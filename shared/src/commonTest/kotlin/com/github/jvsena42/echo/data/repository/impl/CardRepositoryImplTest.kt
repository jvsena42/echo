package com.github.jvsena42.echo.data.repository.impl

import com.github.jvsena42.echo.data.pubky.CardDto
import com.github.jvsena42.echo.data.pubky.toDto
import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.testing.CountingRevalidator
import com.github.jvsena42.echo.testing.FakePubkyClient
import com.github.jvsena42.echo.testing.TEST_PUBKY
import com.github.jvsena42.echo.testing.signedInProvider
import com.github.jvsena42.echo.testing.testCard
import com.github.jvsena42.echo.testing.testDeck
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FOREIGN_PUBKY = "friendpk"

class CardRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val repo = CardRepositoryImpl(pubky, session, revalidator)

    @Test
    fun upsertWritesTheCardRecordAndCachesIt() = runTest {
        val card = testCard("c1", deckId = "deck1", front = "hola", back = "hello")

        repo.upsert(card).getOrThrow()

        val url = "pubky://$TEST_PUBKY/pub/echo/decks/deck1/cards/c1.json"
        val dto = echoJson.decodeFromString<CardDto>(pubky.store.getValue(url))
        assertEquals("c1", dto.id)
        assertEquals("deck1", dto.deck_id)
        assertEquals("hola", dto.front.text)
        assertEquals("hello", dto.back.text)
        assertEquals(card, repo.get("deck1", "c1"))
    }

    @Test
    fun getFallsBackToTheHomeserverOnColdCache() = runTest {
        val card = testCard("c1")
        repo.upsert(card).getOrThrow()

        // A fresh repo over the same store has an empty cache.
        val coldRepo = CardRepositoryImpl(pubky, session, revalidator)

        assertEquals(card, coldRepo.get("deck1", "c1"))
        // And the fetched card is now cached and listable.
        assertEquals(listOf(card), coldRepo.listByDeck("deck1"))
    }

    @Test
    fun getReturnsNullForMissingCard() = runTest {
        assertNull(repo.get("deck1", "ghost"))
    }

    @Test
    fun listByDeckReturnsCachedCardsSortedById() = runTest {
        repo.upsert(testCard("c2")).getOrThrow()
        repo.upsert(testCard("c1")).getOrThrow()
        repo.upsert(testCard("c9", deckId = "otherdeck")).getOrThrow()

        assertEquals(listOf("c1", "c2"), repo.listByDeck("deck1").map { it.id })
    }

    @Test
    fun upsertOverwritesAnExistingCard() = runTest {
        repo.upsert(testCard("c1", front = "old")).getOrThrow()
        val updated = testCard("c1", front = "new", updatedAt = 99L)

        repo.upsert(updated).getOrThrow()

        assertEquals(updated, repo.get("deck1", "c1"))
        assertEquals(expected = 2, actual = pubky.puts.size)
    }

    @Test
    fun deleteRemovesRecordAndCacheEntry() = runTest {
        repo.upsert(testCard("c1")).getOrThrow()

        repo.delete("deck1", "c1").getOrThrow()

        val url = "pubky://$TEST_PUBKY/pub/echo/decks/deck1/cards/c1.json"
        assertTrue(url !in pubky.store)
        assertNull(repo.get("deck1", "c1"))
        assertEquals(emptyList(), repo.listByDeck("deck1"))
    }

    @Test
    fun fetchByDeckReadsTheCardsOfADeckYouDoNotOwn() = runTest {
        val deck = seedRemoteDeck(author = FOREIGN_PUBKY, "c1" to 1_000L, "c2" to 1_000L)

        val cards = repo.fetchByDeck(deck).getOrThrow()

        assertEquals(listOf("c1", "c2"), cards.map { it.id })
        // Browsing someone else's deck must not write anything to your own homeserver.
        assertTrue(pubky.puts.isEmpty())
        assertTrue(pubky.deletes.isEmpty())
        // And the fetched cards are now cached for the session.
        assertEquals(listOf("c1", "c2"), repo.listByDeck("deck1").map { it.id })
    }

    @Test
    fun fetchByDeckReturnsCardsInManifestOrderNotIdOrder() = runTest {
        val deck = seedRemoteDeck(author = TEST_PUBKY, "zebra" to 1_000L, "apple" to 1_000L)

        assertEquals(listOf("zebra", "apple"), repo.fetchByDeck(deck).getOrThrow().map { it.id })
    }

    @Test
    fun fetchByDeckKeepsALocalEditNewerThanTheManifestEntry() = runTest {
        val deck = seedRemoteDeck(author = TEST_PUBKY, "c1" to 1_000L)
        val edited = testCard("c1", front = "edited locally", updatedAt = 5_000L)
        repo.upsert(edited).getOrThrow()
        val putsBefore = pubky.puts.size

        val cards = repo.fetchByDeck(deck).getOrThrow()

        assertEquals(edited, cards.single(), "a newer local edit was overwritten by the fetch")
        assertEquals(putsBefore, pubky.puts.size, "the fetch should not write")
    }

    @Test
    fun fetchByDeckSkipsAnUnreadableCardButKeepsTheRest() = runTest {
        val deck = seedRemoteDeck(author = TEST_PUBKY, "c1" to 1_000L, "c2" to 1_000L)
        pubky.store.remove("pubky://$TEST_PUBKY/pub/echo/decks/deck1/cards/c2.json")

        assertEquals(listOf("c1"), repo.fetchByDeck(deck).getOrThrow().map { it.id })
    }

    @Test
    fun fetchByDeckFailsWhenNoCardCanBeRead() = runTest {
        val deck = seedRemoteDeck(author = TEST_PUBKY, "c1" to 1_000L)
        pubky.failGetWith = IllegalStateException("homeserver unreachable")

        // An unreachable homeserver must not be reported as a deck with no cards.
        assertTrue(repo.fetchByDeck(deck).isFailure)
    }

    @Test
    fun fetchByDeckOnADeckWithNoCardsSucceedsEmpty() = runTest {
        assertEquals(emptyList(), repo.fetchByDeck(testDeck()).getOrThrow())
    }

    /**
     * Writes card records straight into the fake homeserver under [author] and returns a deck
     * whose `cardIndex` points at them — i.e. the state of a deck published by someone else,
     * which this session has never read.
     */
    private fun seedRemoteDeck(author: String, vararg cards: Pair<String, Long>): Deck {
        cards.forEach { (id, updatedAt) ->
            val card = testCard(id, updatedAt = updatedAt)
            pubky.store["pubky://$author/pub/echo/decks/deck1/cards/$id.json"] =
                echoJson.encodeToString(card.toDto())
        }
        return testDeck(
            authorPubky = author,
            cardIndex = cards.map { (id, updatedAt) -> CardIndexEntry(id, updatedAt) },
        )
    }

    @Test
    fun upsertFailsWhenSignedOut() = runTest {
        session.set(null)

        assertTrue(repo.upsert(testCard("c1")).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }
}
