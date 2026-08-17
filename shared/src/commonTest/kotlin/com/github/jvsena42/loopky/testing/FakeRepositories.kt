package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ParsedRow
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TriageDecision
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.domain.model.review
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeIdentityRepository(var session: Session? = fakeSession()) : IdentityRepository {
    var signOutCount = 0

    /** What [beginSignIn] hands back, and what the returned handle's `complete()` answers. */
    var authUrl: String = "pubkyauth:///?caps=&secret=test"
    var beginSignInError: Throwable? = null
    var completionResult: Result<Session> = Result.success(fakeSession())
    var beginSignInCount = 0

    /** Profiles served by [fetchProfile]; a pubky that is absent fails as an unpublished one would. */
    val profiles = mutableMapOf<String, PubkyIdentity>()
    val fetchedProfiles = mutableListOf<String>()

    override suspend fun currentSession(): Session? = session
    override suspend fun loadPersistedSession(): Session? = session

    override suspend fun signIn(): Result<Session> =
        session?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no session"))

    override suspend fun signOut(): Result<Unit> {
        signOutCount++
        session = null
        return Result.success(Unit)
    }

    override suspend fun beginSignIn(capabilities: String): Result<AuthFlowHandle> {
        beginSignInCount++
        beginSignInError?.let { return Result.failure(it) }
        return Result.success(
            object : AuthFlowHandle {
                override val authUrl = this@FakeIdentityRepository.authUrl
                override suspend fun complete(): Result<Session> = completionResult
            },
        )
    }

    override suspend fun fetchProfile(pubky: String, forceRefresh: Boolean): Result<PubkyIdentity> {
        fetchedProfiles += pubky
        return profiles[pubky]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no profile for $pubky"))
    }

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> =
        Result.failure(UnsupportedOperationException("Not used in tests"))
}

class FakeDeckRepository : DeckRepository {
    val decks = mutableMapOf<String, Deck>()
    val published = mutableListOf<Pair<Deck, List<Card>>>()
    val deleted = mutableListOf<String>()

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Emit a change as the real repository would, without going through a mutation. */
    fun emitChange() {
        _changes.tryEmit(Unit)
    }

    /** When set, [listOwned] throws (HomeViewModel wraps the call in runSuspendCatching). */
    var listOwnedError: Throwable? = null
    var publishError: Throwable? = null

    /** When set, [publish] blocks on it, so a test can act while the upload is still in flight. */
    var publishGate: CompletableDeferred<Unit>? = null

