package com.github.jvsena42.loopky.data.unsplash

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Read-only client for the Unsplash REST API. Powers the "from web" image search in the cover
 * and card-image sheets. Selected photos are saved by URL (a [com.github.jvsena42.loopky.domain.model.MediaRef.Image]
 * with `url` set) — Loopky never re-hosts the bytes.
 *
 * The access key comes from the user's own [UnsplashKeyStore] if they have set one, and otherwise
 * from [fallbackKey] (the build-time `BuildConfig.UNSPLASH_ACCESS_KEY`). The fallback is a single
 * shared Unsplash demo app — 50 requests/hour across every install — which is why the UI offers to
 * replace it. Neither key is ever rendered, logged, or put in a URL: URLs end up in [HttpError]
 * messages and logcat, so the key stays in the `Authorization` header.
 *
 * Unsplash's API guidelines are licensing terms, not suggestions: callers must credit the
 * photographer and Unsplash with links back (see [UnsplashPhoto.authorProfileUrl] and
 * [UNSPLASH_HOME_URL]) and must call [trackDownload] when a photo is used.
 */
class UnsplashClient(
    private val http: HttpFetcher,
    private val keyStore: UnsplashKeyStore,
    /** Build-time fallback, used only when the user has stored no key of their own. Never exposed. */
    private val fallbackKey: String = "",
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private suspend fun accessKey(): String = keyStore.key.first().ifBlank { fallbackKey }

    private fun authHeaders(accessKey: String): Map<String, String> = mapOf(
        "Authorization" to "Client-ID $accessKey",
        "Accept-Version" to "v1",
    )

    /**
     * Whether this build carries a fallback key — its *existence*, never its value. Settings needs
     * this to tell "web search is off" apart from "running on the shared key".
     */
    val hasFallbackKey: Boolean get() = fallbackKey.isNotBlank()

    /**
     * Whether a key exists at all, from either source. A [Flow] rather than a value because a key
     * saved in Settings has to reach an image sheet that is already on screen.
     */
    val isConfigured: Flow<Boolean> =
        keyStore.key.map { it.isNotBlank() || fallbackKey.isNotBlank() }

    /** Search photos matching [query]. Empty/blank query falls back to a random selection. */
    suspend fun search(query: String, perPage: Int = DEFAULT_PER_PAGE): Result<List<UnsplashPhoto>> {
        if (query.isBlank()) return random(perPage)
        val key = accessKey()
        if (key.isBlank()) return Result.failure(UnsplashException(UnsplashError.MissingKey))
        return runSuspendCatching {
            val url = "$baseUrl/search/photos?per_page=$perPage&query=${query.urlEncode()}"
            val body = http.get(url, authHeaders(key)).getOrThrow()
            loopkyJson.decodeFromString(UnsplashSearchResponseDto.serializer(), body)
                .results.map { it.toDomain() }
        }.mapUnsplashFailure()
    }

    /**
     * Pings [UnsplashPhoto.downloadLocation]. Unsplash's API guidelines require this whenever a
     * photo is actually used (not merely displayed), and not pinging it risks losing API access.
     *
     * The URL comes from the API and carries its own `ixid` tracking params but no `client_id`,
     * so it still needs the auth header. Failure is non-fatal — the user's pick must not depend on it.
     */
    suspend fun trackDownload(photo: UnsplashPhoto): Result<Unit> {
        val key = accessKey()
        if (key.isBlank() || photo.downloadLocation.isBlank()) return Result.success(Unit)
        return http.get(photo.downloadLocation, authHeaders(key)).map { }
    }

    /** A random set of photos for the initial (no-query) grid. */
    suspend fun random(count: Int = DEFAULT_PER_PAGE): Result<List<UnsplashPhoto>> {
        val key = accessKey()
        if (key.isBlank()) return Result.failure(UnsplashException(UnsplashError.MissingKey))
        return randomWith(key, count)
    }

    /**
     * Checks [candidateKey] against the API without storing it, so Settings can reject a typo
     * before it is saved. Deliberately bypasses [keyStore] — the key under test is not the key
     * in effect.
     */
    suspend fun verify(candidateKey: String): Result<Unit> {
        if (candidateKey.isBlank()) return Result.failure(UnsplashException(UnsplashError.MissingKey))
        return randomWith(candidateKey, count = 1).map { }
    }

    private suspend fun randomWith(accessKey: String, count: Int): Result<List<UnsplashPhoto>> =
        runSuspendCatching {
            val url = "$baseUrl/photos/random?count=$count"
            val body = http.get(url, authHeaders(accessKey)).getOrThrow()
            loopkyJson.decodeFromString(ListSerializer(UnsplashPhotoDto.serializer()), body)
                .map { it.toDomain() }
        }.mapUnsplashFailure()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.unsplash.com"
        private const val DEFAULT_PER_PAGE = 30
    }
}

/**
 * Why an Unsplash call failed, in the only terms the UI has to act on: three of these are fixed by
 * changing the key, the fourth is not.
 */
