package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of a pubky.app tag record (`/pub/pubky.app/tags/{tagId}`), matching the ecosystem-wide
 * `PubkyAppTag` shape so Loopky's deck tags are indexed by Nexus and interoperate with other apps.
 * The record id is content-derived (see [PubkyClient.createTagId]); the indexer validates it.
 */
@Serializable
internal data class TagDto(
    val uri: String,
    val label: String,
    val created_at: Long,
)
