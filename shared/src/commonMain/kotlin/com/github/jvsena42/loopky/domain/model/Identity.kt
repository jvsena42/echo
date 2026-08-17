package com.github.jvsena42.loopky.domain.model

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

/**
 * [avatarUrl] as something an image loader can actually fetch, or null when there is nothing to
 * show.
 *
 * A pubky.app profile stores its picture as a `pubky://` URI pointing at a *file record* —
 * `pubky://{user}/pub/pubky.app/files/{fileId}` — not at an image. Handing that straight to Coil
 * (or `AsyncImage` on iOS) fails silently, which is why avatars never appeared anywhere even for
 * accounts that had set one. The indexer already serves the decoded blob, so the fix is a URL
 * translation rather than fetching and decoding bytes ourselves.
 *
 * [avatarUrl] itself stays the canonical `pubky://` value: it is written straight back out when
 * the profile is saved, and persisting a resolved indexer URL there would corrupt the record.
 *
 * Anything that is neither an http(s) URL nor a recognised file URI returns null, so the avatar
 * falls back to the initial instead of a blank slot.
 */
fun PubkyIdentity.avatarDisplayUrl(indexerBaseUrl: String): String? {
    val raw = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

    if (!raw.startsWith(PUBKY_SCHEME)) return null

    val path = raw.removePrefix(PUBKY_SCHEME)
    val user = path.substringBefore('/', missingDelimiterValue = "")
    val fileId = path.substringAfter(PUBKY_APP_FILES, missingDelimiterValue = "").trim('/')
    if (user.isEmpty() || fileId.isEmpty() || '/' in fileId) return null

    return "${indexerBaseUrl.trimEnd('/')}/static/files/$user/$fileId/main"
}

private const val PUBKY_SCHEME = "pubky://"
private const val PUBKY_APP_FILES = "/pub/pubky.app/files/"

data class Session(
    val identity: PubkyIdentity,
    val sessionSecret: String,
    val capabilities: List<Capability>,
    val homeserver: String,
)

@JvmInline
value class Capability(val value: String)
