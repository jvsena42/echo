package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.SrsChunkDto
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.isDue
import com.github.jvsena42.loopky.domain.model.review
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [SrsRepository] backed by [PubkyClient]. Review state lives batched at
 * `/pub/loopky/srs/{authorPubky}/{deckId}/{n}.json` — on **your** homeserver, keyed by the deck's
 * author, for any deck including ones you do not own (see [PubkyPaths.srsChunk]).
 *
 * SRS has the opposite access pattern to cards: writes are one card at a time and frequent, reads
 * need everything at once (`dueForDeck` must know every card's `due_at`). So reads are chunked,
 * and **writes buffer in memory and flush per chunk** — grading 100 cards costs one chunk write
 * at the end instead of 100 record writes as it goes.
 *
 * The flush deliberately does not live in the study ViewModel: `viewModelScope` is cancelled in
 * `onCleared()`, so a flush started there would be killed before it finished. This class owns an
 * app-scoped [CoroutineScope] instead, and also flushes every [FLUSH_EVERY] reviews so a crash
 * costs a few cards rather than a whole session.
 */
@Suppress("TooManyFunctions")
class SrsRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : SrsRepository {

    /** (authorPubky, deckId, cardId) — deck-and-author scoped, so ids cannot collide across decks. */
    private data class StateKey(val authorPubky: String, val deckId: String, val cardId: String)

    private val cache = mutableMapOf<StateKey, SrsState>()

    /**
     * Which chunk each state belongs to. Recorded when the state is loaded or written, never
     * re-derived — a flush that computed the chunk differently from the write that dirtied it
     * would persist the state into the wrong record.
     */
    private val stateChunks = mutableMapOf<StateKey, Int>()

    /** Which (author, deck) chunks hold unflushed reviews. */
    private val dirty = mutableSetOf<Triple<String, String, Int>>()

    /** deckId → the author whose deck it is, learned when the deck is read. */
    private val deckAuthors = mutableMapOf<String, String>()
    private val cacheLock = Mutex()
    private var sinceFlush = 0

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<String> = _changes.asSharedFlow()

    /**
     * Followed decks count too (#33). Review state is already author-keyed and lands on your own
     * homeserver whoever wrote the deck, so a followed deck needs no storage change to be studied —
     * only to be reachable from here, which owned-decks-only made it not.
     */
    override suspend fun dueToday(): List<Card> = studiableDecks().flatMap { dueForDeck(it.id) }

    /** Decks you can study: the ones you own plus the ones you follow. */
    private suspend fun studiableDecks(): List<Deck> {
        val owned = deckRepository.listOwned()
        // A followed deck lives on someone else's homeserver, so listing it can fail on its own.
        // That must cost you the followed decks, not your whole queue.
        val followed = runSuspendCatching { deckRepository.listFollowed() }.getOrElse {
            Log.e(TAG, "dueToday: followed decks unavailable — ${it.message}", it)
            emptyList()
        }
        return (owned + followed).distinctBy { it.id }
    }

    override suspend fun dueForDeck(deckId: String): List<Card> {
        val deck = deckRepository.sync(deckId)
            .onFailure { Log.e(TAG, "dueForDeck: sync failed for $deckId — ${it.message}", it) }
            .getOrNull() ?: deckRepository.getLocal(deckId)
        val author = deck?.authorPubky ?: session.current()?.identity?.pubky ?: return emptyList()
        cacheLock.withLock { deckAuthors[deckId] = author }

        val cards = cardRepository.listByDeck(deckId)
        loadChunksFor(author, deckId)

        val now = epochMillis()
        return cards
            .map { card -> card to stateOf(author, deckId, card.id) }
            .filter { (_, state) -> state.isDue(now) }
            .sortedWith(compareBy({ (_, state) -> state?.dueAt ?: 0L }, { (card, _) -> card.id }))
            .map { it.first }
    }

    override suspend fun nextDueAt(): Long? {
        val now = epochMillis()
        return cacheLock.withLock { cache.values.map { it.dueAt } }
            .filter { it > now }
            .minOrNull()
    }

    override suspend fun stateFor(cardId: String): SrsState? =
        cacheLock.withLock { cache.entries.firstOrNull { it.key.cardId == cardId }?.value }

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> = runSuspendCatching {
        val author = authorFor(card.deckId)
        val current = stateOf(author, card.deckId, card.id)
        val next = current.review(card.id, grade, epochMillis())
        upsert(card.deckId, next).getOrThrow()
        next
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> = runSuspendCatching {
        val author = authorFor(deckId)
        val key = StateKey(author, deckId, state.cardId)
        val chunk = cacheLock.withLock { stateChunks[key] } ?: chunkFor(deckId, state.cardId)

        val shouldFlush = cacheLock.withLock {
            cache[key] = state
            stateChunks[key] = chunk
            dirty.add(Triple(author, deckId, chunk))
            ++sinceFlush >= FLUSH_EVERY
        }
        _changes.tryEmit(deckId)

        // Bounded loss: a crash costs at most FLUSH_EVERY reviews, not the whole session.
        if (shouldFlush) flush().getOrThrow()
    }

    override suspend fun flush(): Result<Unit> = runSuspendCatching {
        val pending = cacheLock.withLock {
            val snapshot = dirty.toList()
            dirty.clear()
            sinceFlush = 0
            snapshot
        }
        if (pending.isEmpty()) return@runSuspendCatching

        val owner = session.requireSession().identity.pubky
        // Not error handling — a restore-on-abnormal-exit, which is why it rethrows unconditionally
        // and catches Throwable rather than using runSuspendCatching. Cancellation has to restore
        // too: flush() is reachable from review()/upsert() on viewModelScope, so closing the study
        // screen mid-flush would otherwise drop up to FLUSH_EVERY reviews on the floor.
        @Suppress("TooGenericExceptionCaught")
        try {
            pending.mapConcurrently { (author, deckId, chunk) ->
                writeChunk(owner, author, deckId, chunk)
            }
        } catch (err: Throwable) {
            // Put them back so the next flush retries rather than silently losing progress.
            cacheLock.withLock { dirty.addAll(pending) }
            throw err
        }
        Unit
    }

    override fun flushAsync() {
        scope.launch {
            flush().onFailure { Log.e(TAG, "flushAsync: FAILED — ${it.message}", it) }
        }
    }

    private suspend fun writeChunk(owner: String, author: String, deckId: String, chunk: Int) {
        val states = cacheLock.withLock {
            cache.filterKeys { key ->
                key.authorPubky == author && key.deckId == deckId && stateChunks[key] == chunk
            }.values.map { it.toDto() }
        }
        val body = loopkyJson.encodeToString(
            SrsChunkDto(deck_id = deckId, author_pubky = author, chunk = chunk, states = states),
        )
        pubky.putWithSessionRetry(
            PubkyPaths.srsChunk(owner, author, deckId, chunk),
            body,
            session,
            revalidator,
        ).getOrThrow()
    }

    /**
     * Read every SRS chunk for a deck, concurrently, once per session.
     *
     * The chunk set is **discovered** by listing rather than derived from the card count. Deriving
     * it would silently miss any chunk the writer placed elsewhere — and [chunkFor] does exactly
     * that when a card's deck position is not yet known, so a guessed range would make those
     * reviews permanently unreadable.
     */
    private suspend fun loadChunksFor(author: String, deckId: String) {
        val owner = session.current()?.identity?.pubky ?: return
        val loaded = cacheLock.withLock { loadedDecks.contains(author to deckId) }
        if (loaded) return

        val root = PubkyPaths.srsRoot(owner, author, deckId)
        val urls = pubky.list(root).getOrNull()
            ?.let { payload ->
                runCatching { loopkyJson.decodeFromString<List<String>>(payload) }
                    .getOrDefault(emptyList())
                    .filter { it.startsWith("pubky://") }
            }
            .orEmpty()

        urls.mapConcurrently { url ->
            pubky.get(url)
                .mapCatching { loopkyJson.decodeFromString<SrsChunkDto>(it) }
                .onSuccess { dto ->
                    cacheLock.withLock {
                        dto.states.forEach { state ->
                            val key = StateKey(author, deckId, state.card_id)
                            // A buffered review is newer than what is on the homeserver.
                            if (key !in cache) cache[key] = state.toDomain()
                            stateChunks[key] = dto.chunk
                        }
                    }
                }
                .onFailure { Log.e(TAG, "loadChunksFor: $url unreadable — ${it.message}", it) }
        }
        cacheLock.withLock { loadedDecks.add(author to deckId) }
    }

    private val loadedDecks = mutableSetOf<Pair<String, String>>()

    private suspend fun stateOf(author: String, deckId: String, cardId: String): SrsState? =
        cacheLock.withLock { cache[StateKey(author, deckId, cardId)] }

    private suspend fun authorFor(deckId: String): String =
        cacheLock.withLock { deckAuthors[deckId] }
            ?: deckRepository.getLocal(deckId)?.authorPubky
            ?: session.requireSession().identity.pubky

    /**
     * Which SRS chunk a card's state belongs in, for a card seen for the first time.
     *
     * Mirrors the card's own position in the deck, so a deck's SRS chunks line up with its card
     * chunks and a study session touches few of them. Falls back to a hash when the deck's card
     * order is not loaded — still correct, just less tidy. Whichever answer is used, it is then
     * recorded in [stateChunks] and never recomputed.
     */
    private suspend fun chunkFor(deckId: String, cardId: String): Int {
        val index = cardRepository.listByDeck(deckId).indexOfFirst { it.id == cardId }
        return if (index >= 0) {
            index / CHUNK_SIZE
        } else {
            (cardId.hashCode().toUInt() % CHUNK_BUCKETS.toUInt()).toInt()
        }
    }

    companion object {
        private const val TAG = "Loopky/SrsRepo"

        /** Room for a burst of reviews while a collector is mid-reload; oldest is dropped. */
        private const val CHANGE_BUFFER = 8

        /** Reviews buffered before an automatic flush. Caps what a crash can cost. */
        internal const val FLUSH_EVERY = 20

        /** Fallback bucket count when a card's deck position is unknown. */
        private const val CHUNK_BUCKETS = 64
    }
}
