package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.testing.testCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckOrderingTest {

    // Note: no commas in commonTest names — Kotlin/Native rejects them as illegal characters.
    @Test
    fun `orders cards by ord rather than by card id`() {
        // Ids deliberately sort the opposite way to the study order.
        val cards = listOf(
            testCard("alpha", ord = 1000),
            testCard("mango", ord = 2000),
            testCard("zebra", ord = 0),
        )

        assertEquals(listOf("zebra", "alpha", "mango"), cards.inStudyOrder().map { it.id })
    }

    @Test
    fun `cards sharing an ord fall back to id so the order is stable`() {
        val cards = listOf(testCard("b", ord = 0), testCard("a", ord = 0))

        assertEquals(listOf("a", "b"), cards.inStudyOrder().map { it.id })
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList(), emptyList<Card>().inStudyOrder())
    }

    @Test
    fun `ordForIndex leaves room between consecutive cards`() {
        assertEquals(0L, ordForIndex(0))
        assertEquals(ORD_STRIDE, ordForIndex(1))
        assertTrue(ordForIndex(1) - ordForIndex(0) > 1, "no room left for an insert")
    }

    @Test
    fun `ordBetween takes the midpoint of two neighbours`() {
        assertEquals(500L, ordBetween(0L, 1000L))
    }

    @Test
    fun `ordBetween extends past either end of the deck`() {
        assertEquals(ORD_STRIDE, ordBetween(0L, null))
        assertEquals(-ORD_STRIDE, ordBetween(null, 0L))
        assertEquals(0L, ordBetween(null, null))
    }

    @Test
    fun `ordBetween reports exhaustion when neighbours are adjacent`() {
        // Caller has to renumber; silently returning a colliding ord would corrupt the order.
        assertNull(ordBetween(5L, 6L))
        assertNull(ordBetween(5L, 5L))
    }
}
