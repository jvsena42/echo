package com.github.jvsena42.loopky.domain.model

import kotlin.jvm.JvmInline

data class Deck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val description: String?,
    val coverEmoji: String? = null,
    val coverImageRef: MediaRef.Image?,
    val tags: List<Tag>,
    val createdAt: Long,
    val updatedAt: Long,
    val cardIndex: List<CardIndexEntry>,
    /** Opt-in: play TTS audio of the card back during study. */
    val listenEnabled: Boolean = true,
    /** Opt-in: pronunciation practice (speech recognition) on the card back during study. */
    val speakEnabled: Boolean = true,
) {
    val cardCount: Int get() = cardIndex.size
    val pubkyUri: PubkyUri get() = PubkyUri("pubky://$authorPubky/pub/echo/decks/$id/manifest.json")
}

data class CardIndexEntry(
    val id: String,
    val updatedAt: Long,
)

/**
 * Cards in the order the deck's manifest declares. Card records come back keyed by id, so
 * without this the display order is whatever the random card ids happen to sort to. Cards
 * missing from [Deck.cardIndex] (added locally, not yet published) keep their relative order
 * at the end.
 */
fun List<Card>.orderedBy(deck: Deck): List<Card> {
    val position = deck.cardIndex.withIndex().associate { (index, entry) -> entry.id to index }
    return sortedBy { position[it.id] ?: Int.MAX_VALUE }
}

@JvmInline
value class PubkyUri(val value: String)
