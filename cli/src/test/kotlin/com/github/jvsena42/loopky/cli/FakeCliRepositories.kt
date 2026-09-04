package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.CompactionOutcome
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PinnedBlob
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The two repositories a card command touches, and nothing else.
 *
 * Hand-written here rather than reached for from `:shared`'s `commonTest`, which is not on this
 * module's classpath. Everything a given command does not call throws instead of returning a
 * plausible zero: a fake that answers politely turns "this command quietly stopped calling the
 * homeserver" into a passing test.
 */
class FakeDeckRepository(
    private var deck: Deck,
    private val onUpsert: (Card) -> Deck = { deck.copy(cardCount = deck.cardCount + 1) },
    /**
     * What `publish` does. A `.apkg` import is the only command that publishes, and the failing
     * case is as load-bearing as the succeeding one: an aborted publish is what decides whether
     * the blobs it already uploaded get swept back out.
     */
    private val onPublish: (Deck, List<Card>) -> Result<Deck> = { published, cards ->
        Result.success(published.copy(cardCount = cards.size))
    },
    /** Decks `import --resume` matches its `--title` against. */
    private val owned: List<Deck> = emptyList(),
) : DeckRepository {

    val upserted = mutableListOf<Card>()

    /**
     * Every deck handed to `updateMetadata`, so a test can assert on what `deck edit` *wrote*
     * rather than only on what it printed — including that it wrote nothing at all.
     */
    val metadataWrites = mutableListOf<Deck>()

    /** The cards handed to `publish`, so a test can read back what a card ended up referencing. */
    val published = mutableListOf<Card>()

    override suspend fun sync(deckId: String): Result<Deck> = Result.success(deck)

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> {
        published += cards
        return onPublish(deck, cards)
    }

    override suspend fun listOwned(): List<Deck> = owned

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> {
        upserted += card
        deck = onUpsert(card)
        return Result.success(deck)
    }

    /**
     * Mirrors the real one where it matters to a caller: the chunk table and `card_count` come
     * from the deck on the homeserver, never from the deck the caller assembled.
     */
    override suspend fun updateMetadata(deck: Deck): Result<Deck> {
        metadataWrites += deck
        this.deck = deck.copy(chunks = this.deck.chunks, cardCount = this.deck.cardCount)
        return Result.success(this.deck)
    }

    override val changes: SharedFlow<Unit> = MutableSharedFlow()

    private fun no(name: String): Nothing = error("FakeDeckRepository.$name is not part of this test")

    override suspend fun getLocal(id: String): Deck? = no("getLocal")
    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> = no("fetchRemote")
    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> = no("publish")
    override suspend fun delete(deckId: String): Result<Unit> = no("delete")
    override suspend fun appendCards(deckId: String, cards: List<Card>): Result<Deck> = no("appendCards")
    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> = no("deleteCard")
    override suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck> = no("moveCard")
    override suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit> = no("rehostBlob")
    override suspend fun rehostPendingMedia(deckId: String, maxChunks: Int): Result<RehostOutcome> =
        no("rehostPendingMedia")
    override suspend fun decksPendingRehost(): List<Deck> = no("decksPendingRehost")
    override suspend fun compactDeck(deckId: String, maxMerges: Int): Result<CompactionOutcome> =
        no("compactDeck")
    override suspend fun decksPendingCompaction(): List<Deck> = no("decksPendingCompaction")
    override suspend fun listByAuthor(authorPubky: String): List<Deck> = no("listByAuthor")
    override suspend fun followDeck(deck: Deck): Result<Unit> = no("followDeck")
    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> = no("unfollowDeck")
    override suspend fun isFollowingDeck(deckId: String): Boolean = no("isFollowingDeck")
    override suspend fun listFollowed(): List<Deck> = no("listFollowed")
    override suspend fun listFollowedBy(ownerPubky: String): List<Deck> = no("listFollowedBy")
    override suspend fun hasUpdate(deckId: String): Boolean = no("hasUpdate")
    override suspend fun markSeen(deck: Deck) = no("markSeen")
    override suspend fun clone(source: Deck): Result<Deck> = no("clone")
}

class FakeCardRepository(private val existing: List<Card> = emptyList()) : CardRepository {

    override suspend fun listByDeck(deckId: String): List<Card> = existing
    override suspend fun fetchByDeck(deck: Deck): Result<List<Card>> = Result.success(existing)
    override suspend fun get(deckId: String, cardId: String): Card? = existing.firstOrNull { it.id == cardId }

    private fun no(name: String): Nothing = error("FakeCardRepository.$name is not part of this test")

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> = no("writeChunk")
    override suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>> = no("readChunk")
    override suspend fun chunkOf(deckId: String, cardId: String): Int? = no("chunkOf")
    override suspend fun evict(deckId: String, cardId: String) = no("evict")
}

/** A deck with nothing interesting in it but the fields a card command reads. */
fun testDeck(id: String = "d1", cardCount: Int = 0) = Deck(
    id = id,
    authorPubky = "pk:test",
    title = "Test deck",
    description = null,
    coverImageRef = null,
    tags = emptyList(),
    createdAt = 0L,
    updatedAt = 0L,
    cardCount = cardCount,
    source = null as DeckSource?,
)

/**
 * Blob storage that records rather than writes.
 *
 * The one place a fake has to be faithful about *identity*: `putImage` hands back a content
 * addressed ref, and both the per-run upload memo and `--resume`'s dedupe are built on that being
 * the same sha for the same bytes.
 */
class FakeMediaRepository(private val failAfter: Int = Int.MAX_VALUE) : MediaRepository {

    val puts = mutableListOf<ByteArray>()
    val deleted = mutableListOf<String>()

    override val pinnedFetches: SharedFlow<PinnedBlob> = MutableSharedFlow()

    override suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image> {
        if (puts.size >= failAfter) return Result.failure(IllegalStateException("no space left"))
        puts += bytes
        val sha = bytes.fold(7) { acc, byte -> acc * 31 + byte }.toString()
        return Result.success(
            MediaRef.Image(path = "media/$sha.jpg", mime = mime, sha256 = sha, width = null, height = null),
        )
    }

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> {
        deleted += ref.sha256
        return Result.success(Unit)
    }

    private fun no(name: String): Nothing = error("FakeMediaRepository.$name is not part of this test")

    override suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio> =
        no("putAudio")
    override suspend fun get(authorPubky: String, deckId: String, ref: MediaRef): Result<ByteArray> = no("get")
    override suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef> = no("rehost")
}
