package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of a deck subscription (`/pub/loopky/subscriptions/{authorPubky}/{deckId}.json`) — the record
 * behind "Follow deck" (#33).
 *
 * Like a pubky.app follow, the relationship is carried by the record's *existence*; the payload
 * exists so a follower can resolve the deck without re-deriving its path, and so the library can
 * tell "the author has published changes" from "you have already seen them".
 *
 * [author_pubky] is what makes a followed deck syncable at all: `DeckRepository.sync` reads the
 * manifest from the *owner's* homeserver, which it cannot find from a deck id alone.
 */
@Serializable
internal data class SubscriptionDto(
    val schema_version: Int = SCHEMA_VERSION,
    val deck_uri: String,
    val author_pubky: String,
    val deck_id: String,
    val followed_at: Long,
    /**
     * The manifest's `updated_at` when this deck was last opened. Compared against the live
     * manifest to mark a followed deck as having changed since you last looked; 0 until the first
     * open, so a deck followed and never opened reads as up to date rather than perpetually new.
     */
    val last_seen_updated_at: Long = 0L,
)
