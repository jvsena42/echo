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
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ParsedRow
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TriageDecision
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.domain.model.review
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeIdentityRepository(var session: Session? = fakeSession()) : IdentityRepository {
    var signOutCount = 0

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

    override suspend fun beginSignIn(capabilities: String): Result<AuthFlowHandle> =
        Result.failure(UnsupportedOperationException("Not used in tests"))

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

    /** When set, [listOwned] throws (HomeViewModel wraps the call in runCatching). */
    var listOwnedError: Throwable? = null
    var publishError: Throwable? = null

    override suspend fun getLocal(id: String): Deck? = decks[id]

    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> =
        decks[deckId]?.let { Result.success(it) }
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

    override suspend fun followUser(pubky: String): Result<Unit> {
        follows.add(pubky)
        return Result.success(Unit)
    }

    override suspend fun unfollowUser(pubky: String): Result<Unit> {
        follows.remove(pubky)
        return Result.success(Unit)
    }

    override suspend fun decksFromFollowing(): List<Deck> {
        feedError?.let { throw it }
        return feed
    }

    override suspend fun decksByTag(tag: Tag): List<Deck> = feed.filter { tag in it.tags }
}

class RecordingTagRepository(var trendingTags: List<Tag> = emptyList()) : TagRepository {
    val putTags = mutableListOf<Pair<PubkyUri, Tag>>()
    val removedTags = mutableListOf<Pair<PubkyUri, Tag>>()

    override suspend fun putTag(deckUri: PubkyUri, tag: Tag): Result<Unit> {
        putTags.add(deckUri to tag)
        return Result.success(Unit)
    }

    override suspend fun removeTag(deckUri: PubkyUri, tag: Tag): Result<Unit> {
        removedTags.add(deckUri to tag)
        return Result.success(Unit)
    }

    override suspend fun trending(): List<Tag> = trendingTags
}

class FakeImportRepository(var draft: ImportDraft? = null) : ImportRepository {
    var clearCount = 0
    private val triageDecisions = mutableMapOf<Int, TriageDecision>()
    private val rowEdits = mutableMapOf<Int, Pair<String, String>>()

    override fun currentDraft(): ImportDraft? = draft

    override suspend fun parse(rawText: String, separator: Separator?): Result<ImportDraft> =
        draft?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no draft"))

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
