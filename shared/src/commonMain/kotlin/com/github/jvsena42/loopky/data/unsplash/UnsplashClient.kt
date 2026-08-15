package com.github.jvsena42.loopky.data.unsplash

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import kotlinx.serialization.Serializable

/**
 * Read-only client for the Unsplash REST API. Powers the "from web" image search in the cover
 * and card-image sheets. Selected photos are saved by URL (a [com.github.jvsena42.loopky.domain.model.MediaRef.Image]
 * with `url` set) — Loopky never re-hosts the bytes.
 *
 * The access key comes from `BuildConfig.UNSPLASH_ACCESS_KEY` (see PlatformModule). When the
 * key is blank the client returns empty results so the UI degrades to gallery-only.
 *
 * Unsplash's API guidelines are licensing terms, not suggestions: callers must credit the
 * photographer and Unsplash with links back (see [UnsplashPhoto.authorProfileUrl] and
 * [UNSPLASH_HOME_URL]) and must call [trackDownload] when a photo is used.
 */
class UnsplashClient(
    private val http: HttpFetcher,
    private val accessKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val authHeaders: Map<String, String>
        get() = mapOf(
            "Authorization" to "Client-ID $accessKey",
            "Accept-Version" to "v1",
        )

    val isConfigured: Boolean get() = accessKey.isNotBlank()

    /** Search photos matching [query]. Empty/blank query falls back to a random selection. */
    suspend fun search(query: String, perPage: Int = DEFAULT_PER_PAGE): Result<List<UnsplashPhoto>> {
        if (!isConfigured) return Result.success(emptyList())
        if (query.isBlank()) return random(perPage)
        return runCatching {
            val url = "$baseUrl/search/photos?per_page=$perPage&query=${query.urlEncode()}"
            val body = http.get(url, authHeaders).getOrThrow()
            loopkyJson.decodeFromString(UnsplashSearchResponseDto.serializer(), body)
                .results.map { it.toDomain() }
        }
    }

    /**
     * Pings [UnsplashPhoto.downloadLocation]. Unsplash's API guidelines require this whenever a
     * photo is actually used (not merely displayed), and not pinging it risks losing API access.
     *
     * The URL comes from the API and carries its own `ixid` tracking params but no `client_id`,
     * so it still needs [authHeaders]. Failure is non-fatal — the user's pick must not depend on it.
     */
    suspend fun trackDownload(photo: UnsplashPhoto): Result<Unit> {
        if (!isConfigured || photo.downloadLocation.isBlank()) return Result.success(Unit)
        return http.get(photo.downloadLocation, authHeaders).map { }
    }

    /** A random set of photos for the initial (no-query) grid. */
    suspend fun random(count: Int = DEFAULT_PER_PAGE): Result<List<UnsplashPhoto>> {
        if (!isConfigured) return Result.success(emptyList())
        return runCatching {
            val url = "$baseUrl/photos/random?count=$count"
            val body = http.get(url, authHeaders).getOrThrow()
            loopkyJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(UnsplashPhotoDto.serializer()),
                body,
            ).map { it.toDomain() }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.unsplash.com"
        private const val DEFAULT_PER_PAGE = 30
    }
}

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
