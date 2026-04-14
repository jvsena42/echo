package com.github.jvsena42.eco.domain.model

import kotlin.jvm.JvmInline

data class PubkyIdentity(
    val pubky: String,
    val displayName: String?,
    val avatarUrl: String?,
    val bio: String?,
)

data class Session(
    val identity: PubkyIdentity,
    val sessionSecret: String,
    val capabilities: List<Capability>,
    val homeserver: String,
)

@JvmInline
value class Capability(val value: String)
