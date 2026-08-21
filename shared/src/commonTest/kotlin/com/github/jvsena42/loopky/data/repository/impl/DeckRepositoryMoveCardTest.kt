package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DeckRepository.moveCard` — reordering without republishing (#52).
 *
 * Order lives on each card's `ord`, and each chunk owns a private slice of the ord line, so
 * moving a row costs one or two chunk writes plus the manifest whatever the deck's size. The
 * editor used to persist a reorder by republishing the deck in list order: ~201 writes and every
 * card re-uploaded to move one row.
 */
class DeckRepositoryMoveCardTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, RecordingTagRepository())

    private val deckRoot = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"

    @Test
    fun moveCardWithinAChunkRewritesOnlyThatChunk() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        repo.moveCard("deck1", "c1", toIndex = 3).getOrThrow()

        // One chunk plus the manifest, whatever the deck's size — never a republish.
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
        assertEquals(listOf("c2", "c3", "c4", "c1", "c5"), chunkOrder(0).take(5))
    }

    @Test
    fun moveCardPersistsTheOrderThroughOrdNotChunkPosition() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..5).map { testCard("c$it") }).getOrThrow()

        repo.moveCard("deck1", "c5", toIndex = 0).getOrThrow()

        // What a reader actually sorts by. Position in the record is incidental.
        val cards = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow()
        assertEquals(listOf("c5", "c1", "c2", "c3", "c4"), cards.map { it.id })
    }

    @Test
    fun moveCardAcrossChunksWritesBothPlusOneManifest() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        // c1 is in chunk 0; position 150 is in chunk 1.
        val deck = repo.moveCard("deck1", "c1", toIndex = 150).getOrThrow()

        // Landing chunk first: between the two writes the card must be in both records rather
        // than in neither, so a failure cannot lose it.
        assertEquals(
            listOf("$deckRoot/cards/1.json", "$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
        assertEquals(expected = 250, actual = deck.cardCount, "a move changed the card count")
        assertEquals(listOf(99, 101, 50), deck.chunks.map { it.count })
        assertTrue("c1" !in chunkOrder(0))
        // Global 150 = 99 cards left in chunk 0, then offset 51 of chunk 1.
        assertEquals("c1", chunkOrder(1)[51])
    }

    @Test
    fun aCardMovedAcrossChunksStillSortsWhereItLanded() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()

        repo.moveCard("deck1", "c1", toIndex = 150).getOrThrow()

        val cards = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow()
        assertEquals(expected = 250, actual = cards.size)
        assertEquals(expected = 150, actual = cards.indexOfFirst { it.id == "c1" })
    }

    @Test
    fun movingToTheEndOfADeckLandsOnTheLastPosition() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..5).map { testCard("c$it") }).getOrThrow()

        repo.moveCard("deck1", "c1", toIndex = 99).getOrThrow()

        val cards = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow()
        assertEquals(listOf("c2", "c3", "c4", "c5", "c1"), cards.map { it.id })
    }

    @Test
    fun movingACardTheDeckDoesNotHaveIsANoOp() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..5).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        repo.moveCard("deck1", "nope", toIndex = 0).getOrThrow()

        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun moveCardRejectsADeckYouDoNotOwn() = runTest {
        putRemoteManifest("friendpk", "foreign", "Someone else's")

        assertTrue(repo.moveCard("foreign", "c1", toIndex = 0).isFailure)
    }

    /** Card ids of chunk [n] as it is stored, in record order. */
    private fun chunkOrder(n: Int): List<String> =
        loopkyJson.decodeFromString<CardChunkDto>(pubky.store.getValue("$deckRoot/cards/$n.json"))
            .cards.map { it.id }

    private fun putRemoteManifest(author: String, deckId: String, title: String) {
        val dto = testDeck(id = deckId, authorPubky = author, title = title).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }
}
