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
    /**
     * Denormalized so a deck tile can render "20,000 cards" from the manifest alone. Previously
     * derived from the card index, which meant downloading every card entry to draw one tile.
     */
    val cardCount: Int,
    /** Card chunks that make up this deck; membership is their union. See [ChunkMeta]. */
    val chunks: List<ChunkMeta> = emptyList(),
    /** Where this deck came from (clone, import, original). Null for decks published before §6. */
    val source: DeckSource? = null,
    /** Opt-in: play TTS audio of the card back during study. */
    val listenEnabled: Boolean = true,
    /** Opt-in: pronunciation practice (speech recognition) on the card back during study. */
    val speakEnabled: Boolean = true,
) {
    // Built literally rather than via PubkyPaths: that lives in `data/pubky`, and domain models
    // must not depend on the data layer (Architecture §4.1).
    val pubkyUri: PubkyUri get() = PubkyUri("pubky://$authorPubky/pub/loopky/decks/$id/manifest.json")
}

/**
 * One card-chunk record's metadata, as carried by the manifest. [updatedAt] is what lets a client
 * — including a follower syncing someone else's deck — re-fetch only the chunks that changed
 * rather than diffing a full card index.
 */
data class ChunkMeta(
    val n: Int,
    val count: Int,
    val updatedAt: Long,
)

/** Deck provenance. One block rather than a field per origin, so new sources don't churn the schema. */
data class DeckSource(
    val kind: Kind,
    /** `pubky://…/manifest.json` of the deck this came from, when [kind] is [Kind.Clone]. */
    val uri: String? = null,
    /** Source-specific id (an Anki deck id, a URL, …). */
    val originId: String? = null,
    val importedAt: Long? = null,
) {
    enum class Kind { Original, Clone, Import }
}

/**
 * Cards in study order. Card records come back keyed by id, so without this the display order is
 * whatever the random card ids happen to sort to. Order now travels on the card itself as
 * [Card.ord]; ties (and cards added locally before an ord is assigned) fall back to id so the
 * order is at least stable between renders.
 */
fun List<Card>.inStudyOrder(): List<Card> = sortedWith(compareBy({ it.ord }, { it.id }))

@JvmInline
value class PubkyUri(val value: String)
