package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of a pubky.app post record (`/pub/pubky.app/posts/{postId}`), matching `PubkyAppPost` so
 * Nexus indexes it and every other Pubky app can render it. The id is timestamp-derived — see
 * [PostIds].
 *
 * A deck announcement carries the deck's `pubky://…/manifest.json` in both the body text and the
 * embed, plus the deck's cover image as its single attachment when it has one.
 */
@Serializable
internal data class PostDto(
    val content: String,
    val kind: String = PostKinds.SHORT,
    val parent: String? = null,
    val embed: PostEmbedDto? = null,
    val attachments: List<String>? = null,
)

@Serializable
internal data class PostEmbedDto(
    val kind: String,
    val uri: String,
)

/**
 * `PubkyAppPostKind`, serialized lowercase.
 *
 * [SHORT] is deliberately never used for an *embed* kind: Nexus reads a short embed as a repost
 * and looks up the embedded URI as a post that must already exist
 * (`PostRelationships::from_homeserver`). A deck manifest is not a post, so such an announcement
 * would park in the retry queue as a missing dependency and never be indexed. [LINK] embeds carry
 * no relationship and index straight through.
 */
internal object PostKinds {
    const val SHORT = "short"
    const val LINK = "link"
    const val IMAGE = "image"

    /** `post_short_content_max_length` — the cap every kind but `long` is held to. */
    const val MAX_CONTENT_LENGTH = 2_000
}
