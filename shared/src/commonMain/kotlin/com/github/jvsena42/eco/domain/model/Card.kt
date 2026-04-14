package com.github.jvsena42.eco.domain.model

data class Card(
    val id: String,
    val deckId: String,
    val updatedAt: Long,
    val front: CardSide,
    val back: CardSide,
)

data class CardSide(
    val text: String? = null,
    val imageRef: MediaRef.Image? = null,
    val audioRef: MediaRef.Audio? = null,
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && imageRef == null && audioRef == null
}
