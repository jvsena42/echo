package com.github.jvsena42.echo.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of a pubky.app follow record (`/pub/pubky.app/follows/{followee}`). The follow relationship
 * is carried by the record's *existence*; the payload only timestamps when it was created, matching
 * the wider Pubky ecosystem's `PubkyAppFollow` shape so follows interoperate with other apps.
 */
@Serializable
internal data class FollowDto(
    val created_at: Long,
)
