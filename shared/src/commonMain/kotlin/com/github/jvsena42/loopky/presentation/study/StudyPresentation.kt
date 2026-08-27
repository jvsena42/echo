package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.isReversible

/**
 * One card as the session shows it once: which card, and which way round.
 *
 * A deck studied both ways puts each card in the queue twice, but there is still only one card
 * record and one review state behind the pair — see [expandWithReverses].
 */
data class StudyPresentation(val card: Card, val reversed: Boolean = false)

/**
 * The default distance between a card and its reverse. A ceiling, not the value used: see
 * [reverseGapFor].
 */
internal const val REVERSE_GAP = 5

/**
 * How far behind a card its reverse trails, given the reader's new-cards-per-day goal.
 *
 * Five, unless the goal is smaller. A gap wider than the number of cards someone means to get
 * through today would put every reverse past the point they stop, so a goal of 2 would quietly
 * leave them studying one direction only — the exact opposite of what the opt-in is for.
 *
 * This reads the goal to decide **placement**, never to withhold. The queue stays uncapped and
 * every card is still served, so Architecture §8.6's rule that nothing in the queue-building path
 * may consult the daily goal — which is about *capping* — is untouched. Clamped to at least 1
 * because a gap of 0 would emit a card's reverse ahead of the card itself.
 */
internal fun reverseGapFor(newCardsPerDayGoal: Int): Int =
    REVERSE_GAP.coerceAtMost(newCardsPerDayGoal.coerceAtLeast(1))

/**
 * Interleave each card's reverse into the queue, [gap] presentations behind the card itself.
 *
 * "Shortly after, in the same session" rather than a stored date: a reverse due time would have to
 * live per direction, and review state is keyed by card id alone. Nothing here is persisted — the
 * pairing exists for as long as the session does.
 *
 * A queue shorter than [gap] has nowhere to put the reverses in-line, so they flush at the end in
 * order rather than being dropped. Cards that cannot be asked backwards contribute no reverse (see
 * [isReversible]).
 *
 * [deckWantsReverse] is asked per card rather than once, because a session started from Home spans
 * every studiable deck and the opt-in is each deck author's own.
 */
internal fun expandWithReverses(
    cards: List<Card>,
    gap: Int,
    deckWantsReverse: (Card) -> Boolean,
): List<StudyPresentation> {
    if (cards.isEmpty()) return emptyList()
    fun pairs(card: Card) = card.isReversible && deckWantsReverse(card)

    val out = mutableListOf<StudyPresentation>()
    cards.forEachIndexed { index, card ->
        out += StudyPresentation(card)
        val owed = cards.getOrNull(index - gap)
        if (owed != null && pairs(owed)) out += StudyPresentation(owed, reversed = true)
    }
    // The last `gap` cards' reverses never came due inside the loop.
    for (i in maxOf(0, cards.size - gap) until cards.size) {
        if (pairs(cards[i])) out += StudyPresentation(cards[i], reversed = true)
    }
    return out
}
