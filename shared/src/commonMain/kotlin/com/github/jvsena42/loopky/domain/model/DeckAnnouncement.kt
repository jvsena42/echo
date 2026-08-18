package com.github.jvsena42.loopky.domain.model

/**
 * A post Loopky offers to write to the user's pubky.app feed about a deck.
 *
 * **Announcing, not sharing.** Publishing a deck already writes it publicly to the homeserver —
 * there are no private decks in v1 (spec §11) — so this changes nothing about who *can* see the
 * deck. It only tells the people following the user that it exists. Copy that blurs the two turns
 * the Settings switch into a privacy control it is not.
 *
 * [content] is composed here rather than in the repository so the confirm prompt can preview the
 * exact text that will be written: the preview and the post are the same string by construction.
 */
data class DeckAnnouncement(
    val kind: Kind,
    val deckTitle: String,
    val deckUri: PubkyUri,
    /**
     * The original author's display name, for [Kind.Followed] and [Kind.Cloned] — a clone credits
     * whoever it forked. Omitted from the text when unresolved: a bare 52-character pubky in a
     * post body is noise, and the URI already names the account.
     */
    val authorName: String? = null,
    /** The deck's cover emoji, which opens the post in place of the generic fallback. */
    val coverEmoji: String? = null,
    /**
     * The deck's cover image as a post attachment, or null when it has none or it cannot be
     * attached — see [attachableCoverUrl].
     */
    val coverUrl: String? = null,
) {
    enum class Kind { Created, Followed, Cloned }

    /**
     * The post body, URI included.
     *
     * The title and author name are truncated because they are not always the user's own:
     * announcing a follow or a clone quotes another account's manifest, and pubky-app-specs
     * rejects a post over `post_short_content_max_length` (2,000 characters). A post that fails
     * validation is written and then never indexed, which is the one failure mode with no visible
     * symptom.
     */
    val content: String
        get() {
            val by = authorName?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { " by ${it.ellipsized(MAX_AUTHOR_LENGTH)}" }
                .orEmpty()
            val title = deckTitle.trim().ellipsized(MAX_TITLE_LENGTH)
            val icon = coverEmoji?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ICON
            val headline = when (kind) {
                Kind.Created -> "$icon I published a new deck on Loopky: $title"
                Kind.Followed -> "$icon Now following the Loopky deck $title$by"
                Kind.Cloned -> "$icon Cloned the Loopky deck $title$by into my library"
            }
            return "$headline\n\n${deckUri.value}"
        }

    companion object {
        /** Everything an announcement says about a deck comes off the deck itself. */
        fun of(deck: Deck, kind: Kind, authorName: String? = null): DeckAnnouncement =
            DeckAnnouncement(
                kind = kind,
                deckTitle = deck.title,
                deckUri = deck.pubkyUri,
                authorName = authorName,
                coverEmoji = deck.coverEmoji,
                coverUrl = deck.attachableCoverUrl(),
            )

        private const val DEFAULT_ICON = "📚"
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_AUTHOR_LENGTH = 40
    }
}

/**
 * The deck's cover image as a URL a post may attach, or null when there is nothing attachable.
 *
 * A cover comes in three shapes and only two of them are already addressable: a remote web image
 * carries its own `url`, a clone's ref is pinned to the origin blob through `uri`, and an
 * own-homeserver blob has only a deck-relative `media/{sha}.{ext}` path that has to be made
 * absolute here. Built literally rather than through `PubkyPaths` for the same reason
 * [Deck.pubkyUri] is: domain models must not depend on the data layer (Architecture §4.1).
 *
 * Held to the pubky-app-specs attachment rules — an allowed protocol, at most
 * [MAX_ATTACHMENT_URL_LENGTH] characters — because a post that breaks them is rejected by the
 * indexer wholesale. A cover that does not fit is dropped; the announcement still goes out.
 */
private fun Deck.attachableCoverUrl(): String? {
    val ref = coverImageRef ?: return null
    val url = ref.url
        ?: ref.uri
        ?: ref.path.takeIf { it.isNotEmpty() }
            ?.let { "pubky://$authorPubky/pub/loopky/decks/$id/$it" }
        ?: return null
    val allowed = ATTACHMENT_PROTOCOLS.any { url.startsWith("$it://") }
    return url.takeIf { allowed && it.length <= MAX_ATTACHMENT_URL_LENGTH }
}

/** `post_allowed_attachment_protocols` in pubky-app-specs. */
private val ATTACHMENT_PROTOCOLS = listOf("pubky", "https", "http")

/** `post_attachment_url_max_length` in pubky-app-specs. */
private const val MAX_ATTACHMENT_URL_LENGTH = 200

private const val ELLIPSIS = "…"

private fun String.ellipsized(max: Int): String =
    if (length <= max) this else take(max - ELLIPSIS.length).trimEnd() + ELLIPSIS
