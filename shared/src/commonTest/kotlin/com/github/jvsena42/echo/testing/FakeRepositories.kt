package com.github.jvsena42.echo.testing

import com.github.jvsena42.echo.data.repository.AuthFlowHandle
import com.github.jvsena42.echo.data.repository.CardRepository
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.DiscoveryRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.ImportRepository
import com.github.jvsena42.echo.data.repository.MediaRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
import com.github.jvsena42.echo.data.repository.TagRepository
import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.ColumnMapping
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.DraftCardImage
import com.github.jvsena42.echo.domain.model.ImportDraft
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.domain.model.ParsedRow
import com.github.jvsena42.echo.domain.model.PubkyIdentity
import com.github.jvsena42.echo.domain.model.PubkyUri
import com.github.jvsena42.echo.domain.model.Separator
import com.github.jvsena42.echo.domain.model.Session
import com.github.jvsena42.echo.domain.model.SrsGrade
import com.github.jvsena42.echo.domain.model.SrsState
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.domain.model.TriageDecision
import com.github.jvsena42.echo.domain.model.review
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeIdentityRepository(var session: Session? = fakeSession()) : IdentityRepository {
    var signOutCount = 0

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

    override suspend fun fetchProfile(pubky: String): Result<PubkyIdentity> =
        Result.failure(UnsupportedOperationException("Not used in tests"))

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

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> {
        publishError?.let { return Result.failure(it) }
        published.add(deck to cards)
        decks[deck.id] = deck
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
}

class FakeCardRepository : CardRepository {
    /** Keyed by deck id, then card id. */
    val cards = mutableMapOf<String, MutableMap<String, Card>>()

    fun seed(vararg seeded: Card) {
        seeded.forEach { cards.getOrPut(it.deckId) { mutableMapOf() }[it.id] = it }
    }

    override suspend fun listByDeck(deckId: String): List<Card> =
        cards[deckId]?.values?.toList() ?: emptyList()

    override suspend fun get(deckId: String, cardId: String): Card? = cards[deckId]?.get(cardId)

    override suspend fun upsert(card: Card): Result<Unit> {
        cards.getOrPut(card.deckId) { mutableMapOf() }[card.id] = card
        return Result.success(Unit)
    }

    override suspend fun delete(deckId: String, cardId: String): Result<Unit> {
        cards[deckId]?.remove(cardId)
        return Result.success(Unit)
    }
}

/** Grades through the real scheduler so VM tests see realistic state transitions. */
class FakeSrsRepository : SrsRepository {
    var due: List<Card> = emptyList()
    val reviews = mutableListOf<Pair<Card, SrsGrade>>()
    val states = mutableMapOf<String, SrsState>()

    override suspend fun dueToday(): List<Card> = due
    override suspend fun dueForDeck(deckId: String): List<Card> = due.filter { it.deckId == deckId }
    var nextDue: Long? = null
    override suspend fun nextDueAt(): Long? = nextDue
    override suspend fun stateFor(cardId: String): SrsState? = states[cardId]

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> {
        reviews.add(card to grade)
        val next = states[card.id].review(card.id, grade, now = 0L)
        states[card.id] = next
        return Result.success(next)
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> {
        states[state.cardId] = state
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

    override suspend fun get(deckId: String, ref: MediaRef): Result<ByteArray> = Result.success(ByteArray(0))

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> = Result.success(Unit)
}

fun testDraft(vararg pairs: Pair<String, String>): ImportDraft = ImportDraft(
    rawText = pairs.joinToString("\n") { "${it.first} — ${it.second}" },
    separator = Separator.EmDash,
    columnMapping = ColumnMapping.DEFAULT_TWO_COL,
    rows = pairs.mapIndexed { index, (front, back) ->
        ParsedRow(index = index, fields = listOf(front, back), isValid = true)
    },
    duplicatesCollapsed = 0,
    flags = emptyList(),
)
