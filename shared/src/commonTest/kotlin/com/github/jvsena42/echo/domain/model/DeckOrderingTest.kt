package com.github.jvsena42.echo.domain.model

import com.github.jvsena42.echo.testing.testCard
import com.github.jvsena42.echo.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals

class DeckOrderingTest {

    private fun deckWithIndex(vararg cardIds: String) = testDeck(
        cardIndex = cardIds.mapIndexed { index, id -> CardIndexEntry(id, 1_000L + index) },
    )

    // Note: no commas in commonTest names — Kotlin/Native rejects them as illegal characters.
    @Test
    fun `orders cards by the manifest index rather than by card id`() {
        // Ids deliberately sort the opposite way to the manifest order.
        val deck = deckWithIndex("zebra", "alpha", "mango")
        val cards = listOf(testCard("alpha"), testCard("mango"), testCard("zebra"))

        assertEquals(
            listOf("zebra", "alpha", "mango"),
            cards.orderedBy(deck).map { it.id },
        )
    }

    @Test
    fun `cards missing from the index keep their relative order at the end`() {
        val deck = deckWithIndex("second", "first")
        val cards = listOf(testCard("unpublished1"), testCard("first"), testCard("unpublished2"), testCard("second"))

        assertEquals(
            listOf("second", "first", "unpublished1", "unpublished2"),
            cards.orderedBy(deck).map { it.id },
        )
    }

    @Test
    fun `an empty index leaves the list untouched`() {
        val deck = testDeck()
        val cards = listOf(testCard("b"), testCard("a"))

        assertEquals(listOf("b", "a"), cards.orderedBy(deck).map { it.id })
    }
}
