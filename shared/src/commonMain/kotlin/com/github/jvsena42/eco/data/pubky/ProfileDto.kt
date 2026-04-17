package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.PubkyIdentity
import kotlinx.serialization.Serializable

@Serializable
internal data class ProfileDto(
    val name: String? = null,
    val bio: String? = null,
    val image: String? = null,
)

internal fun ProfileDto.toDomain(pubky: String) = PubkyIdentity(
    pubky = pubky,
    displayName = name,
    avatarUrl = image,
    bio = bio,
)

internal fun PubkyIdentity.toProfileDto() = ProfileDto(
    name = displayName,
    bio = bio,
    image = avatarUrl,
)
