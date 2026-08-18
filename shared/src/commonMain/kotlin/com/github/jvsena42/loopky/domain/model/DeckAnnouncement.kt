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
    /**
     * The deck's topics, which the post carries twice over: as `#hashtags` in [content], and as
     * real tag records written on the post by
     * [com.github.jvsena42.loopky.data.repository.DiscoveryRepository.announceDeck].
     *
     * Both are needed and neither is redundant. The records are what Nexus indexes — they put the
     * label in `/v0/tags/hot` and `/v0/search/posts/by_tag/{label}`. The hashtags are what a
     * *reader* can act on: pubky.app linkifies `#tag` to its own tag search, and a bare
     * `pubky://` URI it cannot linkify at all (see [content]).
     */
    val tags: List<Tag> = emptyList(),
) {
    enum class Kind { Created, Followed, Cloned }

    /**
     * The post body: a headline, the deck's `pubky://` address, and the topics as hashtags.
     *
     * **The URI is deliberately left bare, and it will not be clickable everywhere.** pubky.app
     * renders post content as markdown, and neither of the two things that could linkify it does:
     * remark-gfm's autolink literals cover only `http(s)`, `www.` and `mailto`, and a CommonMark
     * autolink (`<pubky://…>`) survives the parse only to have its `href` blanked by
     * react-markdown's `defaultUrlTransform`, which allows `https?|ircs?|mailto|xmpp` and nothing
     * else. No public HTTPS gateway maps a `pubky://` record to a browsable page either, so there
     * is no form of this link that is both clickable *and* correct on the web. It stays the
     * canonical address: Loopky's own deep-link filter opens it, and so does any client that
     * linkifies unknown schemes. The hashtags carry the clickable half.
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
            val hashtags = tags.hashtagLine()
            return "$headline\n\n${deckUri.value}" + if (hashtags.isEmpty()) "" else "\n\n$hashtags"
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
                tags = deck.announceableTags(),
            )

        private const val DEFAULT_ICON = "📚"
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_AUTHOR_LENGTH = 40
    }
}

/**
 * The deck's topics, plus [ReservedTags.DECK] so the announcement is findable as a Loopky deck
 * network-wide — the label's whole purpose, and the reason it is not filtered out here the way a
 * hand-entered reserved label would be.
 *
 * Capped because every one of these costs a homeserver write on top of the post, and a post
 * trailing twenty hashtags reads as spam in someone else's feed.
 */
private fun Deck.announceableTags(): List<Tag> =
    (tags.filterNot { ReservedTags.isReserved(it) }.take(MAX_ANNOUNCEMENT_TOPICS) + ReservedTags.DECK)
        .distinct()

/** `#kanji #japanese #loopky-deck` — what pubky.app turns into links to its own tag search. */
private fun List<Tag>.hashtagLine(): String =
    joinToString(" ") { "#${it.value}" }

/** Topics per announcement, before [ReservedTags.DECK] is appended. */
private const val MAX_ANNOUNCEMENT_TOPICS = 5

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
