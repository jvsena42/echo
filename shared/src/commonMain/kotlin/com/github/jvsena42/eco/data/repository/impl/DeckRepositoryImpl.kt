package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.CardDto
import com.github.jvsena42.eco.data.pubky.ManifestDto
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.PubkyPaths
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.pubky.SessionRevalidator
import com.github.jvsena42.eco.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.eco.data.pubky.putWithSessionRetry
import com.github.jvsena42.eco.data.pubky.requireSession
import com.github.jvsena42.eco.data.pubky.toDomain
import com.github.jvsena42.eco.data.pubky.toDto
import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.TagRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.util.Log
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

    override suspend fun getLocal(id: String): Deck? = cacheLock.withLock { cache[id] }

    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> {
        return pubky.get(PubkyPaths.manifest(authorPubky, deckId))
            .mapCatching { json ->
                val deck = echoJson.decodeFromString<ManifestDto>(json).toDomain()
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
            val body = echoJson.encodeToString(card.toDto())
            pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
        }

        val manifestDeck = deck.copy(
            cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
        )
        val manifestUrl = PubkyPaths.manifest(author, deck.id)
        val manifestBody = echoJson.encodeToString(manifestDeck.toDto())
        pubky.putWithSessionRetry(manifestUrl, manifestBody, session, revalidator).getOrThrow()

        // Mirror deck tags as pubky.app tag records so Nexus indexes them network-wide.
        // Best-effort: a failed tag write must not fail the publish.
        for (tag in manifestDeck.tags) {
            tagRepo.putTag(manifestDeck.pubkyUri, tag).onFailure {
                Log.e(TAG, "publish: tag '${tag.value}' write failed — ${it.message}", it)
            }
        }

        cacheLock.withLock { cache[manifestDeck.id] = manifestDeck }
        manifestDeck
    }

    override suspend fun updateMetadata(deck: Deck): Result<Deck> = runCatching {
        require(deck.authorPubky == session.requireSession().identity.pubky) {
            "Deck author mismatch"
        }
        val url = PubkyPaths.manifest(deck.authorPubky, deck.id)
        val body = echoJson.encodeToString(deck.toDto())
        pubky.putWithSessionRetry(url, body, session, revalidator).getOrThrow()
        cacheLock.withLock { cache[deck.id] = deck }
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
        Unit
    }

    override suspend fun listOwned(): List<Deck> {
        val author = session.current()?.identity?.pubky ?: return emptyList()
        return listByAuthor(author)
    }

    override suspend fun listByAuthor(authorPubky: String): List<Deck> {
        val listJson = pubky.list(PubkyPaths.decksList(authorPubky)).getOrNull() ?: return emptyList()
        val deckIds = parseDeckIdsFromList(listJson)
        val decks = mutableListOf<Deck>()
        for (deckId in deckIds) {
            fetchRemote(authorPubky, deckId).getOrNull()?.let { decks.add(it) }
        }
        return decks
    }

    override suspend fun sync(deckId: String): Result<Deck> = runCatching {
        val s = session.requireSession()
        val author = s.identity.pubky
        val remote = fetchRemote(author, deckId).getOrThrow()

        val localCards = cardRepo.listByDeck(deckId).associateBy { it.id }
        val remoteIds = remote.cardIndex.map { it.id }.toSet()

        for (entry in remote.cardIndex) {
            val local = localCards[entry.id]
            if (local == null || local.updatedAt < entry.updatedAt) {
                pubky.get(PubkyPaths.card(author, deckId, entry.id))
                    .mapCatching { echoJson.decodeFromString<CardDto>(it).toDomain() }
                    .onSuccess { card -> cardRepo.upsert(card) }
                    .getOrThrow()
            }
        }

        for (localId in localCards.keys) {
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
        runCatching { echoJson.decodeFromString<List<String>>(payload) }
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
        const val TAG = "Echo/DeckRepo"
    }
}
