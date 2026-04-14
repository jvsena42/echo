package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.CardDto
import com.github.jvsena42.eco.data.pubky.ManifestDto
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.PubkyPaths
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.pubky.requireSession
import com.github.jvsena42.eco.data.pubky.toDomain
import com.github.jvsena42.eco.data.pubky.toDto
import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.Deck
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
        val s = session.requireSession()
        val author = s.identity.pubky
        val secret = s.sessionSecret

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
            pubky.putWithSession(url, body, secret).getOrThrow()
        }

        val manifestDeck = deck.copy(
            cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
        )
        val manifestUrl = PubkyPaths.manifest(author, deck.id)
        val manifestBody = echoJson.encodeToString(manifestDeck.toDto())
        pubky.putWithSession(manifestUrl, manifestBody, secret).getOrThrow()

        cacheLock.withLock { cache[manifestDeck.id] = manifestDeck }
        manifestDeck
    }

    override suspend fun updateMetadata(deck: Deck): Result<Deck> = runCatching {
        val s = session.requireSession()
        require(deck.authorPubky == s.identity.pubky) {
            "Deck author mismatch"
        }
        val url = PubkyPaths.manifest(deck.authorPubky, deck.id)
        val body = echoJson.encodeToString(deck.toDto())
        pubky.putWithSession(url, body, s.sessionSecret).getOrThrow()
        cacheLock.withLock { cache[deck.id] = deck }
        deck
    }

    override suspend fun delete(deckId: String): Result<Unit> = runCatching {
        val s = session.requireSession()
        val author = s.identity.pubky
        val cached = getLocal(deckId)
        val cardIds = cached?.cardIndex?.map { it.id } ?: emptyList()
        for (cardId in cardIds) {
            pubky.deleteWithSession(
                PubkyPaths.card(author, deckId, cardId),
                s.sessionSecret,
            ).getOrThrow()
        }
        pubky.deleteWithSession(
            PubkyPaths.manifest(author, deckId),
            s.sessionSecret,
        ).getOrThrow()
        cacheLock.withLock { cache.remove(deckId) }
        Unit
    }

    override suspend fun listOwned(): List<Deck> {
        val s = session.current() ?: return emptyList()
        val author = s.identity.pubky
        val listJson = pubky.list(PubkyPaths.decksList(author)).getOrNull() ?: return emptyList()
        val deckIds = parseDeckIdsFromList(listJson)
        val decks = mutableListOf<Deck>()
        for (deckId in deckIds) {
            fetchRemote(author, deckId).getOrNull()?.let { decks.add(it) }
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
}
