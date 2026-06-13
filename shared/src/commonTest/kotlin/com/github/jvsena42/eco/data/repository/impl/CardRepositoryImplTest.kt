package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.CardDto
import com.github.jvsena42.eco.testing.CountingRevalidator
import com.github.jvsena42.eco.testing.FakePubkyClient
import com.github.jvsena42.eco.testing.TEST_PUBKY
import com.github.jvsena42.eco.testing.signedInProvider
import com.github.jvsena42.eco.testing.testCard
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun upsertFailsWhenSignedOut() = runTest {
        session.set(null)

        assertTrue(repo.upsert(testCard("c1")).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }
}