    override suspend fun getLocal(id: String): Deck? = decks[id] ?: followedDecks[id]

    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> =
        getLocal(deckId)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("deck $deckId not found"))

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> =
        publish(deck, cards, onProgress = {})

    val progressReports = mutableListOf<PublishProgress>()

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> {
        publishError?.let { return Result.failure(it) }
        // Held open so a test can catch a publish mid-flight, the way cancelling a real upload
        // does. The real publish() is a runSuspendCatching, so cancellation propagates rather than
        // coming back as a failed Result — mirrored here by not catching it.
        publishGate?.await()
        published.add(deck to cards)
        decks[deck.id] = deck
        val progress = PublishProgress(1, 1, cards.size, cards.size, done = true)
        progressReports.add(progress)
        onProgress(progress)
        _changes.tryEmit(Unit)
        return Result.success(deck)
    }

    override suspend fun updateMetadata(deck: Deck): Result<Deck> {
        decks[deck.id] = deck
        _changes.tryEmit(Unit)
        return Result.success(deck)
    }

    override suspend fun delete(deckId: String): Result<Unit> {
        deleted.add(deckId)
        decks.remove(deckId)
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    override suspend fun listOwned(): List<Deck> {
        listOwnedError?.let { throw it }
        return decks.values.toList()
    }

    override suspend fun listByAuthor(authorPubky: String): List<Deck> =
        decks.values.filter { it.authorPubky == authorPubky }

    override suspend fun sync(deckId: String): Result<Deck> =
        decks[deckId]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("deck $deckId not found"))

    val upsertedCards = mutableListOf<Pair<String, Card>>()
    val deletedCards = mutableListOf<Pair<String, String>>()
    var upsertCardError: Throwable? = null

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> {
        upsertCardError?.let { return Result.failure(it) }
        upsertedCards.add(deckId to card)
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        val updated = deck.copy(cardCount = deck.cardCount + 1)
        decks[deckId] = updated
        _changes.tryEmit(Unit)
        return Result.success(updated)
    }

    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> {
        deletedCards.add(deckId to cardId)
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        val updated = deck.copy(cardCount = (deck.cardCount - 1).coerceAtLeast(0))
        decks[deckId] = updated
        _changes.tryEmit(Unit)
        return Result.success(updated)
    }

    // ── Follow deck (#33) ────────────────────────────────────────────────

    /** Decks followed rather than owned. Kept apart from [decks] so a test can tell them apart. */
    val followedDecks = mutableMapOf<String, Deck>()
    val seen = mutableListOf<String>()
    var followError: Throwable? = null
    var listFollowedError: Throwable? = null

    /** Deck ids whose author has published changes since they were last opened. */
    val updatedDecks = mutableSetOf<String>()

    override suspend fun followDeck(deck: Deck): Result<Unit> {
        followError?.let { return Result.failure(it) }
        followedDecks[deck.id] = deck
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        followedDecks.remove(deckId)
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    override suspend fun isFollowingDeck(deckId: String): Boolean = deckId in followedDecks

    override suspend fun listFollowed(): List<Deck> {
        listFollowedError?.let { throw it }
        return followedDecks.values.toList()
    }

    override suspend fun hasUpdate(deckId: String): Boolean = deckId in updatedDecks

    override suspend fun markSeen(deck: Deck) {
        seen.add(deck.id)
        updatedDecks.remove(deck.id)
    }

    val cloned = mutableListOf<Deck>()
    var cloneError: Throwable? = null

    /** Held open so a test can act while a clone is still in flight. */
    var cloneGate: CompletableDeferred<Unit>? = null

    override suspend fun clone(source: Deck): Result<Deck> {
        cloneError?.let { return Result.failure(it) }
        cloneGate?.await()
        cloned.add(source)
        val copy = source.copy(
            id = "clone-of-${source.id}",
            authorPubky = TEST_PUBKY,
            source = DeckSource(kind = DeckSource.Kind.Clone, uri = source.pubkyUri.value),
        )
        decks[copy.id] = copy
        followedDecks.remove(source.id)
        _changes.tryEmit(Unit)
        return Result.success(copy)
    }
}

class FakeCardRepository : CardRepository {
    /** Keyed by deck id, then card id. */
    val cards = mutableMapOf<String, MutableMap<String, Card>>()

    /**
     * Cards only [fetchByDeck] can see, keyed by deck id — the test stand-in for records that
     * live on a homeserver but have not been read into the session cache yet.
     */
    val remoteCards = mutableMapOf<String, MutableMap<String, Card>>()

    /** When set, [fetchByDeck] fails (an unreachable homeserver). */
    var fetchError: Throwable? = null

    var fetchCount = 0
        private set

    fun seed(vararg seeded: Card) {
        seeded.forEach { cards.getOrPut(it.deckId) { mutableMapOf() }[it.id] = it }
    }

    /** Seed cards that are only reachable through [fetchByDeck], not through the cache. */
    fun seedRemote(vararg seeded: Card) {
        seeded.forEach { remoteCards.getOrPut(it.deckId) { mutableMapOf() }[it.id] = it }
    }

    override suspend fun listByDeck(deckId: String): List<Card> =
        cards[deckId]?.values?.toList().orEmpty().inStudyOrder()

