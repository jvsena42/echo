package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [CardRepository] backed by [PubkyClient]. Cards live in chunk records at
 * `/pub/loopky/decks/{deckId}/cards/{n}.json` — see `docs/Architecture.md §8.0`.
 *
 * This class owns chunk *reads* and raw chunk *writes*. It deliberately does not touch the
 * manifest: a chunk write has to be reflected in `chunks[n].updated_at` and `card_count`, and
 * splitting that across two classes is exactly how the old per-card layout drifted. Single-card
 * mutations therefore go through
 * [com.github.jvsena42.loopky.data.repository.DeckRepository.upsertCard] instead, which owns both
 * sides of that write.
 */
class CardRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
) : CardRepository {

    private val cache = mutableMapOf<String, MutableMap<String, Card>>()

    /** Chunk `updated_at` per deck, so a re-sync can skip chunks that did not move. */
    private val chunkStamps = mutableMapOf<String, MutableMap<Int, Long>>()

    /** deckId → cardId → the chunk that card was read from. Recorded, never inferred from `ord`. */
    private val cardChunks = mutableMapOf<String, MutableMap<String, Int>>()
    private val cacheLock = Mutex()

    override suspend fun listByDeck(deckId: String): List<Card> = cacheLock.withLock {
        cache[deckId]?.values?.toList()
    }.orEmpty().inStudyOrder()

    override suspend fun fetchByDeck(deck: Deck): Result<List<Card>> = runCatching {
        val fresh = mutableMapOf<String, Card>()
        var firstFailure: Throwable? = null

        // Split first so only the chunks that actually moved cost a request.
        val (stale, unchanged) = deck.chunks.partition { meta ->
            val stamp = cacheLock.withLock { chunkStamps[deck.id]?.get(meta.n) }
            stamp == null || stamp < meta.updatedAt
        }
        unchanged.forEach { meta ->
            cachedCardsIn(deck.id, meta.n).forEach { fresh[it.id] = it }
        }

        // Concurrent: opening a 20k-card deck is ~200 chunk reads, and serially that is 200
        // round trips before a single card renders.
        val results = stale.mapConcurrently { meta -> meta to readChunk(deck, meta.n) }

        for ((meta, result) in results) {
            result
                .onSuccess { cards ->
                    cards.forEach { fresh[it.id] = it }
                    cacheLock.withLock {
                        chunkStamps.getOrPut(deck.id) { mutableMapOf() }[meta.n] = meta.updatedAt
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "fetchByDeck: chunk ${meta.n} unreadable — ${err.message}", err)
                    if (firstFailure == null) firstFailure = err
                    // Keep whatever this chunk contributed last time rather than dropping cards.
                    cachedCardsIn(deck.id, meta.n).forEach { fresh[it.id] = it }
                }
        }

        // One corrupt chunk shouldn't hide the rest of the deck, but a deck whose chunks are *all*
        // unreadable is an unreachable homeserver — reporting that as an empty deck would read to
        // the user as "the cards are gone".
        if (fresh.isEmpty() && firstFailure != null) throw requireNotNull(firstFailure)

        // Replace rather than merge: a card the author removed is simply absent from every chunk,
        // so rebuilding the deck's entry from what we just read is what drops it locally.
        cacheLock.withLock {
            cache[deck.id] = fresh.toMutableMap()
            cardChunks[deck.id]?.keys?.retainAll(fresh.keys)
        }
        fresh.values.toList().inStudyOrder()
    }

    override suspend fun get(deckId: String, cardId: String): Card? {
        cacheLock.withLock { cache[deckId]?.get(cardId) }?.let { return it }
        val author = session.current()?.identity?.pubky ?: return null
        // Without the deck's chunk list there is no way to know which chunk holds this card, so
        // fall back to walking chunks from the start. Callers that already hold the deck should
        // prefer fetchByDeck.
        var n = 0
        while (true) {
            val cards = readChunkAt(author, deckId, n).getOrNull() ?: return null
            cards.forEach { putInCache(it, n) }
            cards.firstOrNull { it.id == cardId }?.let { return it }
            n++
        }
    }

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> =
        runCatching {
            val author = session.requireSession().identity.pubky
            val url = PubkyPaths.cardChunk(author, deckId, chunk)
            if (cards.isEmpty()) {
                pubky.deleteWithSessionRetry(url, session, revalidator).getOrThrow()
            } else {
                val body = loopkyJson.encodeToString(
                    CardChunkDto(deck_id = deckId, chunk = chunk, cards = cards.map { it.toDto() }),
                )
                pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
            }
            cacheLock.withLock {
                val deckCache = cache.getOrPut(deckId) { mutableMapOf() }
                val chunkMap = cardChunks.getOrPut(deckId) { mutableMapOf() }
                // Drop cards this chunk used to hold before re-recording it, so a card moved or
                // removed by this write doesn't linger under the old chunk.
                chunkMap.entries.removeAll { it.value == chunk }
                cards.forEach { deckCache[it.id] = it; chunkMap[it.id] = chunk }
            }
        }

    override suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>> =
        readChunkAt(deck.authorPubky, deck.id, chunk).onSuccess { cards ->
            cards.forEach { putInCache(it, chunk) }
        }

    override suspend fun chunkOf(deckId: String, cardId: String): Int? =
        cacheLock.withLock { cardChunks[deckId]?.get(cardId) }

    override suspend fun evict(deckId: String, cardId: String) {
        cacheLock.withLock {
            cache[deckId]?.remove(cardId)
            cardChunks[deckId]?.remove(cardId)
        }
    }

    private suspend fun readChunkAt(
        authorPubky: String,
        deckId: String,
        chunk: Int,
    ): Result<List<Card>> =
        pubky.get(PubkyPaths.cardChunk(authorPubky, deckId, chunk))
            .mapCatching { json ->
                loopkyJson.decodeFromString<CardChunkDto>(json).cards.map { it.toDomain() }
            }

    private suspend fun putInCache(card: Card, chunk: Int) = cacheLock.withLock {
        cache.getOrPut(card.deckId) { mutableMapOf() }[card.id] = card
        cardChunks.getOrPut(card.deckId) { mutableMapOf() }[card.id] = chunk
    }

    /** Cards the cache holds that were last read from [chunk]. */
    private suspend fun cachedCardsIn(deckId: String, chunk: Int): List<Card> = cacheLock.withLock {
        val ids = cardChunks[deckId]?.filterValues { it == chunk }?.keys.orEmpty()
        val deckCache = cache[deckId] ?: return@withLock emptyList()
        ids.mapNotNull { deckCache[it] }
    }

    companion object {
        private const val TAG = "Loopky/CardRepo"
    }
}
