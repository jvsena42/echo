package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import com.github.jvsena42.loopky.testing.testDeckWithCards
import kotlinx.coroutines.Dispatchers
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
    private val repo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)

    @Test
    fun writeChunkStoresTheBatchAsOneRecord() = runTest {
        val cards = listOf(testCard("c1", ord = 0), testCard("c2", ord = 1000))

        repo.writeChunk("deck1", chunk = 0, cards = cards).getOrThrow()

        val url = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1/cards/0.json"
        val dto = loopkyJson.decodeFromString<CardChunkDto>(pubky.store.getValue(url))
        assertEquals("deck1", dto.deck_id)
        assertEquals(0, dto.chunk)
        assertEquals(listOf("c1", "c2"), dto.cards.map { it.id })
        // One request for two cards — the whole point of the layout.
        assertEquals(expected = 1, actual = pubky.puts.size)
    }

    @Test
    fun writeChunkWithNoCardsDeletesTheRecord() = runTest {
        repo.writeChunk("deck1", 0, listOf(testCard("c1"))).getOrThrow()

        repo.writeChunk("deck1", 0, emptyList()).getOrThrow()

        val url = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1/cards/0.json"
        assertTrue(url !in pubky.store)
    }

    @Test
    fun listByDeckReturnsCachedCardsInStudyOrderNotIdOrder() = runTest {
        repo.writeChunk(
            "deck1",
            0,
            listOf(testCard("zebra", ord = 0), testCard("apple", ord = 1000)),
        ).getOrThrow()

        assertEquals(listOf("zebra", "apple"), repo.listByDeck("deck1").map { it.id })
    }

    @Test
    fun getFallsBackToTheHomeserverOnColdCache() = runTest {
        val deck = seedRemoteDeck(TEST_PUBKY, testCard("c1", ord = 0))
        val coldRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)

        assertEquals("c1", coldRepo.get(deck.id, "c1")?.id)
    }

    @Test
    fun getReturnsNullForMissingCard() = runTest {
        assertNull(repo.get("deck1", "ghost"))
    }

    @Test
    fun fetchByDeckReadsTheCardsOfADeckYouDoNotOwn() = runTest {
        val deck = seedRemoteDeck(
            FOREIGN_PUBKY,
            testCard("c1", ord = 0),
            testCard("c2", ord = 1000),
        )

        val cards = repo.fetchByDeck(deck).getOrThrow()

        assertEquals(listOf("c1", "c2"), cards.map { it.id })
        // Browsing someone else's deck must not write anything to your own homeserver.
        assertTrue(pubky.puts.isEmpty())
        assertTrue(pubky.deletes.isEmpty())
        assertEquals(listOf("c1", "c2"), repo.listByDeck(deck.id).map { it.id })
    }

    @Test
    fun fetchByDeckReturnsCardsInOrdOrderNotIdOrder() = runTest {
        val deck = seedRemoteDeck(
            TEST_PUBKY,
            testCard("zebra", ord = 0),
            testCard("apple", ord = 1000),
        )

        assertEquals(listOf("zebra", "apple"), repo.fetchByDeck(deck).getOrThrow().map { it.id })
    }

    @Test
    fun fetchByDeckSkipsAnUnreadableChunkButKeepsTheRest() = runTest {
        val cards = (1..150).map { testCard("c$it", ord = it * 1000L) }
        val deck = seedRemoteDeck(TEST_PUBKY, *cards.toTypedArray(), chunkSize = 100)
        pubky.store.remove("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/cards/1.json")

        val read = repo.fetchByDeck(deck).getOrThrow()

        assertEquals(expected = 100, actual = read.size)
        assertEquals("c1", read.first().id)
    }

    @Test
    fun fetchByDeckFailsWhenNoChunkCanBeRead() = runTest {
        val deck = seedRemoteDeck(TEST_PUBKY, testCard("c1"))
        pubky.failGetWith = IllegalStateException("homeserver unreachable")

        // An unreachable homeserver must not be reported as a deck with no cards.
        assertTrue(repo.fetchByDeck(deck).isFailure)
    }

    @Test
    fun fetchByDeckSucceedsEmptyWhenEveryChunkIsMissing() = runTest {
        // What an import that died before writing a single chunk leaves behind. The homeserver is
        // answering — it is answering 404 — so this is a real, empty, broken deck, not an
        // unreachable one. Failing here put the deck detail screen into "this deck no longer
        // exists", and since the delete button lives on that screen, the deck could not be removed.
        val deck = seedRemoteDeck(TEST_PUBKY, testCard("c1"), testCard("c2"))
        deck.chunks.forEach {
            pubky.store.remove("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/cards/${it.n}.json")
        }

        assertEquals(emptyList(), repo.fetchByDeck(deck).getOrThrow())
    }

    @Test
    fun fetchByDeckOnADeckWithNoCardsSucceedsEmpty() = runTest {
        assertEquals(emptyList(), repo.fetchByDeck(testDeck()).getOrThrow())
    }

    @Test
    fun fetchByDeckSkipsChunksWhoseTimestampHasNotMoved() = runTest {
        val deck = seedRemoteDeck(TEST_PUBKY, testCard("c1"), testCard("c2"))
        repo.fetchByDeck(deck).getOrThrow()
        val getsAfterFirst = pubky.gets.size

        repo.fetchByDeck(deck).getOrThrow()

        // The chunk's updated_at did not move, so the second pass serves from cache. This is what
        // keeps a follower of a 20k-card deck from re-reading every chunk on every open.
        assertEquals(getsAfterFirst, pubky.gets.size)
        assertEquals(listOf("c1", "c2"), repo.listByDeck(deck.id).map { it.id })
    }

    @Test
    fun fetchByDeckRereadsOnlyTheChunkThatChanged() = runTest {
        val cards = (1..250).map { testCard("c$it", ord = it * 1000L) }
        val deck = seedRemoteDeck(TEST_PUBKY, *cards.toTypedArray(), chunkSize = 100)
        repo.fetchByDeck(deck).getOrThrow()
        assertEquals(expected = 3, actual = pubky.gets.size)
        pubky.gets.clear()

        // Owner edited one card in chunk 1; only that chunk's timestamp advances.
        val bumped = deck.copy(
            chunks = deck.chunks.map { if (it.n == 1) it.copy(updatedAt = 9_000L) else it },
        )
        repo.fetchByDeck(bumped).getOrThrow()

        assertEquals(
            listOf("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/cards/1.json"),
            pubky.gets,
        )
    }

    @Test
    fun fetchByDeckDropsCardsTheAuthorRemoved() = runTest {
        val deck = seedRemoteDeck(TEST_PUBKY, testCard("c1", ord = 0), testCard("c2", ord = 1000))
        repo.fetchByDeck(deck).getOrThrow()

        // The author republished the chunk without c2, bumping its timestamp.
        val shrunk = seedRemoteDeck(TEST_PUBKY, testCard("c1", ord = 0), updatedAt = 9_000L)

        assertEquals(listOf("c1"), repo.fetchByDeck(shrunk).getOrThrow().map { it.id })
        assertEquals(listOf("c1"), repo.listByDeck(shrunk.id).map { it.id })
    }

    @Test
    fun writeChunkFailsWhenSignedOut() = runTest {
        session.set(null)

        assertTrue(repo.writeChunk("deck1", 0, listOf(testCard("c1"))).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    /**
     * Writes chunk records straight into the fake homeserver under [author] and returns a deck
     * whose manifest describes them — i.e. a deck published by someone else that this session has
     * never read.
     */
    private fun seedRemoteDeck(
        author: String,
        vararg cards: Card,
        chunkSize: Int = 100,
        updatedAt: Long = 2_000L,
    ): Deck {
        cards.toList().chunked(chunkSize).forEachIndexed { n, batch ->
            pubky.store["pubky://$author/pub/loopky/decks/deck1/cards/$n.json"] =
                loopkyJson.encodeToString(
                    CardChunkDto(deck_id = "deck1", chunk = n, cards = batch.map { it.toDto() }),
                )
        }
        return testDeckWithCards(
            cards = cards.toList(),
            authorPubky = author,
            chunkSize = chunkSize,
            updatedAt = updatedAt,
        )
    }
}
