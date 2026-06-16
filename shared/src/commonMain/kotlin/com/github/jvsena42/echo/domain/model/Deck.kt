package com.github.jvsena42.echo.domain.model

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
    val lastStudiedAt: Long? = null,
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

@JvmInline
value class PubkyUri(val value: String)
