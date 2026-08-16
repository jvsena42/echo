package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ORD_STRIDE
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.inStudyOrder
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
 * [DeckRepository] backed by [PubkyClient]. Pubky is the source of truth; an in-memory map is
 * used as a per-session cache so `getLocal` and `listOwned` can return instantly after a sync.
 *
 * See `docs/Architecture.md §8.0` for the on-homeserver layout this implementation writes.
 */
@Suppress("TooManyFunctions")
class DeckRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val cardRepo: CardRepository,
    private val revalidator: SessionRevalidator,
    private val tagRepo: TagRepository,
) : DeckRepository {

    private val cache = mutableMapOf<String, Deck>()
    private val cacheLock = Mutex()

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override suspend fun getLocal(id: String): Deck? = cacheLock.withLock { cache[id] }

    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> {
        return pubky.get(PubkyPaths.manifest(authorPubky, deckId))
            .mapCatching { json ->
                val deck = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
                cacheLock.withLock { cache[deck.id] = deck }
                deck
            }
    }

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> =
        publish(deck, cards, onProgress = {})

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> = runCatching {
        val author = session.requireSession().identity.pubky

        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        cards.forEach {
            require(!it.front.isEmpty && !it.back.isEmpty) {
                "Card ${it.id} has an empty side"
            }
        }

        val batches = CardChunking.chunk(cards)
        val chunkMeta = CardChunking.metaFor(batches, deck.updatedAt)
        val manifestUrl = PubkyPaths.manifest(author, deck.id)

        // A deck that shrank leaves chunk records past the new tail. Read the old chunk count
        // before writing, since the cache entry is replaced below.
        val previousChunkCount = getLocal(deck.id)?.chunks?.size ?: 0

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

        // Mirror deck tags as tag records so Nexus indexes them network-wide, and add the
        // loopky-deck marker that puts this deck in the global list — without it the deck is only
        // reachable by people who already follow the author (#40).
        // Best-effort throughout: a failed tag write must not fail the publish.
        tagRepo.putReservedTag(manifestDeck.pubkyUri, ReservedTags.DECK).onFailure {
            Log.e(TAG, "publish: ${ReservedTags.DECK.value} write failed — ${it.message}", it)
        }
        for (tag in manifestDeck.tags.filterNot { ReservedTags.isReserved(it) }) {
            tagRepo.putTag(manifestDeck.pubkyUri, tag).onFailure {
                Log.e(TAG, "publish: tag '${tag.value}' write failed — ${it.message}", it)
            }
        }

        cacheLock.withLock { cache[manifestDeck.id] = manifestDeck }
        _changes.tryEmit(Unit)
        manifestDeck
    }

    override suspend fun updateMetadata(deck: Deck): Result<Deck> = runCatching {
        require(deck.authorPubky == session.requireSession().identity.pubky) {
            "Deck author mismatch"
        }
        val url = PubkyPaths.manifest(deck.authorPubky, deck.id)
        val body = loopkyJson.encodeToString(deck.toDto())
        pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
        cacheLock.withLock { cache[deck.id] = deck }
        _changes.tryEmit(Unit)
        deck
    }

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> = runCatching {
        require(!card.front.isEmpty && !card.back.isEmpty) { "Card ${card.id} has an empty side" }
        val deck = requireOwnedDeck(deckId)

        // An existing card is rewritten in place; a new one appends to the last chunk with room.
        val existing = locateChunk(deck, card.id)
        val targetChunk = existing ?: CardChunking.appendTarget(deck.chunks)
        val current = cardRepo.readChunk(deck, targetChunk).getOrDefault(emptyList())

        val ord = current.firstOrNull { it.id == card.id }?.ord
            ?: ((current.maxOfOrNull { it.ord } ?: -ORD_STRIDE) + ORD_STRIDE)
        val updated = current.filterNot { it.id == card.id } + card.copy(ord = ord)

        writeChunkAndManifest(deck, targetChunk, updated.inStudyOrder())
    }

    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> = runCatching {
        val deck = requireOwnedDeck(deckId)
        val chunk = locateChunk(deck, cardId) ?: return@runCatching deck
        val remaining = cardRepo.readChunk(deck, chunk).getOrDefault(emptyList())
            .filterNot { it.id == cardId }

        cardRepo.evict(deckId, cardId)
        writeChunkAndManifest(deck, chunk, remaining)
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

    /**
     * Write one chunk and the manifest entry that describes it, as a pair. Deliberately a single
     * function: the old layout let a card record and its manifest entry be written independently,
     * which is why a single-card edit could leave the manifest stale forever.
     *
     * The chunk is written first — a chunk the manifest doesn't yet describe is invisible, whereas
     * a manifest pointing at a chunk that was never written is a broken deck.
     */
    private suspend fun writeChunkAndManifest(deck: Deck, chunk: Int, cards: List<Card>): Deck {
        cardRepo.writeChunk(deck.id, chunk, cards).getOrThrow()

        val now = epochMillis()
        val chunks = CardChunking.withChunk(deck.chunks, chunk, cards.size, now)
        val updated = deck.copy(
            chunks = chunks,
            cardCount = CardChunking.cardCount(chunks),
            updatedAt = now,
        )
        val body = loopkyJson.encodeToString(updated.toDto())
        pubky.putWithSessionRetry(
            PubkyPaths.manifest(deck.authorPubky, deck.id),
            body,
            session,
            revalidator,
        ).getOrThrow()

        cacheLock.withLock { cache[updated.id] = updated }
        _changes.tryEmit(Unit)
        return updated
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

    override suspend fun delete(deckId: String): Result<Unit> = runCatching {
        val author = session.requireSession().identity.pubky
        val cached = getLocal(deckId)

        // Sweep everything under the deck root (cards, manifest, media blobs, SRS records)
        // so nothing orphans on the homeserver. The listing itself is the fallback source of
        // paths when the cache is cold.
        val deckRoot = "${PubkyPaths.deckRoot(author, deckId)}/"
        val listedPaths = listAllPaths(deckRoot)
        val fallbackPaths = buildList {
            cached?.chunks?.forEach { add(PubkyPaths.cardChunk(author, deckId, it.n)) }
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
        cached?.let { deck ->
            tagRepo.removeReservedTag(deck.pubkyUri, ReservedTags.DECK).onFailure {
                Log.e(TAG, "delete: ${ReservedTags.DECK.value} removal failed — ${it.message}", it)
            }
            for (tag in deck.tags.filterNot { ReservedTags.isReserved(it) }) {
                tagRepo.removeTag(deck.pubkyUri, tag).onFailure {
                    Log.e(TAG, "delete: tag '${tag.value}' removal failed — ${it.message}", it)
                }
            }
        }

        cacheLock.withLock { cache.remove(deckId) }
        _changes.tryEmit(Unit)
        Unit
    }

    override suspend fun listOwned(): List<Deck> {
        val author = session.current()?.identity?.pubky ?: return emptyList()
        return listByAuthor(author)
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

    override suspend fun sync(deckId: String): Result<Deck> = runCatching {
        val author = session.requireSession().identity.pubky
        val remote = fetchRemote(author, deckId).getOrThrow()

        // fetchByDeck re-reads only the chunks whose `updated_at` moved, and rebuilds the deck's
        // cache entry from what it read. Cards dropped remotely simply aren't in any chunk any
        // more, so they fall out of the cache without a separate reconciliation pass — the old
        // index-diff-then-delete loop issued homeserver DELETEs for them, which meant a stale
        // local cache could delete a card that was still live for its author.
        cardRepo.fetchByDeck(remote).getOrThrow()
        remote
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
