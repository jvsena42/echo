package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.CardDto
import com.github.jvsena42.eco.data.pubky.PubkyClient
import com.github.jvsena42.eco.data.pubky.PubkyPaths
import com.github.jvsena42.eco.data.pubky.SessionProvider
import com.github.jvsena42.eco.data.pubky.requireSession
import com.github.jvsena42.eco.data.pubky.toDomain
import com.github.jvsena42.eco.data.pubky.toDto
import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.domain.model.Card
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [CardRepository] backed by [PubkyClient]. Individual card records live at
 * `/pub/echo/decks/{deckId}/cards/{cardId}.json` — see `docs/Architecture.md §8.0`.
 *
 * [DeckRepositoryImpl] coordinates manifest updates when cards change; this class only touches
 * the card records themselves. Callers that add/remove cards must also bump the deck's manifest.
 */
class CardRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
) : CardRepository {

    private val cache = mutableMapOf<String, MutableMap<String, Card>>()
    private val cacheLock = Mutex()

    override suspend fun listByDeck(deckId: String): List<Card> = cacheLock.withLock {
        cache[deckId]?.values?.sortedBy { it.id } ?: emptyList()
    }

    override suspend fun get(deckId: String, cardId: String): Card? {
        cacheLock.withLock { cache[deckId]?.get(cardId) }?.let { return it }
        val author = session.current()?.identity?.pubky ?: return null
        return pubky.get(PubkyPaths.card(author, deckId, cardId))
            .mapCatching { echoJson.decodeFromString<CardDto>(it).toDomain() }
            .onSuccess { putInCache(it) }
            .getOrNull()
    }

    override suspend fun upsert(card: Card): Result<Unit> = runCatching {
        val s = session.requireSession()
        val url = PubkyPaths.card(s.identity.pubky, card.deckId, card.id)
        val body = echoJson.encodeToString(card.toDto())
        pubky.putWithSession(url, body, s.sessionSecret).getOrThrow()
        putInCache(card)
    }

    override suspend fun delete(deckId: String, cardId: String): Result<Unit> = runCatching {
        val s = session.requireSession()
        pubky.deleteWithSession(
            PubkyPaths.card(s.identity.pubky, deckId, cardId),
            s.sessionSecret,
        ).getOrThrow()
        cacheLock.withLock { cache[deckId]?.remove(cardId) }
        Unit
    }

    private suspend fun putInCache(card: Card) = cacheLock.withLock {
        cache.getOrPut(card.deckId) { mutableMapOf() }[card.id] = card
    }
}