    override suspend fun fetchByDeck(deck: Deck): Result<List<Card>> {
        fetchCount++
        fetchError?.let { return Result.failure(it) }
        remoteCards[deck.id]?.forEach { (id, card) ->
            cards.getOrPut(deck.id) { mutableMapOf() }[id] = card
        }
        return Result.success(cards[deck.id]?.values?.toList().orEmpty().inStudyOrder())
    }

    override suspend fun get(deckId: String, cardId: String): Card? = cards[deckId]?.get(cardId)

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> {
        writtenChunks.add(deckId to chunk)
        val deckCache = this.cards.getOrPut(deckId) { mutableMapOf() }
        cards.forEach { deckCache[it.id] = it }
        return Result.success(Unit)
    }

    override suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>> =
        Result.success(cards[deck.id]?.values?.toList().orEmpty().inStudyOrder())

    override suspend fun chunkOf(deckId: String, cardId: String): Int? =
        if (cards[deckId]?.containsKey(cardId) == true) 0 else null

    override suspend fun evict(deckId: String, cardId: String) {
        cards[deckId]?.remove(cardId)
    }

    val writtenChunks = mutableListOf<Pair<String, Int>>()
}

/** Grades through the real scheduler so VM tests see realistic state transitions. */
class FakeSrsRepository : SrsRepository {
    var due: List<Card> = emptyList()
    val reviews = mutableListOf<Pair<Card, SrsGrade>>()
    val states = mutableMapOf<String, SrsState>()

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<String> = _changes.asSharedFlow()

    override suspend fun dueToday(): List<Card> = due
    override suspend fun dueForDeck(deckId: String): List<Card> = due.filter { it.deckId == deckId }
    var nextDue: Long? = null
    override suspend fun nextDueAt(): Long? = nextDue
    override suspend fun stateFor(cardId: String): SrsState? = states[cardId]

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> {
        reviews.add(card to grade)
        val next = states[card.id].review(card.id, grade, now = 0L)
        upsert(card.deckId, next)
        return Result.success(next)
    }

    var flushes = 0
        private set

    override suspend fun flush(): Result<Unit> {
        flushes++
        return Result.success(Unit)
    }

    override fun flushAsync() {
        flushes++
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> {
        states[state.cardId] = state
        _changes.tryEmit(deckId)
        return Result.success(Unit)
    }
}

class FakeDiscoveryRepository : DiscoveryRepository {
    var feed: List<Deck> = emptyList()
    var feedError: Throwable? = null
    val follows = mutableListOf<String>()

    override suspend fun following(): List<String> = follows.toList()
    override suspend fun isFollowing(pubky: String): Boolean = pubky in follows

    /** When set, follow/unfollow fails — the optimistic pill must revert. */
    var followError: Throwable? = null

    override suspend fun followUser(pubky: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        follows.add(pubky)
        return Result.success(Unit)
    }

    override suspend fun unfollowUser(pubky: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        follows.remove(pubky)
        return Result.success(Unit)
    }

    /** The Loopky accounts each follow list resolves to, keyed by whose list it is. */
    var followingByUser: Map<String, List<PubkyIdentity>> = emptyMap()
    var followersByUser: Map<String, List<PubkyIdentity>> = emptyMap()
    var followListError: Throwable? = null

    /** Held open, this lets a test assert the screen renders before the counts land. */
    var followListGate: CompletableDeferred<Unit>? = null

    override suspend fun followingProfiles(pubky: String): List<PubkyIdentity> {
        followListGate?.await()
        followListError?.let { throw it }
        return followingByUser[pubky].orEmpty()
    }

    override suspend fun followerProfiles(pubky: String): List<PubkyIdentity> {
        followListGate?.await()
        followListError?.let { throw it }
        return followersByUser[pubky].orEmpty()
    }

    override suspend fun decksFromFollowing(): List<Deck> {
        feedGate?.await()
        feedError?.let { throw it }
        return feed
    }

