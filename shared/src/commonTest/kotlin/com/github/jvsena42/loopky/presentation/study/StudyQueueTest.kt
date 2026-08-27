package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.testing.testCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sequencing for a deck studied both ways — the pure half of the feature. */
class StudyQueueTest {

    private fun cards(n: Int) = (1..n).map { testCard("c$it") }

    private fun expand(cards: List<Card>, gap: Int) =
        expandWithReverses(cards, gap) { true }

    /** "c1", "↔c1", … — the shape of a queue at a glance. */
    private fun List<StudyPresentation>.shape() =
        map { if (it.reversed) "↔${it.card.id}" else it.card.id }

    @Test
    fun eachReverseTrailsItsOwnCardByTheGap() {
        val queue = expand(cards(8), gap = 3)

        assertEquals(
            listOf("c1", "c2", "c3", "c4", "↔c1", "c5", "↔c2", "c6", "↔c3", "c7", "↔c4", "c8", "↔c5", "↔c6", "↔c7", "↔c8"),
            queue.shape(),
        )
    }

    @Test
    fun everyCardIsAskedBothWaysExactlyOnce() {
        val queue = expand(cards(12), gap = 5)

        assertEquals(24, queue.size)
        assertEquals(cards(12).map { it.id }, queue.filterNot { it.reversed }.map { it.card.id })
        assertEquals(cards(12).map { it.id }, queue.filter { it.reversed }.map { it.card.id })
    }

    @Test
    fun aQueueShorterThanTheGapKeepsItsReversesRatherThanDroppingThem() {
        // Nothing comes due inside the loop, so all three flush at the end — in order, and still
        // behind every forward. Dropping them would silently turn the opt-in off for small decks.
        val queue = expand(cards(3), gap = 5)

        assertEquals(listOf("c1", "c2", "c3", "↔c1", "↔c2", "↔c3"), queue.shape())
    }

    @Test
    fun aCardThatCannotBeAskedBackwardsContributesNoReverse() {
        // An image-only answer with no text on the front has nothing to answer *with*.
        val oneSided = Card(
            id = "c2",
            deckId = "deck1",
            updatedAt = 0L,
            front = CardSide(text = null),
            back = CardSide(text = "back"),
        )
        val queue = expand(listOf(testCard("c1"), oneSided, testCard("c3")), gap = 1)

        assertTrue(queue.none { it.reversed && it.card.id == "c2" })
        assertEquals(listOf("↔c1", "↔c3"), queue.filter { it.reversed }.shape())
    }

    @Test
    fun aDeckThatDidNotOptInIsNotPaired() {
        // The predicate is per card, because a session started from Home spans every deck and the
        // opt-in is each author's own.
        val mixed = listOf(testCard("a1", deckId = "on"), testCard("b1", deckId = "off"))
        val queue = expandWithReverses(mixed, gap = 1) { it.deckId == "on" }

        assertEquals(listOf("a1", "b1", "↔a1"), queue.shape())
    }

    @Test
    fun anEmptyQueueStaysEmpty() {
        assertEquals(emptyList(), expand(emptyList(), gap = 5).shape())
    }

    @Test
    fun theGapNeverReachesPastTheDailyGoal() {
        // A gap wider than the cards someone means to study today would put every reverse past
        // where they stop, leaving them drilling one direction only.
        assertEquals(5, reverseGapFor(20))
        assertEquals(5, reverseGapFor(5))
        assertEquals(3, reverseGapFor(3))
        assertEquals(1, reverseGapFor(1))
    }

    @Test
    fun theGapIsNeverZeroHoweverSmallTheGoal() {
        // A gap of 0 would emit a card's reverse ahead of the card itself.
        assertEquals(1, reverseGapFor(0))
        assertEquals(1, reverseGapFor(-3))
    }
}
