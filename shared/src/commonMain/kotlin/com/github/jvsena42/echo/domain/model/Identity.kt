package com.github.jvsena42.echo.domain.model

import kotlin.jvm.JvmInline

data class PubkyIdentity(
    val pubky: String,
    val displayName: String?,
    val avatarUrl: String?,
    val bio: String?,
)

/**
 * Letter shown when there is no avatar picture. Display name first, pubky as the fallback — the
 * same rule everywhere an avatar placeholder is drawn, so one person never shows two letters.
 */
val PubkyIdentity.avatarInitial: Char
    get() = (displayName?.trim()?.firstOrNull() ?: pubky.firstOrNull())?.uppercaseChar() ?: '?'

data class Session(
    val identity: PubkyIdentity,
    val sessionSecret: String,
    val capabilities: List<Capability>,
    val homeserver: String,
)

@JvmInline
value class Capability(val value: String)