    override suspend fun decksByTag(tag: Tag): List<Deck> = feed.filter { tag in it.tags }

    /** Decks reachable only through the indexer, i.e. not from anyone the user follows. */
    var globalDecks: List<Deck> = emptyList()
    var loopkyUsers: List<PubkyIdentity> = emptyList()

    /** What each strip asked for, so a test can pin the cost budget. */
    val globalRequests = mutableListOf<Pair<Tag, Int>>()
    val suggestedRequests = mutableListOf<Int>()

    /**
     * Held open, these let a test assert the other strips still settle while one is in flight —
     * the whole point of loading Discover section by section.
     */
    var globalGate: CompletableDeferred<Unit>? = null
    var feedGate: CompletableDeferred<Unit>? = null
    var peopleGate: CompletableDeferred<Unit>? = null

    /** Exact per-label results, when a test needs finer control than [globalDecks] gives. */
    var globalDecksByTag: Map<Tag, List<Deck>>? = null

    override suspend fun decksByTagGlobal(tag: Tag, limit: Int): List<Deck> {
        globalRequests.add(tag to limit)
        globalGate?.await()
        globalDecksByTag?.let { return it[tag].orEmpty().take(limit) }
        return globalDecks.filter { tag in it.tags || tag == ReservedTags.DECK }.take(limit)
    }

    override suspend fun loopkyUsers(limit: Int): List<PubkyIdentity> = loopkyUsers.take(limit)

    /** Mirrors the real union: directory first, then deck authors, minus self and follows. */
    override suspend fun suggestedPeople(seedDecks: List<Deck>, limit: Int): List<PubkyIdentity> {
        suggestedRequests.add(limit)
        peopleGate?.await()
        val directory = loopkyUsers.filterNot { it.pubky in follows }
        val seen = directory.mapTo(mutableSetOf()) { it.pubky }
        val authors = seedDecks.map { it.authorPubky }
            .distinct()
            .filter { it !in follows && seen.add(it) }
            .map { PubkyIdentity(it, displayName = null, avatarUrl = null, bio = null) }
        return (directory + authors).take(limit)
    }
}

class RecordingTagRepository : TagRepository {
    val putTags = mutableListOf<Pair<PubkyUri, Tag>>()
    val removedTags = mutableListOf<Pair<PubkyUri, Tag>>()

    /** Reserved writes are recorded apart so a test can assert Loopky's own index labels. */
    val putReservedTags = mutableListOf<Pair<PubkyUri, Tag>>()
    val removedReservedTags = mutableListOf<Pair<PubkyUri, Tag>>()

    /** When set, every write fails with it — publish and sign-in must survive that. */
    var failWith: Throwable? = null

    override suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        putTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        removedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        putReservedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        removedReservedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** Deck topics the indexer would aggregate to; recorded so a test can pin the ask. */
    var deckTags: List<Tag> = emptyList()
    val deckTagRequests = mutableListOf<Pair<Int, Int>>()

    override suspend fun trendingDeckTags(sampleSize: Int, limit: Int): List<Tag> {
        deckTagRequests.add(sampleSize to limit)
        return deckTags.take(limit)
    }

    /** Indexer reads, canned per label. */
    var subjectsByTag: Map<Tag, List<TaggedSubject>> = emptyMap()
    var taggersByTag: Map<Tag, List<String>> = emptyMap()
    var selfTaggers: Set<String> = emptySet()
    var counts: Map<PubkyUri, Map<Tag, Int>> = emptyMap()

    override suspend fun taggedSubjects(tag: Tag, limit: Int): List<TaggedSubject> =
        subjectsByTag[tag].orEmpty().take(limit)

    override suspend fun taggersOf(tag: Tag, limit: Int): List<String> =
        taggersByTag[tag].orEmpty().take(limit)

