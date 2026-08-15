package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardIndexEntry
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.util.Log
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

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> = runCatching {
        val author = session.requireSession().identity.pubky

        require(deck.authorPubky == author) {
            "Deck author mismatch: expected $author, got ${deck.authorPubky}"
        }
        cards.forEach {
            require(!it.front.isEmpty && !it.back.isEmpty) {
                "Card ${it.id} has an empty side"
            }
        }

        for (card in cards) {
            val url = PubkyPaths.card(author, deck.id, card.id)
            val body = loopkyJson.encodeToString(card.toDto())
            pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
        }

        val manifestDeck = deck.copy(
            cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
        )
        val manifestUrl = PubkyPaths.manifest(author, deck.id)
        val manifestBody = loopkyJson.encodeToString(manifestDeck.toDto())
        pubky.putWithSessionRetry(manifestUrl, manifestBody, session, revalidator).getOrThrow()

        // Mirror deck tags as pubky.app tag records so Nexus indexes them network-wide.
        // Best-effort: a failed tag write must not fail the publish.
        for (tag in manifestDeck.tags) {
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

    override suspend fun delete(deckId: String): Result<Unit> = runCatching {
        val author = session.requireSession().identity.pubky
        val cached = getLocal(deckId)

        // Sweep everything under the deck root (cards, manifest, media blobs, SRS records)
        // so nothing orphans on the homeserver. The listing itself is the fallback source of
        // paths when the cache is cold.
        val deckRoot = "${PubkyPaths.deckRoot(author, deckId)}/"
        val listedPaths = pubky.list(deckRoot).getOrNull()
            ?.let(::parsePubkyUrlsFromList)
            .orEmpty()
        val fallbackPaths = buildList {
            cached?.cardIndex?.forEach { add(PubkyPaths.card(author, deckId, it.id)) }
            add(PubkyPaths.manifest(author, deckId))
        }
        // Manifest last: a half-deleted deck without a manifest disappears from listings
        // instead of resurfacing as corrupt.
        val paths = (listedPaths + fallbackPaths).distinct()
            .sortedBy { it.endsWith("/manifest.json") }
        for (path in paths) {
            pubky.deleteWithSessionRetry(path, session, revalidator).getOrThrow()
        }

        // Remove the pubky.app tag records pointing at the deleted deck (best-effort).
        cached?.let { deck ->
            for (tag in deck.tags) {
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
        val decks = mutableListOf<Deck>()
        var firstFailure: Throwable? = null
        for (deckId in deckIds) {
            fetchRemote(authorPubky, deckId)
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

        val localIds = cardRepo.listByDeck(deckId).map { it.id }
        val remoteIds = remote.cardIndex.map { it.id }.toSet()

        // fetchByDeck already skips records the cache holds at or past the index's updatedAt.
        // Doing this by hand here also called `upsert`, which PUT every card straight back to
        // the homeserver it had just been read from.
        cardRepo.fetchByDeck(remote).getOrThrow()

        for (localId in localIds) {
            if (localId !in remoteIds) {
                cardRepo.delete(deckId, localId)
            }
        }
        remote
    }

    /**
     * The FFI `list` result format is undocumented here — we parse defensively by looking for
     * `/pub/echo/decks/{deckId}/` segments. When the homeserver stabilises, replace with the
     * proper structured parse.
     */
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
    }
}
