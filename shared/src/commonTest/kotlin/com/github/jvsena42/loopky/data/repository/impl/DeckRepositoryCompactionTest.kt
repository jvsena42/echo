package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DeckRepository.compactDeck` — reclaiming the holes card deletes leave (#51).
 *
 * A delete shrinks its chunk's `count` and stops there, because closing the hole in place would
 * resequence every card after it and rewrite every following record. The density is reclaimed here
 * instead: off the critical path, a pair of chunks at a time.
 */
class DeckRepositoryCompactionTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val backgroundTasks = FakeBackgroundTasks()
    private val repo =
        deckRepository(pubky, session, cardRepo, revalidator, backgroundTasks = backgroundTasks)

    private val deckRoot = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"

    /** A deck of [count] cards, then every card whose index satisfies [deleting] removed. */
    private suspend fun churnedDeck(count: Int, deleting: (Int) -> Boolean) {
        repo.publish(testDeck(id = "deck1"), (1..count).map { testCard("c$it") }).getOrThrow()
        (1..count).filter { deleting(it) }.forEach { repo.deleteCard("deck1", "c$it").getOrThrow() }
    }

    /** Card ids of chunk [n] as stored, in record order. */
    private fun chunkOrder(n: Int): List<String> =
        loopkyJson.decodeFromString<CardChunkDto>(pubky.store.getValue("$deckRoot/cards/$n.json"))
            .cards.map { it.id }

    @Test
    fun compactionFoldsSparseChunksTogetherAndDropsTheEmptiedRecords() = runTest {
        // 300 cards over 3 chunks, then 70% of each deleted: 30 + 30 + 30 across three records.
        churnedDeck(300) { it % 10 >= 3 }
        assertEquals(listOf(30, 30, 30), repo.getLocal("deck1")!!.chunks.map { it.count })

        val outcome = repo.compactDeck("deck1").getOrThrow()

        assertEquals(expected = 2, actual = outcome.merges)
        assertEquals(expected = 3, actual = outcome.chunksBefore)
        assertEquals(expected = 1, actual = outcome.chunksAfter)
        assertTrue(outcome.complete)
        assertEquals(listOf(90), repo.getLocal("deck1")!!.chunks.map { it.count })
        assertFalse("$deckRoot/cards/1.json" in pubky.store)
        assertFalse("$deckRoot/cards/2.json" in pubky.store)
    }

    @Test
    fun compactionStopsAtTheRecordCountTheCardsActuallyNeed() = runTest {
        // 120 cards cannot fit in one 100-card record, so two is as dense as this deck gets.
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }

        val outcome = repo.compactDeck("deck1").getOrThrow()

        assertTrue(outcome.complete)
        assertEquals(listOf(80, 40), repo.getLocal("deck1")!!.chunks.map { it.count })
    }

    @Test
    fun compactionKeepsEveryCardAndItsStudyOrder() = runTest {
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }
        val before = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow().map { it.id }

        repo.compactDeck("deck1").getOrThrow()

        val after = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow().map { it.id }
        assertContentEquals(before, after)
        assertEquals(expected = 120, actual = repo.getLocal("deck1")!!.cardCount)
    }

    @Test
    fun compactionStopsWhenNoTwoNeighboursFitInOneRecord() = runTest {
        // 60 per chunk: no pair fits, so there is nothing to reclaim.
        churnedDeck(300) { it % 5 == 0 && it % 10 != 0 }
        val chunksBefore = repo.getLocal("deck1")!!.chunks
        pubky.puts.clear()

        val outcome = repo.compactDeck("deck1").getOrThrow()

        assertEquals(expected = 0, actual = outcome.merges)
        assertTrue(outcome.complete)
        assertEquals(chunksBefore, repo.getLocal("deck1")!!.chunks)
        assertTrue(pubky.puts.isEmpty(), "a no-op compaction wrote ${pubky.puts.map { it.first }}")
    }

    @Test
    fun compactionWritesTheLandingRecordBeforeItDropsTheSource() = runTest {
        churnedDeck(200) { it % 4 != 0 }
        pubky.puts.clear()
        pubky.deletes.clear()

        repo.compactDeck("deck1").getOrThrow()

        // Landing record, then the manifest, and only then the source. Any other order leaves a
        // window where the manifest points at a chunk that 404s, or where cards are in neither.
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
        assertEquals(listOf("$deckRoot/cards/1.json"), pubky.deletes)
    }

    @Test
    fun compactionDoesNotTellFollowersTheAuthorPublished() = runTest {
        churnedDeck(200) { it % 4 != 0 }
        val before = repo.getLocal("deck1")!!

        val after = repo.compactDeck("deck1").getOrThrow().let { repo.getLocal("deck1")!! }

        // Nothing user-visible changed, so `updated_at` must not move — but the chunk stamps must,
        // or a follower never re-fetches the pair that was folded.
        assertEquals(before.updatedAt, after.updatedAt)
        assertTrue(after.chunks.single().updatedAt >= before.chunks.first().updatedAt)
    }

    @Test
    fun compactionRespectsItsMergeBudgetAndResumesOnTheNextPass() = runTest {
        churnedDeck(500) { it % 5 != 0 }

        val first = repo.compactDeck("deck1", maxMerges = 1).getOrThrow()
        assertEquals(expected = 1, actual = first.merges)
        assertFalse(first.complete, "a budget-capped pass claimed it was done")
        assertEquals(expected = 4, actual = repo.getLocal("deck1")!!.chunks.size)

        val second = repo.compactDeck("deck1").getOrThrow()
        assertTrue(second.complete)
        assertEquals(expected = 1, actual = repo.getLocal("deck1")!!.chunks.size)
        assertEquals(expected = 100, actual = repo.getLocal("deck1")!!.cardCount)
    }

    @Test
    fun anUnreadableChunkStopsThePassWithoutLosingCards() = runTest {
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }
        pubky.store.remove("$deckRoot/cards/1.json")

        val outcome = repo.compactDeck("deck1").getOrThrow()

        assertEquals(expected = 0, actual = outcome.merges)
        assertFalse(outcome.complete, "a pass that could not read a chunk claimed it was done")
        // The table is untouched, so the next pass sees exactly what this one did.
        assertEquals(listOf(40, 40, 40), repo.getLocal("deck1")!!.chunks.map { it.count })
    }

    @Test
    fun compactionRepairsAnEntryCountingCardsItsRecordNoLongerHas() = runTest {
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }
        // A chunk record deleted out from under the manifest — the manifest still counts its 40.
        pubky.store.remove("$deckRoot/cards/1.json")
        pubky.store["$deckRoot/cards/1.json"] = loopkyJson.encodeToString(
            CardChunkDto(deck_id = "deck1", chunk = 1, cards = emptyList()),
        )

        repo.compactDeck("deck1").getOrThrow()

        val deck = repo.getLocal("deck1")!!
        assertEquals(expected = 80, actual = deck.cardCount)
        assertEquals(listOf(80), deck.chunks.map { it.count })
    }

    @Test
    fun compactionRejectsADeckYouDoNotOwn() = runTest {
        val foreign = testDeck(id = "foreign", authorPubky = "friendpk")
        pubky.store["pubky://friendpk/pub/loopky/decks/foreign/manifest.json"] =
            loopkyJson.encodeToString(foreign.toDto())

        assertTrue(repo.compactDeck("foreign").isFailure)
    }

    @Test
    fun deletingACardAsksForACompactionPassOnceTheTableIsSparse() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..CHUNK_SIZE + 1).map { testCard("c$it") })
            .getOrThrow()
        assertEquals(expected = 0, actual = backgroundTasks.compactionsScheduled)

        // Chunk 1 is down to its last card, which chunk 0 has no room for…
        repo.deleteCard("deck1", "c${CHUNK_SIZE + 1}").getOrThrow()
        assertEquals(expected = 0, actual = backgroundTasks.compactionsScheduled)

        // …until chunk 0 gives one up.
        repo.publish(testDeck(id = "deck1"), (1..CHUNK_SIZE + 1).map { testCard("c$it") })
            .getOrThrow()
        repo.deleteCard("deck1", "c1").getOrThrow()
        assertEquals(expected = 1, actual = backgroundTasks.compactionsScheduled)
    }

    @Test
    fun deletingACardNeverCompactsInline() = runTest {
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }

        // A bulk delete must not pay for a merge per card, nor rewrite followers' cached records
        // in the middle of the user's edit — it only asks for the pass.
        assertEquals(listOf(40, 40, 40), repo.getLocal("deck1")!!.chunks.map { it.count })
        assertTrue(backgroundTasks.compactionsScheduled > 0)
    }

    @Test
    fun listingDecksAsksForAPassForTheOnesWithHoles() = runTest {
        churnedDeck(300) { it % 5 != 0 && it % 5 != 1 }
        val scheduledByDeletes = backgroundTasks.compactionsScheduled

        assertEquals(listOf("deck1"), repo.decksPendingCompaction().map { it.id })
        assertTrue(backgroundTasks.compactionsScheduled > scheduledByDeletes)
    }

    /**
     * Counts `[10, 90, 10]`, so the first pair fills a record exactly and the tail cannot follow:
     * chunk 1 is dropped and chunk 2 survives, leaving a hole in the numbering.
     */
    private suspend fun deckWithAGapAfterCompacting() {
        churnedDeck(300) { if (it in 101..200) it % 10 == 0 else it % 10 != 0 }
        assertEquals(listOf(10, 90, 10), repo.getLocal("deck1")!!.chunks.map { it.count })
        repo.compactDeck("deck1").getOrThrow()
    }

    @Test
    fun aMergeLeavesAHoleInTheNumberingAndTheDeckStillReadsInOrder() = runTest {
        deckWithAGapAfterCompacting()

        // Nothing downstream assumes the table is contiguous — chunk numbers are read in `n`
        // order, so folding 1 away leaves 0 and 2 sorting exactly where they did.
        assertEquals(listOf(0, 2), repo.getLocal("deck1")!!.chunks.map { it.n })

        val order = cardRepo.fetchByDeck(repo.getLocal("deck1")!!).getOrThrow()
            .map { it.id.removePrefix("c").toInt() }
        assertContentEquals(order.sorted(), order, "compaction reordered the deck")
        assertEquals(expected = 110, actual = order.size)
    }

    @Test
    fun aRepublishSweepsTheRecordsCompactionLeftHighNumbered() = runTest {
        deckWithAGapAfterCompacting()
        pubky.deletes.clear()

        repo.publish(testDeck(id = "deck1"), (1..5).map { testCard("k$it") }).getOrThrow()

        // The republish writes chunk 0 and has to clear the old chunk 2 *by its number*. Counting
        // the previous chunks instead would clear `1 until 2` — a record that no longer exists —
        // and leave chunk 2 orphaned under the deck root, invisible to the manifest but still
        // served to anyone listing `cards/`.
        assertTrue(
            "$deckRoot/cards/2.json" in pubky.deletes,
            "the republish left ${pubky.store.keys.filter { "cards/" in it }}",
        )
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5"), chunkOrder(0))
    }
}
