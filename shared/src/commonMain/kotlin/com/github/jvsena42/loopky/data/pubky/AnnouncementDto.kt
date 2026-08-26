package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of an announcement ledger entry (`/pub/loopky/announcements/{id}.json`) — Loopky's record
 * that it has already posted about this deck, so it never posts about it twice (#145).
 *
 * The entry's *id* carries the identity (a hash of
 * [com.github.jvsena42.loopky.domain.model.DeckAnnouncement.dedupeKey]); everything here is for a
 * human reading the record. [post_uri] is the one field code uses: it is what an already-announced
 * deck returns in place of writing a second post.
 */
@Serializable
internal data class AnnouncementDto(
    val post_uri: String,
    val deck_uri: String,
    val kind: String,
    val title: String,
    val created_at: Long,
)
