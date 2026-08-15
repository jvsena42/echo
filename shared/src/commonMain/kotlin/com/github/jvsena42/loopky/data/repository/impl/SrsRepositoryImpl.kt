package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.SrsStateDto
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.isDue
import com.github.jvsena42.loopky.domain.model.review
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [SrsRepository] backed by [PubkyClient]. Per-card review state lives at
 * `/pub/loopky/decks/{deckId}/srs/{cardId}.json` — see `PubkyPaths.srs`. Mirrors [CardRepositoryImpl]:
 * Pubky is the source of truth, with a [Mutex]-guarded in-memory cache for the session.
 *
 * Building the due queue enumerates owned decks via [DeckRepository] and their cards via
 * [CardRepository]; a [DeckRepository.sync] first populates the card cache from the homeserver.
 */
class SrsRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
) : SrsRepository {

    private val cache = mutableMapOf<String, SrsState>()
    private val cacheLock = Mutex()

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<String> = _changes.asSharedFlow()

    override suspend fun dueToday(): List<Card> {
        val decks = deckRepository.listOwned()
        return decks.flatMap { dueForDeck(it.id) }
    }

    override suspend fun dueForDeck(deckId: String): List<Card> {
        // Ensure the card cache is populated from the homeserver before we read it.
        deckRepository.sync(deckId).onFailure {
            Log.e(TAG, "dueForDeck: sync failed for $deckId — ${it.message}", it)
        }
        val now = epochMillis()
        val cards = cardRepository.listByDeck(deckId)
        return cards
            .map { card -> card to fetchState(deckId, card.id) }
            .filter { (_, state) -> state.isDue(now) }
            .sortedWith(compareBy({ (_, state) -> state?.dueAt ?: 0L }, { (card, _) -> card.id }))
            .map { it.first }
    }

    /**
     * Reads the cache `dueToday`/`dueForDeck` has already warmed, so this is an in-memory min
     * rather than another round of homeserver reads.
     */
    override suspend fun nextDueAt(): Long? {
        val now = epochMillis()
        return cacheLock.withLock { cache.values.map { it.dueAt } }
            .filter { it > now }
            .minOrNull()
    }

    override suspend fun stateFor(cardId: String): SrsState? = cacheLock.withLock { cache[cardId] }

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> = runCatching {
        val current = fetchState(card.deckId, card.id)
        val next = current.review(card.id, grade, epochMillis())
        upsert(card.deckId, next).getOrThrow()
        next
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> = runCatching {
        val author = session.requireSession().identity.pubky
        val url = PubkyPaths.srs(author, deckId, state.cardId)
        val body = loopkyJson.encodeToString(state.toDto())
        pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
        cacheLock.withLock { cache[state.cardId] = state }
        _changes.tryEmit(deckId)
        Unit
    }

    /** Read state from cache, falling back to the homeserver (a 404 means a new, never-reviewed card). */
    private suspend fun fetchState(deckId: String, cardId: String): SrsState? {
        cacheLock.withLock { cache[cardId] }?.let { return it }
        val author = session.current()?.identity?.pubky ?: return null
        return pubky.get(PubkyPaths.srs(author, deckId, cardId))
            .mapCatching { loopkyJson.decodeFromString<SrsStateDto>(it).toDomain() }
            .onSuccess { state -> cacheLock.withLock { cache[cardId] = state } }
            .getOrNull()
    }

    companion object {
        private const val TAG = "Loopky/SrsRepo"

        /** Room for a burst of reviews while a collector is mid-reload; oldest is dropped. */
        private const val CHANGE_BUFFER = 8
    }
}
