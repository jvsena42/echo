package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.SubscriptionDto
import com.github.jvsena42.loopky.data.pubky.absolutizedTo
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ORD_STRIDE
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.generateId
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
 * [DeckRepository] backed by [PubkyClient]. Pubky is the source of truth; an in-memory map is
 * used as a per-session cache so `getLocal` and `listOwned` can return instantly after a sync.
 *
 * See `docs/Architecture.md §8.0` for the on-homeserver layout this implementation writes.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class DeckRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val cardRepo: CardRepository,
    private val revalidator: SessionRevalidator,
    private val tagRepo: TagRepository,
    private val mediaRepo: MediaRepository,
    private val backgroundTasks: BackgroundTasks,
    /**
     * App-scoped, like `SrsRepositoryImpl`'s: re-hosting outlives whatever screen triggered it, so
     * it cannot run on a `viewModelScope` that dies in `onCleared()`. Injectable so tests can pass
     * `backgroundScope` instead of leaking work onto `Dispatchers.Default` under `runTest`.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : DeckRepository {

    init {
        // Sequential by construction — `collect` handles one signal at a time — so two cards
        // sharing a blob cannot both copy it.
        scope.launch {
            mediaRepo.pinnedFetches.collect { (deckId, sha256) ->
                rehostBlob(deckId, sha256).onFailure {
                    Log.e(TAG, "rehostBlob: $deckId/$sha256 failed — ${it.message}", it)
                }
            }
        }
    }

    private val cache = mutableMapOf<String, Deck>()
    private val cacheLock = Mutex()

    /**
     * deckId → subscription, or null until first loaded from the homeserver. Mirrors
     * `DiscoveryRepositoryImpl`'s follow-set cache: null and empty mean different things, so
     * "you follow nothing" is never confused with "we haven't looked yet".
     */
    private var subscriptions: MutableMap<String, SubscriptionDto>? = null
    private val subscriptionLock = Mutex()

    /**
     * One write lock per deck, guarding the read-manifest → write-chunk → write-manifest sequence.
     *
     * Deliberately not [cacheLock]: that one is held only for map access, and holding it across
     * network I/O would serialize `getLocal` app-wide — which is on the study hot path.
     *
     * `kotlinx` [Mutex] is **not reentrant**, so the lock is taken at exactly one level: the public
     * entry points. Anything named `…Locked` assumes the caller already holds it.
     */
    private val deckWriteLocks = mutableMapOf<String, Mutex>()
    private val deckWriteLocksGuard = Mutex()

    /**
     * (deckId, sha256) pairs already attempted this session, successful or not.
     *
     * Success has to be recorded or a card that stays on screen re-copies its blob on every
     * render — idempotent PUTs, so silent waste rather than corruption, which is worse because
     * nobody would notice. Failure is recorded too: retrying a dangling origin on every render
     * is the same waste. A fresh session, or the deferred sweep, tries again.
     */
    private val rehostAttempted = mutableSetOf<Pair<String, String>>()
    private val rehostLock = Mutex()

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override suspend fun getLocal(id: String): Deck? = cacheLock.withLock { cache[id] }

    // A runSuspendCatching block rather than mapCatching: mapCatching is inline, so its lambda
    // inherits the suspend context and catches the cancellation that cacheLock.withLock can throw.
    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> =
        runSuspendCatching {
            val json = pubky.get(PubkyPaths.manifest(authorPubky, deckId)).getOrThrow()
            val deck = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
            cacheLock.withLock { cache[deck.id] = deck }
            deck
        }

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> =
        publish(deck, cards, onProgress = {})

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> = runSuspendCatching {
        val author = session.requireSession().identity.pubky

        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        withDeckWrite(deck.id) { publishLocked(deck, cards, author, onProgress) }
    }

    /** [publish]'s body. **The caller must hold [Deck.id]'s write lock.** */
    private suspend fun publishLocked(
        deck: Deck,
        cards: List<Card>,
        author: String,
        onProgress: (PublishProgress) -> Unit,
    ): Deck {
        cards.forEach {
            require(!it.front.isEmpty && !it.back.isEmpty) {
                "Card ${it.id} has an empty side"
            }
        }

        val batches = CardChunking.chunk(cards)
        val chunkMeta = CardChunking.metaFor(batches, deck.updatedAt)
        val manifestUrl = PubkyPaths.manifest(author, deck.id)

        // A deck that shrank leaves chunk records past the new tail, and a tag dropped in the
        // editor leaves a tag record behind. Read what the deck was before writing, since the
        // cache entry is replaced below.
        val previous = getLocal(deck.id)
        val previousChunkCount = previous?.chunks?.size ?: 0

        val manifestDeck = deck.copy(cardCount = cards.size, chunks = chunkMeta)

        // Claim the deck *before* uploading its cards. Previously the manifest was written last,
        // so a failure partway through left orphaned chunk records under a deck root with no
        // manifest: listByAuthor could not see the deck, so the user had no way to reach it and
        // delete it. The marker manifest makes an interrupted publish visible and deletable.
        val marker = manifestDeck.copy(incomplete = true)
        pubky.putWithSessionRetry(
            manifestUrl,
            loopkyJson.encodeToString(marker.toDto()),
            session,
            revalidator,
        ).getOrThrow()

        onProgress(
            PublishProgress(0, batches.size, 0, cards.size),
        )

        // Written through CardRepository rather than encoded here: it is the one place that knows
        // the chunk record's shape, and routing through it leaves the card cache warm so opening
        // the deck straight after publishing doesn't re-download what we just uploaded.
        //
        // Chunk PUTs are idempotent overwrites, so a re-run after a failure simply rewrites them.
        // Chunks complete out of order, so the counter is shared state across the writers and
        // needs the lock — an unguarded `written++` would drop increments and report a progress
        // bar that never reaches the end.
        val progressLock = Mutex()
        var written = 0
        batches.withIndex().toList().mapConcurrently { (n, batch) ->
            cardRepo.writeChunk(deck.id, n, batch).getOrThrow()
            val soFar = progressLock.withLock { ++written }
            onProgress(
                PublishProgress(
                    chunksWritten = soFar,
                    totalChunks = batches.size,
                    cardsWritten = (soFar * CHUNK_SIZE).coerceAtMost(cards.size),
                    totalCards = cards.size,
                ),
            )
        }

        // Unreachable from the manifest once it shrinks, but left behind they would be served to
        // anyone listing the deck's `cards/` directory directly. An empty write deletes the record
        // and drops the cards it held from the cache in one step.
        (batches.size until previousChunkCount).toList().mapConcurrently { n ->
            cardRepo.writeChunk(deck.id, n, emptyList())
                .onFailure { Log.e(TAG, "publish: stale chunk $n not removed — ${it.message}", it) }
        }

        // Every chunk is up: clear the marker. A reader that arrives between the two manifest
        // writes sees `incomplete`, which is honest — the deck is mid-publish.
        val manifestBody = loopkyJson.encodeToString(manifestDeck.toDto())
        pubky.putWithSessionRetry(manifestUrl, manifestBody, session, revalidator).getOrThrow()
        onProgress(
            PublishProgress(batches.size, batches.size, cards.size, cards.size, done = true),
        )

        syncTags(previous?.tags.orEmpty(), manifestDeck)

        cacheLock.withLock { cache[manifestDeck.id] = manifestDeck }
        _changes.tryEmit(Unit)
        return manifestDeck
    }

    /**
     * Bring the deck's tag records in line with [deck]'s tag list so Nexus indexes them
     * network-wide, and add the `loopky-deck` marker that puts the deck in the global list —
     * without it the deck is only reachable by people who already follow the author (#40).
     *
     * Called from **every** manifest write that can carry a tag change, not just the first publish
     * (#47). Tag records are separate records, so a manifest write alone changes nothing an indexer
     * sees: a label dropped in the editor would stay indexed forever, and one added after the
     * initial publish would never appear.
     *
     * [previousTags] is what the manifest carried before this write — anything it has that [deck]
     * no longer does gets its record removed. Reserved labels are Loopky's own index, never
     * user-authored, so they are excluded from both ends of the diff and only the deck marker is
     * (idempotently) re-asserted.
     *
     * Best-effort throughout: discoverability is a bonus on top of a save, not a precondition, so
     * a failed tag write must not fail the write that triggered it.
     */
    private suspend fun syncTags(previousTags: List<Tag>, deck: Deck) {
        tagRepo.putReservedTag(deck.pubkyUri, ReservedTags.DECK).onFailure {
            Log.e(TAG, "syncTags: ${ReservedTags.DECK.value} write failed — ${it.message}", it)
        }

        val current = deck.tags.filterNot { ReservedTags.isReserved(it) }
        for (tag in current) {
            tagRepo.putTag(deck.pubkyUri, tag).onFailure {
                Log.e(TAG, "syncTags: tag '${tag.value}' write failed — ${it.message}", it)
            }
        }

        val dropped = previousTags.filterNot { ReservedTags.isReserved(it) } - current.toSet()
        for (tag in dropped) {
            tagRepo.removeTag(deck.pubkyUri, tag).onFailure {
                Log.e(TAG, "syncTags: tag '${tag.value}' removal failed — ${it.message}", it)
            }
        }
    }

    /**
     * Write the deck's metadata, keeping whatever chunk table is current rather than the caller's.
     *
     * A ViewModel holds the `Deck` it loaded when the editor opened; anything that rewrote a chunk
     * in the meantime — a card edit on another screen, a media re-host — is invisible to it, and
     * writing its `chunks`/`cardCount` back would orphan those chunks.
     */
    override suspend fun updateMetadata(deck: Deck): Result<Deck> = runSuspendCatching {
        requireOwnedDeck(deck.id)
        withDeckWrite(deck.id) {
            // Read inside the lock and before the patch: patchDeckLocked replaces the cache entry,
            // so afterwards there is nothing left to diff the tag records against.
            val previousTags = getLocal(deck.id)?.tags.orEmpty()
            val updated = patchDeckLocked(deck.id) { current ->
                deck.copy(chunks = current.chunks, cardCount = current.cardCount)
            }
            syncTags(previousTags, updated)
            updated
        }
    }

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> = runSuspendCatching {
        require(!card.front.isEmpty && !card.back.isEmpty) { "Card ${card.id} has an empty side" }
        requireOwnedDeck(deckId)

        withDeckWrite(deckId) {
            val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }

            // An existing card is rewritten in place; a new one appends to the last chunk with room.
            val existing = locateChunk(deck, card.id)
            val targetChunk = existing ?: CardChunking.appendTarget(deck.chunks)
            val current = cardRepo.readChunk(deck, targetChunk).getOrDefault(emptyList())

            val ord = current.firstOrNull { it.id == card.id }?.ord
                ?: ((current.maxOfOrNull { it.ord } ?: -ORD_STRIDE) + ORD_STRIDE)
            val updated = current.filterNot { it.id == card.id } + card.copy(ord = ord)

            writeChunkAndManifestLocked(deck, targetChunk, updated.inStudyOrder())
        }
    }

    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> = runSuspendCatching {
        requireOwnedDeck(deckId)

        withDeckWrite(deckId) {
            val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
            val chunk = locateChunk(deck, cardId) ?: return@withDeckWrite deck
            val remaining = cardRepo.readChunk(deck, chunk).getOrDefault(emptyList())
                .filterNot { it.id == cardId }

            cardRepo.evict(deckId, cardId)
            writeChunkAndManifestLocked(deck, chunk, remaining)
        }
    }

    override suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck> =
        runSuspendCatching {
            requireOwnedDeck(deckId)

            withDeckWrite(deckId) {
                val deck = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
                val from = locateChunk(deck, cardId) ?: return@withDeckWrite deck
                val sourceCards = cardRepo.readChunk(deck, from).getOrThrow().inStudyOrder()
                val card = sourceCards.firstOrNull { it.id == cardId } ?: return@withDeckWrite deck
                val remaining = sourceCards.filterNot { it.id == cardId }

                val target = CardChunking.positionAt(deck.chunks, toIndex, excluding = from)
                if (target.chunk == from) {
                    val reordered = remaining.toMutableList().apply {
                        add(target.offset.coerceIn(0, size), card)
                    }
                    writeChunkAndManifestLocked(deck, from, CardChunking.renumber(reordered, from))
                } else {
                    val landing = cardRepo.readChunk(deck, target.chunk).getOrThrow()
                        .inStudyOrder()
                        .toMutableList()
                        .apply { add(target.offset.coerceIn(0, size), card) }
                    // Landing chunk first. Between the two writes the card is in both chunks,
                    // which reads as one card because membership is keyed by id — whereas the
                    // other order would leave it in neither, and a failure there loses it.
                    writeChunksAndManifestLocked(
                        deck,
                        listOf(
                            target.chunk to CardChunking.renumber(landing, target.chunk),
                            from to remaining,
                        ),
                    )
                }
            }
        }

    override suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit> =
        runSuspendCatching {
            val key = deckId to sha256
            if (!rehostLock.withLock { rehostAttempted.add(key) }) return@runSuspendCatching

            // Cache reads only. requireOwnedDeck would fetch the manifest, and a blob that is not
            // already on screen is the sweep's job, not this path's.
            val deck = getLocal(deckId) ?: return@runSuspendCatching
            if (deck.authorPubky != session.current()?.identity?.pubky) return@runSuspendCatching

            val cover = deck.coverImageRef?.takeIf { it.isRehostable() && it.sha256 == sha256 }
            val cards = cardRepo.listByDeck(deckId).filter { it.pinnedRef(sha256) != null }
            val sample = cover ?: cards.firstNotNullOfOrNull { it.pinnedRef(sha256) }
                ?: return@runSuspendCatching

            // One copy, however many cards reference it — the blob is content-addressed, so the
            // digest is unchanged and every ref carrying that sha stays valid.
            val rehosted = mediaRepo.rehost(deckId, sample).getOrThrow()
            if (rehosted.uri != null) return@runSuspendCatching

            withDeckWrite(deckId) {
                val current = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
                writeRehostedCards(current, cards, sha256, rehosted)
                if (cover != null) {
                    patchDeckLocked(deckId, emitChange = false) {
                        it.copy(coverImageRef = it.coverImageRef?.relocatedTo(rehosted, sha256))
                    }
                }
            }
            Log.d(TAG, "rehostBlob: $deckId/$sha256 across ${cards.size} card(s)")
        }

    override suspend fun decksPendingRehost(): List<Deck> = listOwned()
        .filter { it.source?.kind == DeckSource.Kind.Clone && !it.mediaRehosted }

    override suspend fun rehostPendingMedia(
        deckId: String,
        maxChunks: Int,
    ): Result<RehostOutcome> = runSuspendCatching {
        val deck = requireOwnedDeck(deckId)
        if (deck.mediaRehosted) {
            RehostOutcome(0, 0, 0, 0, complete = true)
        } else {
            sweeper.sweep(deck, maxChunks)
        }
    }

    /** [DeckMediaSweeper]'s window onto this class, keeping the write lock owned here. */
    private val writeAccess = object : DeckWriteAccess {
        override suspend fun localDeck(deckId: String): Deck? = getLocal(deckId)

        override suspend fun <T> inWriteLock(deckId: String, block: suspend () -> T): T =
            withDeckWrite(deckId, block)

        override suspend fun patchLocked(deckId: String, patch: (Deck) -> Deck): Deck =
            patchDeckLocked(deckId, emitChange = false, patch = patch)
    }

    private val sweeper = DeckMediaSweeper(cardRepo, mediaRepo, writeAccess)

    /** Rewrite [cards]' refs to the re-hosted blob, one chunk write per chunk they live in. */
    private suspend fun writeRehostedCards(
        deck: Deck,
        cards: List<Card>,
        sha256: String,
        rehosted: MediaRef,
    ) {
        val byChunk = cards.groupBy { cardRepo.chunkOf(deck.id, it.id) ?: locateChunk(deck, it.id) }
        for ((chunk, inChunk) in byChunk) {
            if (chunk == null) {
                Log.w(TAG, "rehostBlob: ${inChunk.size} card(s) not in any chunk of ${deck.id}")
                continue
            }
            val ids = inChunk.mapTo(mutableSetOf()) { it.id }
            val stored = cardRepo.readChunk(deck, chunk).getOrDefault(emptyList())
            val updated = stored.map { card ->
                if (card.id in ids) card.relocatedTo(rehosted, sha256) else card
            }
            // touchDeck = false: re-hosting rewrites refs to blobs the deck already had, so
            // nothing user-visible changed. Bumping updated_at would tell every follower the
            // author published changes, and emitting `changes` would reload the library.
            writeChunkAndManifestLocked(deck, chunk, updated, touchDeck = false)
        }
    }

    /**
     * The chunk holding [cardId], or null if the deck doesn't contain it.
     *
     * Uses the mapping the card cache recorded when it read the deck. Only when that is cold does
     * it fall back to walking chunks, and it stops at the first hit rather than reading them all —
     * a scan of a 20k-card deck is 200 requests, which would undo the point of chunking.
     */
    private suspend fun locateChunk(deck: Deck, cardId: String): Int? {
        cardRepo.chunkOf(deck.id, cardId)?.let { return it }
        for (meta in deck.chunks) {
            val cards = cardRepo.readChunk(deck, meta.n).getOrDefault(emptyList())
            if (cards.any { it.id == cardId }) return meta.n
        }
        return null
    }

    /** Run [block] holding [deckId]'s write lock, so its manifest read-modify-write is atomic. */
    private suspend fun <T> withDeckWrite(deckId: String, block: suspend () -> T): T {
        val lock = deckWriteLocksGuard.withLock { deckWriteLocks.getOrPut(deckId) { Mutex() } }
        return lock.withLock { block() }
    }

    /**
     * Read-modify-write the manifest. **The caller must hold [deckId]'s write lock.**
     *
     * The deck is re-read from the cache rather than taken from a snapshot the caller captured:
     * every manifest write serializes the *whole* record, so patching a `Deck` fetched before a
     * concurrent write would silently restore its chunk table. A dropped chunk entry orphans the
     * chunk record — the cards in it stay on the homeserver but vanish from the deck.
     *
     * [emitChange] is false for writes with nothing user-visible in them (media re-hosting), so a
     * sweep does not trigger a library reload per chunk.
     */
    private suspend fun patchDeckLocked(
        deckId: String,
        emitChange: Boolean = true,
        patch: (Deck) -> Deck,
    ): Deck {
        val current = requireNotNull(getLocal(deckId)) { "Deck $deckId is not loaded" }
        val updated = patch(current)
        val body = loopkyJson.encodeToString(updated.toDto())
        pubky.putWithSessionRetry(
            PubkyPaths.manifest(updated.authorPubky, updated.id),
            body,
            session,
            revalidator,
        ).getOrThrow()

        cacheLock.withLock { cache[updated.id] = updated }
        if (emitChange) _changes.tryEmit(Unit)
        return updated
    }

    /**
     * Write one chunk and the manifest entry that describes it, as a pair. Deliberately a single
     * function: the old layout let a card record and its manifest entry be written independently,
     * which is why a single-card edit could leave the manifest stale forever.
     *
     * The chunk is written first — a chunk the manifest doesn't yet describe is invisible, whereas
     * a manifest pointing at a chunk that was never written is a broken deck.
     *
     * **The caller must hold [Deck.id]'s write lock.** [touchDeck] is false for a write that
     * changes no content — re-hosting media rewrites refs to blobs the deck already had, and
     * bumping `updated_at` would light up every follower's "the author published changes" badge.
     */
    private suspend fun writeChunkAndManifestLocked(
        deck: Deck,
        chunk: Int,
        cards: List<Card>,
        touchDeck: Boolean = true,
    ): Deck = writeChunksAndManifestLocked(deck, listOf(chunk to cards), touchDeck)

    /**
     * [writeChunkAndManifestLocked] for a write that spans more than one chunk — a card moved
     * across a chunk boundary. The chunks are written in the order given, then described by a
     * **single** manifest patch: two patches would leave a window where the manifest counts the
     * card twice, and would cost a second full-record write of the whole chunk table.
     *
     * **The caller must hold [Deck.id]'s write lock.**
     */
    private suspend fun writeChunksAndManifestLocked(
        deck: Deck,
        updates: List<Pair<Int, List<Card>>>,
        touchDeck: Boolean = true,
    ): Deck {
        updates.forEach { (chunk, cards) -> cardRepo.writeChunk(deck.id, chunk, cards).getOrThrow() }

        val now = epochMillis()
        return patchDeckLocked(deck.id, emitChange = touchDeck) { current ->
            val chunks = updates.fold(current.chunks) { acc, (chunk, cards) ->
                CardChunking.withChunk(acc, chunk, cards.size, now)
            }
            current.copy(
                chunks = chunks,
                cardCount = CardChunking.cardCount(chunks),
                updatedAt = if (touchDeck) now else current.updatedAt,
            )
        }
    }

    /** The deck, confirmed to exist and to belong to the signed-in user. */
    private suspend fun requireOwnedDeck(deckId: String): Deck {
        val author = session.requireSession().identity.pubky
        val deck = getLocal(deckId) ?: fetchRemote(author, deckId).getOrThrow()
        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        return deck
    }

    override suspend fun delete(deckId: String): Result<Unit> = runSuspendCatching {
        // Guarded like every other write since decks you don't own became reachable (#33). Without
        // it, deleting a followed deck built its sweep paths from *your* pubky and quietly ranged
        // over your own namespace looking for a deck that was never there.
        val cached = requireOwnedDeck(deckId)
        withDeckWrite(deckId) { deleteLocked(cached) }
    }

    /** [delete]'s body. **The caller must hold the deck's write lock.** */
    private suspend fun deleteLocked(cached: Deck) {
        val deckId = cached.id
        val author = cached.authorPubky

        // Sweep everything under the deck root (cards, manifest, media blobs, SRS records)
        // so nothing orphans on the homeserver. The listing itself is the fallback source of
        // paths when the cache is cold.
        val deckRoot = "${PubkyPaths.deckRoot(author, deckId)}/"
        val listedPaths = listAllPaths(deckRoot)
        val fallbackPaths = buildList {
            cached.chunks.forEach { add(PubkyPaths.cardChunk(author, deckId, it.n)) }
            add(PubkyPaths.manifest(author, deckId))
        }
        val all = (listedPaths + fallbackPaths).distinct()

        // Manifest last, and on its own: a half-deleted deck without a manifest disappears from
        // listings instead of resurfacing as corrupt, so it must not be racing the rest.
        val (manifests, contents) = all.partition { it.endsWith("/manifest.json") }
        contents.mapConcurrently { path ->
            pubky.deleteWithSessionRetry(path, session, revalidator).getOrThrow()
        }
        for (path in manifests) {
            pubky.deleteWithSessionRetry(path, session, revalidator).getOrThrow()
        }

        // Remove the tag records pointing at the deleted deck (best-effort). The loopky-deck
        // marker goes too, or global browse keeps offering a deck that no longer resolves.
        tagRepo.removeReservedTag(cached.pubkyUri, ReservedTags.DECK).onFailure {
            Log.e(TAG, "delete: ${ReservedTags.DECK.value} removal failed — ${it.message}", it)
        }
        for (tag in cached.tags.filterNot { ReservedTags.isReserved(it) }) {
            tagRepo.removeTag(cached.pubkyUri, tag).onFailure {
                Log.e(TAG, "delete: tag '${tag.value}' removal failed — ${it.message}", it)
            }
        }

        cacheLock.withLock { cache.remove(deckId) }
        _changes.tryEmit(Unit)
    }

    override suspend fun listOwned(): List<Deck> {
        val author = session.current()?.identity?.pubky ?: return emptyList()
        val owned = listByAuthor(author)

        // The self-heal. There is no single app-start hook — session restore is spread across
        // ViewModels — but this runs whenever Home or the library loads, and the job is unique
        // work with KEEP, so asking again costs nothing. Recovers a dropped inline signal or a
        // sweep the system cancelled.
        if (owned.any { it.source?.kind == DeckSource.Kind.Clone && !it.mediaRehosted }) {
            backgroundTasks.scheduleMediaRehost()
        }
        return owned
    }

    /**
     * Throws when the homeserver could not be reached. Swallowing that into an empty list
     * would make an offline device indistinguishable from an account with no decks — and
     * since Pubky is the only source of truth, that reads to the user as "my decks are gone".
     * A genuinely absent path (nothing published yet) is still an empty list.
     */
    override suspend fun listByAuthor(authorPubky: String): List<Deck> {
        val listJson = pubky.list(PubkyPaths.decksList(authorPubky))
            .getOrElse { if (it.isNotFound()) return emptyList() else throw it }
        val deckIds = parseDeckIdsFromList(listJson)
        // Concurrent: this was one manifest GET per deck, serially, so a library of ten decks
        // paid ten round trips end to end before anything could render.
        val results = deckIds.mapConcurrently { deckId ->
            deckId to fetchRemote(authorPubky, deckId)
        }
        val decks = mutableListOf<Deck>()
        var firstFailure: Throwable? = null
        for ((deckId, result) in results) {
            result
                .onSuccess { decks.add(it) }
                .onFailure { err ->
                    Log.e(TAG, "listByAuthor: manifest fetch failed for $deckId — ${err.message}", err)
                    if (firstFailure == null) firstFailure = err
                }
        }
        // One unreadable deck shouldn't hide the rest, but if the listing had decks and none
        // of them could be read, that is a connectivity failure — not an empty library.
        if (decks.isEmpty() && firstFailure != null) throw requireNotNull(firstFailure)
        return decks
    }

    override suspend fun sync(deckId: String): Result<Deck> = runSuspendCatching {
        val remote = fetchRemote(authorOf(deckId), deckId).getOrThrow()

        // fetchByDeck re-reads only the chunks whose `updated_at` moved, and rebuilds the deck's
        // cache entry from what it read. Cards dropped remotely simply aren't in any chunk any
        // more, so they fall out of the cache without a separate reconciliation pass — the old
        // index-diff-then-delete loop issued homeserver DELETEs for them, which meant a stale
        // local cache could delete a card that was still live for its author.
        cardRepo.fetchByDeck(remote).getOrThrow()
        remote
    }

    /**
     * Whose homeserver [deckId] lives on.
     *
     * This used to be the signed-in user, unconditionally, which meant [sync] read
     * `pubky://me/…` for a deck belonging to someone else and always failed — silently, because its
     * only caller ([com.github.jvsena42.loopky.data.repository.SrsRepository.dueForDeck]) logs the
     * failure and falls back to the local cache. Following a deck is worthless without this (#33).
     *
     * The cache knows for any deck that has been opened this session. Falling back to the session
     * keeps a cold-cache sync of your own deck working, which is the only case that ever worked.
     */
    private suspend fun authorOf(deckId: String): String =
        getLocal(deckId)?.authorPubky
            ?: loadSubscriptions()[deckId]?.author_pubky
            ?: session.requireSession().identity.pubky

    // ── Following someone else's deck (#33) ──────────────────────────────

    override suspend fun followDeck(deck: Deck): Result<Unit> = runSuspendCatching {
        val owner = session.requireSession().identity.pubky
        val record = SubscriptionDto(
            deck_uri = deck.pubkyUri.value,
            author_pubky = deck.authorPubky,
            deck_id = deck.id,
            followed_at = epochMillis(),
            // Following from deck detail means you are looking at it, so it is seen by definition.
            last_seen_updated_at = deck.updatedAt,
        )
        pubky.putWithSessionRetry(
            PubkyPaths.subscription(owner, deck.authorPubky, deck.id),
            loopkyJson.encodeToString(record),
            session,
            revalidator,
        ).getOrThrow()

        loadSubscriptions()
        subscriptionLock.withLock { subscriptions?.put(deck.id, record) }

        // Best-effort, like mirrorTags: the reserved label is what makes "N people follow this"
        // fall out of the indexer's tagger count, but discoverability must not fail a follow.
        tagRepo.putReservedTag(deck.pubkyUri, ReservedTags.FOLLOWED).onFailure {
            Log.e(TAG, "followDeck: ${ReservedTags.FOLLOWED.value} write failed — ${it.message}", it)
        }
        _changes.tryEmit(Unit)
    }

    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> =
        runSuspendCatching {
            val owner = session.requireSession().identity.pubky
            pubky.deleteWithSessionRetry(
                PubkyPaths.subscription(owner, authorPubky, deckId),
                session,
                revalidator,
            ).getOrThrow()
            subscriptionLock.withLock { subscriptions?.remove(deckId) }

            // The subscription and its label go; the SRS state does not. Re-following must not
            // reset your progress, and review state was never the author's data to begin with
            // (Architecture.md §8.3).
            val uri = PubkyUri(PubkyPaths.manifest(authorPubky, deckId))
            tagRepo.removeReservedTag(uri, ReservedTags.FOLLOWED).onFailure {
                Log.e(TAG, "unfollowDeck: ${ReservedTags.FOLLOWED.value} removal failed — ${it.message}", it)
            }
            _changes.tryEmit(Unit)
        }

    override suspend fun isFollowingDeck(deckId: String): Boolean =
        loadSubscriptions().containsKey(deckId)

    override suspend fun listFollowed(): List<Deck> {
        val subs = loadSubscriptions().values.toList()
        if (subs.isEmpty()) return emptyList()

        val results = subs.mapConcurrently { sub ->
            sub to fetchRemote(sub.author_pubky, sub.deck_id)
        }
        val decks = mutableListOf<Deck>()
        var firstFailure: Throwable? = null
        for ((sub, result) in results) {
            result
                .onSuccess { decks.add(it) }
                .onFailure { err ->
                    Log.e(TAG, "listFollowed: ${sub.deck_id} unreadable — ${err.message}", err)
                    // A deck its author deleted is gone, not a failure — the subscription simply
                    // outlived it. Anything else may be transient, so it still counts as a failure.
                    if (firstFailure == null && !err.isNotFound()) firstFailure = err
                }
        }
        // One unreachable deck must not hide the rest, but a set of subscriptions where nothing at
        // all could be read is a connectivity failure, not an empty library — same rule as
        // listByAuthor, and for the same reason.
        if (decks.isEmpty() && firstFailure != null) throw requireNotNull(firstFailure)
        return decks
    }

    override suspend fun hasUpdate(deckId: String): Boolean {
        val sub = loadSubscriptions()[deckId] ?: return false
        val deck = getLocal(deckId) ?: return false
        return deck.updatedAt > sub.last_seen_updated_at
    }

    override suspend fun markSeen(deck: Deck) {
        val sub = loadSubscriptions()[deck.id] ?: return
        if (sub.last_seen_updated_at >= deck.updatedAt) return

        val owner = session.current()?.identity?.pubky ?: return
        val updated = sub.copy(last_seen_updated_at = deck.updatedAt)
        pubky.putWithSessionRetry(
            PubkyPaths.subscription(owner, sub.author_pubky, sub.deck_id),
            loopkyJson.encodeToString(updated),
            session,
            revalidator,
        ).onFailure {
            // Cosmetic: the worst case is the "updated" dot showing one extra time.
            Log.e(TAG, "markSeen: ${deck.id} not recorded — ${it.message}", it)
            return
        }
        subscriptionLock.withLock { subscriptions?.put(deck.id, updated) }
        _changes.tryEmit(Unit)
    }

    override suspend fun clone(source: Deck): Result<Deck> = runSuspendCatching {
        val me = session.requireSession().identity.pubky
        // A fetch, not a cache read: for a deck you don't own, nothing has ever loaded its cards.
        val sourceCards = cardRepo.fetchByDeck(source).getOrThrow()
        val now = epochMillis()
        val newId = generateId()

        val cards = sourceCards.inStudyOrder().map { card ->
            card.copy(
                // A fresh id per card, so grading the clone never moves the original's review
                // state and vice versa — they are keyed by (author, deck, card).
                id = generateId(),
                deckId = newId,
                updatedAt = now,
                front = card.front.absolutizedTo(source),
                back = card.back.absolutizedTo(source),
            )
        }

        val deck = source.copy(
            id = newId,
            authorPubky = me,
            createdAt = now,
            updatedAt = now,
            coverImageRef = source.coverImageRef?.absolutizedTo(source.authorPubky, source.id),
            cardCount = cards.size,
            // publish() writes the real chunk table; the source's is about the source's records.
            chunks = emptyList(),
            incomplete = false,
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = source.pubkyUri.value,
                importedAt = now,
            ),
        )

        val published = publish(deck, cards).getOrThrow()

        // On the *source* manifest, so credit for the copy accrues to the original author rather
        // than to the fork. Best-effort, like every other reserved-tag write.
        tagRepo.putReservedTag(source.pubkyUri, ReservedTags.CLONED).onFailure {
            Log.e(TAG, "clone: ${ReservedTags.CLONED.value} write failed — ${it.message}", it)
        }

        // You own a copy now, so tracking the author's edits on theirs is noise. Best-effort: a
        // failed unfollow leaves you with both, which is untidy rather than broken.
        if (isFollowingDeck(source.id)) {
            unfollowDeck(source.authorPubky, source.id).onFailure {
                Log.e(TAG, "clone: unfollowing ${source.id} failed — ${it.message}", it)
            }
        }

        // A fresh clone's media is entirely pinned to the source author. Scheduled from the repo
        // rather than a ViewModel so it is not a screen's responsibility to remember, and so it is
        // testable in commonTest.
        backgroundTasks.scheduleMediaRehost()

        Log.d(TAG, "clone: ${source.id} -> $newId (${cards.size} cards)")
        published
    }

    /**
     * A card side whose media refs point at [source]'s blobs rather than at paths under a deck the
     * blobs were never uploaded to.
     */
    private fun CardSide.absolutizedTo(source: Deck): CardSide = copy(
        imageRef = imageRef?.absolutizedTo(source.authorPubky, source.id),
        audioRef = audioRef?.absolutizedTo(source.authorPubky, source.id),
    )

    /** The subscription set for this session, read from the homeserver on first use. */
    private suspend fun loadSubscriptions(): Map<String, SubscriptionDto> {
        subscriptionLock.withLock { subscriptions }?.let { return it.toMap() }
        val owner = session.current()?.identity?.pubky ?: return emptyMap()

        val loaded = mutableMapOf<String, SubscriptionDto>()
        for (path in listAllPaths(PubkyPaths.subscriptionsRoot(owner))) {
            val json = pubky.get(path).getOrElse {
                Log.e(TAG, "loadSubscriptions: $path unreadable — ${it.message}", it)
                continue
            }
            runCatching { loopkyJson.decodeFromString<SubscriptionDto>(json) }
                .onSuccess { loaded[it.deck_id] = it }
                .onFailure { Log.e(TAG, "loadSubscriptions: $path is not a subscription", it) }
        }
        subscriptionLock.withLock { subscriptions = loaded }
        return loaded.toMap()
    }

    /**
     * Every path under [prefix], following the cursor until the homeserver stops returning new
     * entries.
     *
     * `list()` has always accepted `cursor`/`limit`, and no call site used them — so anything past
     * the server's default page was simply invisible. That is survivable for a deck listing and
     * not survivable for [delete], which relies on this sweep to avoid orphaning records.
     */
    private suspend fun listAllPaths(prefix: String): List<String> {
        val seen = linkedSetOf<String>()
        var cursor: String? = null
        var more = true
        while (more) {
            val payload = pubky.list(prefix, cursor = cursor, limit = LIST_PAGE_SIZE).getOrNull()
            val page = payload?.let(::parsePubkyUrlsFromList).orEmpty()
            // `seen.addAll` returning false means the page added nothing new: the server is
            // repeating itself, so stop rather than loop forever against a homeserver that
            // ignores the cursor. A short page means we reached the end.
            val addedSomething = page.isNotEmpty() && seen.addAll(page)
            more = addedSomething && page.size >= LIST_PAGE_SIZE.toInt()
            cursor = page.lastOrNull()
        }
        return seen.toList()
    }

    /** The FFI `list` payload is a JSON array of `pubky://…` URL strings. */
    private fun parsePubkyUrlsFromList(payload: String): List<String> =
        runCatching { loopkyJson.decodeFromString<List<String>>(payload) }
            .getOrDefault(emptyList())
            .filter { it.startsWith("pubky://") }

    private fun parseDeckIdsFromList(payload: String): List<String> {
        val marker = "/${PubkyPaths.APP_NAMESPACE}/decks/"
        val ids = linkedSetOf<String>()
        var index = 0
        while (true) {
            val hit = payload.indexOf(marker, index)
            if (hit == -1) break
            val start = hit + marker.length
            val end = payload.indexOf('/', start).let { if (it == -1) payload.length else it }
            if (end > start) ids.add(payload.substring(start, end))
            index = end
        }
        return ids.toList()
    }

    private companion object {
        const val TAG = "Loopky/DeckRepo"

        /** Room for a burst of mutations while a collector is mid-reload; oldest is dropped. */
        const val CHANGE_BUFFER = 8

        /** Entries per `list()` page. Paging behaviour on large directories is unverified (§8.4). */
        const val LIST_PAGE_SIZE: UShort = 200u
    }
}
