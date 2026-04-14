package com.github.jvsena42.eco.domain.model

data class Card(
    val id: String,
    val deckId: String,
    val front: CardSide,
    val back: CardSide,
    val tags: List<Tag>,
    val position: Int,
)

data class CardSide(
    val text: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
)