sealed interface UnsplashError {
    /** No key at all — the user has not set one and the build carries none. */
    data object MissingKey : UnsplashError

    /** HTTP 401 — the key is wrong, malformed, or was revoked. */
    data object InvalidKey : UnsplashError

    /**
     * HTTP 403 — the hourly rate limit for this app is exhausted. Unsplash also answers 403 to a
     * missing or garbled `Client-ID`; both verdicts send the user to the same field, so the
     * ambiguity costs nothing.
     */
    data object RateLimited : UnsplashError

    /** Offline, timed out, or unparseable — nothing the user's key can fix. */
    data object Unavailable : UnsplashError
}

/**
 * Carrier for an [UnsplashError] through [Result].
 *
 * The message is the error name and nothing else. It must never carry the access key or the
 * request URL: this string used to be rendered verbatim to the user, URL and all.
 */
class UnsplashException(val error: UnsplashError) : RuntimeException(error.toString())

/** Maps transport failures onto [UnsplashError]; already-typed failures pass through. */
internal fun <T> Result<T>.mapUnsplashFailure(): Result<T> = recoverCatching { cause ->
    throw if (cause is UnsplashException) {
        cause
    } else {
        UnsplashException(
            when ((cause as? HttpError)?.statusCode) {
                HTTP_UNAUTHORIZED -> UnsplashError.InvalidKey
                HTTP_FORBIDDEN -> UnsplashError.RateLimited
                else -> UnsplashError.Unavailable
            },
        )
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/** Where a free Access Key comes from — Unsplash's developer application list. */
const val UNSPLASH_DEVELOPER_URL = "https://unsplash.com/oauth/applications"

/**
 * The last four characters of a stored key — the only form of one that may ever reach the screen.
 * Applies to a user's own key only; the built-in fallback is never displayed in any form.
 */
fun maskedKeySuffix(key: String): String = key.takeLast(MASKED_SUFFIX_LENGTH)

private const val MASKED_SUFFIX_LENGTH = 4

/**
 * Unsplash's API guidelines require every link back to unsplash.com to carry these referral
 * params, so that photographers get credited for the traffic Loopky sends them.
 */
private const val UNSPLASH_REFERRAL = "utm_source=loopky&utm_medium=referral"

/** unsplash.com itself — the platform half of the credit line, referral params included. */
const val UNSPLASH_HOME_URL = "https://unsplash.com/?$UNSPLASH_REFERRAL"

/** Minimal domain model for a web image — only what the grid, credit line + save flow need. */
data class UnsplashPhoto(
    val id: String,
    val thumbUrl: String,
    val fullUrl: String,
    val authorName: String,
    /** The photographer's Unsplash profile, referral params already appended. Blank if unknown. */
    val authorProfileUrl: String = "",
    /** Ping this when the photo is used — see [UnsplashClient.trackDownload]. */
    val downloadLocation: String = "",
)

@Serializable
internal data class UnsplashSearchResponseDto(
    val results: List<UnsplashPhotoDto> = emptyList(),
)

@Serializable
internal data class UnsplashPhotoDto(
    val id: String,
    val urls: UnsplashUrlsDto,
    val user: UnsplashUserDto? = null,
    val links: UnsplashLinksDto? = null,
)

@Serializable
internal data class UnsplashLinksDto(
    val download_location: String = "",
)

@Serializable
internal data class UnsplashUrlsDto(
    val thumb: String = "",
    val small: String = "",
    val regular: String = "",
)

@Serializable
internal data class UnsplashUserDto(
    val name: String = "",
    val username: String = "",
    val links: UnsplashUserLinksDto? = null,
)

@Serializable
internal data class UnsplashUserLinksDto(
    val html: String = "",
)

internal fun UnsplashPhotoDto.toDomain() = UnsplashPhoto(
    id = id,
    thumbUrl = urls.small.ifBlank { urls.thumb },
    fullUrl = urls.regular.ifBlank { urls.small },
    authorName = user?.name.orEmpty(),
    authorProfileUrl = user?.profileUrl().orEmpty().withReferral(),
    downloadLocation = links?.download_location.orEmpty(),
)

private fun UnsplashUserDto.profileUrl(): String {
    val html = links?.html.orEmpty()
    if (html.isNotBlank()) return html
    return if (username.isNotBlank()) "https://unsplash.com/@$username" else ""
}

/** Appends the referral params the guidelines mandate, minding an existing query string. */
private fun String.withReferral(): String = when {
    isBlank() -> ""
    contains('?') -> "$this&$UNSPLASH_REFERRAL"
    else -> "$this?$UNSPLASH_REFERRAL"
}

/** Percent-encode a query string (UTF-8) for use in a URL — commonMain has no URLEncoder. */
@Suppress("MagicNumber") // ASCII boundary (0x80) and hex radix (16) are standard URL-encoding constants.
private fun String.urlEncode(): String = buildString {
    for (byte in this@urlEncode.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        if (code < 0x80 && (ch.isLetterOrDigit() || ch in "-_.~")) {
            append(ch)
        } else {
            append('%')
            append(code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
