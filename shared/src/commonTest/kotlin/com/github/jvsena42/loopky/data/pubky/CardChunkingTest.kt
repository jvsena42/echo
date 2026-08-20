package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ChunkMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chunk-table maths behind compaction (#51).
 *
 * Card deletes shrink a chunk's `count` and leave the gap, so a deck that churned heavily ends up
 * spread over more records than its card count warrants. These are the pure functions that decide
 * whether that has happened and which pair to fold.
 */
class CardChunkingTest {

    private fun table(vararg counts: Int): List<ChunkMeta> =
        counts.mapIndexed { n, count -> ChunkMeta(n = n, count = count, updatedAt = 1L) }

    @Test
    fun mergeTargetPicksTheFirstPairThatFitsInOneRecord() {
        assertEquals(1 to 2, CardChunking.mergeTarget(table(100, 40, 30, 90)))
    }

    @Test
    fun mergeTargetIgnoresAPairThatWouldOverflow() {
        assertNull(CardChunking.mergeTarget(table(60, 60, 60)))
    }

    @Test
    fun mergeTargetFillsAChunkExactly() {
        // Merging up to a full CHUNK_SIZE is deliberate: a chunk left just short cannot pair with
        // anything but an almost-empty neighbour, which is what stops a delete/add cycle thrashing.
        assertEquals(0 to 1, CardChunking.mergeTarget(table(40, 60)))
    }

    @Test
    fun mergeTargetPairsNeighboursInTheSortedTableNotConsecutiveNumbers() {
        // What the table looks like after chunk 1 was already folded away: nothing sorts between
        // 0 and 2, so folding them keeps study order.
        val gapped = listOf(
            ChunkMeta(n = 0, count = 30, updatedAt = 1L),
            ChunkMeta(n = 2, count = 30, updatedAt = 1L),
        )
        assertEquals(0 to 2, CardChunking.mergeTarget(gapped))
    }

    @Test
    fun aSingleChunkAndAnEmptyDeckHaveNothingToMerge() {
        assertNull(CardChunking.mergeTarget(table(1)))
        assertNull(CardChunking.mergeTarget(emptyList()))
    }

    @Test
    fun isSparseIsTrueExactlyWhenThereIsAMergeToMake() {
        assertTrue(CardChunking.isSparse(table(10, 10)))
        assertFalse(CardChunking.isSparse(table(100, 100)))
        assertFalse(CardChunking.isSparse(table(50)))
    }

    @Test
    fun densityIsCardsHeldPerSlotAllocated() {
        assertEquals(1f, CardChunking.density(table(CHUNK_SIZE, CHUNK_SIZE)))
        assertEquals(0.5f, CardChunking.density(table(CHUNK_SIZE / 2)))
        // The case the issue describes: import 20k, delete 15k, and the deck still reads like 20k.
        assertEquals(0.25f, CardChunking.density(List(200) { ChunkMeta(it, 25, 1L) }))
    }

    @Test
    fun anEmptyDeckWastesNothing() {
        assertEquals(1f, CardChunking.density(emptyList()))
    }
}
