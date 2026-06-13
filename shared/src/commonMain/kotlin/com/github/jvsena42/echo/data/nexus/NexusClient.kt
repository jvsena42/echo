package com.github.jvsena42.echo.data.nexus

import com.github.jvsena42.echo.data.repository.impl.echoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Read-only client for the Pubky Nexus indexer (`/v0` REST API). Nexus aggregates the whole
 * network — it is the only way to answer global questions (trending tags, prefix search) that
 * a single homeserver cannot. All calls are unauthenticated public reads.
 *
 * Echo writes tag records to the user's homeserver in the pubky-app-specs format
 * (see [com.github.jvsena42.echo.data.repository.impl.TagRepositoryImpl]); Nexus indexes them
 * from there, so reads and writes meet without Echo running its own backend.
 */
class NexusClient(
    private val http: HttpFetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** Network-wide hot tags, most-tagged first. */
    suspend fun hotTags(limit: Int = DEFAULT_HOT_TAGS_LIMIT): Result<List<NexusHotTagDto>> =
        runCatching {
            val body = http.get("$baseUrl/v0/tags/hot?limit=$limit").getOrThrow()
            echoJson.decodeFromString(ListSerializer(NexusHotTagDto.serializer()), body)
        }

    /** Tag labels starting with [prefix] — powers tag-input autocomplete. */
    suspend fun searchTagsByPrefix(
        prefix: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Result<List<String>> = runCatching {
        val body = http.get("$baseUrl/v0/search/tags/by_prefix/$prefix?limit=$limit").getOrThrow()
        echoJson.decodeFromString(ListSerializer(String.serializer()), body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://nexus.staging.pubky.app"
        private const val DEFAULT_HOT_TAGS_LIMIT = 20
        private const val DEFAULT_SEARCH_LIMIT = 10
    }
}

/** One entry of `GET /v0/tags/hot` (Nexus `HotTag`). */
@Serializable
data class NexusHotTagDto(
    val label: String,
    val taggers_id: List<String> = emptyList(),
    val tagged_count: Long = 0,
    val taggers_count: Long = 0,
)
