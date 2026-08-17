package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.util.encodeUriComponent
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Read-only client for the Pubky Nexus indexer (`/v0` REST API). Nexus aggregates the whole
 * network — it is the only way to answer global questions (trending tags, prefix search) that
 * a single homeserver cannot. All calls are unauthenticated public reads.
 *
 * Loopky writes tag records to the user's homeserver in the pubky-app-specs format
 * (see [com.github.jvsena42.loopky.data.repository.impl.TagRepositoryImpl]); Nexus indexes them
 * from there, so reads and writes meet without Loopky running its own backend.
 */
class NexusClient(
    private val http: HttpFetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** Tag labels starting with [prefix] — powers tag-input autocomplete. */
    suspend fun searchTagsByPrefix(
        prefix: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val encoded = encodeUriComponent(prefix)
        val body = http.get("$baseUrl/v0/search/tags/by_prefix/$encoded?limit=$limit").getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
    }

    /**
     * Loopky resources carrying [label], most-tagged first — the label → URIs read behind global
     * browse.
     *
     * Only tag records written outside the pubky.app namespace reach this index, which is why
     * deck tags live under `/pub/loopky/tags/` (see `TagRepositoryImpl`). `app` is that namespace,
     * so this cannot return another app's resources.
     */
    suspend fun resourcesByTag(
        label: String,
        limit: Int = DEFAULT_RESOURCE_LIMIT,
        skip: Int = 0,
    ): Result<List<NexusResourceDto>> = runSuspendCatching {
        val url = buildString {
            append("$baseUrl/v0/stream/resources")
            append("?app=$LOOPKY_APP")
            append("&tags=${encodeUriComponent(label)}")
            append("&sorting=taggers_count")
            append("&limit=${limit.coerceIn(1, MAX_RESOURCE_LIMIT)}")
            append("&skip=$skip")
        }
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(NexusResourceDto.serializer()), body)
    }

    /**
     * Every label on one resource with its distinct-tagger count — the read behind "N people
     * follow this deck". Fails with a 404 [HttpError] when nothing has ever tagged [uri].
     */
    suspend fun resourceByUri(uri: String): Result<NexusResourceTagsDto> = runSuspendCatching {
        val url = "$baseUrl/v0/resource/by-uri" +
            "?uri=${encodeUriComponent(uri)}" +
            "&limit_tags=$MAX_TAGS_PER_RESOURCE" +
            "&limit_taggers=1"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(NexusResourceTagsDto.serializer(), body)
    }

    /**
     * Pubkys that have used [label] anywhere. For a self-tag like `loopky-user` the taggers *are*
     * the directory — each entry tagged its own profile.
     *
     * Only reaches tags on pubky.app profiles and posts; resource tags are invisible here.
     */
    suspend fun taggersOfLabel(
        label: String,
        limit: Int = MAX_GLOBAL_TAGGERS_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/tags/taggers/${encodeUriComponent(label)}" +
            "?limit=${limit.coerceIn(1, MAX_GLOBAL_TAGGERS_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
    }

    /**
     * Who tagged user [userId] with [label]. Used to tell a self-tag from someone labelling
     * a stranger: only the former has [userId] among the taggers.
     */
    suspend fun userTaggers(
        userId: String,
        label: String,
        limit: Int = DEFAULT_USER_TAGGERS_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/user/${encodeUriComponent(userId)}/taggers/${encodeUriComponent(label)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_TAGGERS_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(NexusTaggersDto.serializer(), body).users
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://nexus.staging.pubky.app"

        /** The `/pub/{app}/tags/` segment Loopky writes deck tag records under. */
        const val LOOPKY_APP = "loopky"

        private const val DEFAULT_SEARCH_LIMIT = 10
        private const val DEFAULT_RESOURCE_LIMIT = 30
        private const val MAX_RESOURCE_LIMIT = 100
        private const val MAX_TAGS_PER_RESOURCE = 100
        private const val MAX_GLOBAL_TAGGERS_LIMIT = 20
        private const val DEFAULT_USER_TAGGERS_LIMIT = 40
        private const val MAX_USER_TAGGERS_LIMIT = 100
    }
}

/** Identity of an indexed resource (Nexus `ResourceDetails`). */
@Serializable
data class NexusResourceDetailsDto(
    val id: String = "",
    val uri: String,
    val scheme: String = "",
    val indexed_at: Long = 0,
)

/**
 * One label on a resource or user (Nexus `TagDetails`). [taggers] is capped by the request's
 * `limit_taggers`, so count distinct taggers with [taggers_count], never `taggers.size`.
 */
@Serializable
data class NexusTagDetailsDto(
    val label: String,
    val taggers: List<String> = emptyList(),
    val taggers_count: Int = 0,
)

/** One entry of `GET /v0/stream/resources` (Nexus `ResourceView`). */
@Serializable
data class NexusResourceDto(
    val details: NexusResourceDetailsDto,
    val tags: List<NexusTagDetailsDto> = emptyList(),
    val taggers_count: Int = 0,
)

/** `GET /v0/resource/by-uri` (Nexus `ResourceTagsResponse`). */
@Serializable
data class NexusResourceTagsDto(
    val resource: NexusResourceDetailsDto,
    val tags: List<NexusTagDetailsDto> = emptyList(),
)

/** `GET /v0/user/{id}/taggers/{label}` (Nexus `TaggersInfoResponse`). */
@Serializable
data class NexusTaggersDto(
    val users: List<String> = emptyList(),
    val relationship: Boolean = false,
)
