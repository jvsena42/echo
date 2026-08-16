package com.github.jvsena42.loopky.domain.model

data class Card(
    val id: String,
    val deckId: String,
    val updatedAt: Long,
    val front: CardSide,
    val back: CardSide,
    /**
     * Study order within the deck. Sparse (see [ORD_STRIDE]) so a card inserted between two
     * neighbours takes the midpoint instead of forcing every following card to be renumbered —
     * which, under the chunked layout, would mean rewriting every chunk in the deck.
     *
     * Ordering used to come from the card's position in the manifest's card index; that index is
     * gone (see `docs/Architecture.md §8.0`), so the order travels with the card record itself.
     */
    val ord: Long = 0L,
)

/** Gap left between consecutive [Card.ord] values, giving room for ~10 midpoint inserts. */
const val ORD_STRIDE = 1000L

/** Sparse [Card.ord] for the card at [index] of a freshly ordered deck. */
fun ordForIndex(index: Int): Long = index * ORD_STRIDE

/**
 * An [Card.ord] that sorts between [before] and [after]. A null bound means "the end of the deck".
 * When two neighbours are already adjacent there is no midpoint left, so this returns null and the
 * caller must renumber — rare enough to be worth the sparse-ord trade.
 */
fun ordBetween(before: Long?, after: Long?): Long? = when {
    before == null && after == null -> 0L
    before == null -> after!! - ORD_STRIDE
    after == null -> before + ORD_STRIDE
    after - before <= 1L -> null
    else -> before + (after - before) / 2
}

data class CardSide(
    val text: String? = null,
    val imageRef: MediaRef.Image? = null,
    val audioRef: MediaRef.Audio? = null,
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && imageRef == null && audioRef == null
}
