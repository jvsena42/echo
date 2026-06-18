package com.github.jvsena42.echo.data.unsplash

import com.github.jvsena42.echo.data.nexus.HttpFetcher
import com.github.jvsena42.echo.data.repository.impl.echoJson
import kotlinx.serialization.Serializable

/**
 * Read-only client for the Unsplash REST API. Powers the "from web" image search in the cover
 * and card-image sheets. Selected photos are saved by URL (a [com.github.jvsena42.echo.domain.model.MediaRef.Image]
 * with `url` set) — Echo never re-hosts the bytes.
 *
 * The access key comes from `BuildConfig.UNSPLASH_ACCESS_KEY` (see PlatformModule). When the
 * key is blank the client returns empty results so the UI degrades to gallery-only.
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
            echoJson.decodeFromString(UnsplashSearchResponseDto.serializer(), body)
                .results.map { it.toDomain() }
        }
    }

    /** A random set of photos for the initial (no-query) grid. */
    suspend fun random(count: Int = DEFAULT_PER_PAGE): Result<List<UnsplashPhoto>> {
        if (!isConfigured) return Result.success(emptyList())
        return runCatching {
            val url = "$baseUrl/photos/random?count=$count"
            val body = http.get(url, authHeaders).getOrThrow()
            echoJson.decodeFromString(
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

/** Minimal domain model for a web image — only what the grid + save flow need. */
data class UnsplashPhoto(
    val id: String,
    val thumbUrl: String,
    val fullUrl: String,
    val authorName: String,
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
)

internal fun UnsplashPhotoDto.toDomain() = UnsplashPhoto(
    id = id,
    thumbUrl = urls.small.ifBlank { urls.thumb },
    fullUrl = urls.regular.ifBlank { urls.small },
    authorName = user?.name.orEmpty(),
)

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