    /** Every self-tag lookup, so a test can prove a repeated question is answered from a cache. */
    val selfTagChecks = mutableListOf<String>()

    override suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean {
        selfTagChecks.add(pubky)
        return pubky in selfTaggers
    }

    override suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int> =
        counts[subjectUri].orEmpty()
}

class FakeImportRepository(var draft: ImportDraft? = null) : ImportRepository {
    var clearCount = 0
    private val triageDecisions = mutableMapOf<Int, TriageDecision>()
    private val rowEdits = mutableMapOf<Int, Pair<String, String>>()

    override fun currentDraft(): ImportDraft? = draft

    override suspend fun parse(rawText: String, separator: Separator?): Result<ImportDraft> =
        draft?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no draft"))

    override suspend fun parseBulk(
        rawText: String,
        separator: Separator?,
        suggestedTitle: String?,
    ): Result<ImportDraft> =
        parse(rawText, separator).map { draft ->
            draft.copy(suggestedTitle = suggestedTitle).also { this.draft = it }
        }

    override fun decisions(): Map<Int, TriageDecision> = triageDecisions.toMap()

    override fun setDecision(rowIndex: Int, decision: TriageDecision) {
        triageDecisions[rowIndex] = decision
    }

    override fun updateRow(rowIndex: Int, front: String, back: String) {
        rowEdits[rowIndex] = front to back
    }

    private val rowImages = mutableMapOf<Pair<Int, Boolean>, DraftCardImage>()

    override fun setRowImage(rowIndex: Int, isFront: Boolean, image: DraftCardImage?) {
        if (image == null) rowImages.remove(rowIndex to isFront) else rowImages[rowIndex to isFront] = image
    }

    override fun rowImage(rowIndex: Int, isFront: Boolean): DraftCardImage? = rowImages[rowIndex to isFront]

    override fun keptRows(): List<ParsedRow> =
        draft?.rows?.filter { triageDecisions[it.index] != TriageDecision.Discard } ?: emptyList()

    override fun clear() {
        clearCount++
        draft = null
        triageDecisions.clear()
        rowEdits.clear()
    }
}

class FakeMediaRepository : MediaRepository {
    val putImages = mutableListOf<Triple<String, ByteArray, String>>()

    override suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image> {
        putImages.add(Triple(deckId, bytes, mime))
        return Result.success(
            MediaRef.Image(path = "media/fake.jpg", mime = mime, sha256 = "fake", width = null, height = null),
        )
    }

    override suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio> =
        Result.success(MediaRef.Audio(path = "media/fake.m4a", mime = mime, sha256 = "fake", durationMs = null))

    val gets = mutableListOf<Triple<String, String, MediaRef>>()

    override suspend fun get(
        authorPubky: String,
        deckId: String,
        ref: MediaRef,
    ): Result<ByteArray> {
        gets.add(Triple(authorPubky, deckId, ref))
        return Result.success(ByteArray(0))
    }

    override suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef> = Result.success(ref)

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> = Result.success(Unit)
}

fun testDraft(vararg pairs: Pair<String, String>): ImportDraft = ImportDraft(
    rawText = pairs.joinToString("\n") { "${it.first} — ${it.second}" },
    separator = Separator.EmDash,
    rows = pairs.mapIndexed { index, (front, back) ->
        ParsedRow(index = index, fields = listOf(front, back), isValid = true)
    },
    duplicatesCollapsed = 0,
)

/**
 * Delegates to a real [CardRepository] but fails once it has written [failAfter] chunks, standing
 * in for a publish that dies part-way through uploading a large deck.
 */
class FailingChunkCardRepository(
    private val delegate: CardRepository,
    private val failAfter: Int = 1,
) : CardRepository by delegate {
    private var written = 0

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> {
        if (written >= failAfter) return Result.failure(IllegalStateException("upload died"))
        written++
        return delegate.writeChunk(deckId, chunk, cards)
    }
}
