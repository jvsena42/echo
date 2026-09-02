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
    /**
     * Which indexer this build talks to. Deliberately has no default: staging and production
     * index different networks, so a missing wire-up must fail to compile rather than quietly
     * ship a release pointed at staging (#42). Platform modules supply it from the injected
     * `PubkyEnvironment.nexusBaseUrl`, so it cannot end up on a different network from the
     * homeserver the app publishes to (#205) — see `androidPlatformModule` / `doInitKoin`.
     *
     * Public because the indexer also serves profile pictures, which callers build URLs for
     * themselves (see [com.github.jvsena42.loopky.domain.model.avatarDisplayUrl]).
     */
    val baseUrl: String,
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
     * Pubkys whose profile name starts with [prefix] — the people half of Loopky's search box.
     *
     * Indexes every pubky.app profile, not only accounts that have opened Loopky, so callers
     * decide which matches are worth showing (see
     * [com.github.jvsena42.loopky.data.repository.DiscoveryRepository.searchPeople]).
     *
     * Lexicographic on the indexed name: a prefix, not a substring. Searching "ada" finds "Ada
     * Lovelace" and never "Grace Ada".
     */
    suspend fun searchUsersByName(
        prefix: String,
        limit: Int = DEFAULT_USER_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/users/by_name/${encodeUriComponent(prefix)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_SEARCH_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
    }

    /**
     * The same search over pubkys rather than names — someone who was handed part of a key rather
     * than a name.
     *
     * Nexus rejects a prefix shorter than [MIN_USER_ID_PREFIX] characters outright, so callers
     * must not ask below it; a full pubky needs no search at all and should be opened directly.
     */
    suspend fun searchUsersById(
        prefix: String,
        limit: Int = DEFAULT_USER_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/users/by_id/${encodeUriComponent(prefix)}" +
            "?limit=${limit.coerceIn(1, MAX_USER_SEARCH_LIMIT)}"
        val body = http.get(url).getOrThrow()
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
     * Pubkys whose **profile** carries [label] — the read the `loopky-user` directory is built on.
     *
     * Replaces `/v0/tags/taggers/{label}`, which looked like this query and is not one: without a
     * `user_id` that endpoint answers out of the hot-tags Redis cache
     * (`nexus-common/src/models/tag/global.rs:119-135`), so a label that never reached the top-N
     * network-wide misses the map and comes back `[]` — indistinguishable from nobody having used
     * it. Prod's `User` hot list bottomed out at 66 tagged users while `loopky-user` had 2, and
     * `bitkit-user` reads empty for the same reason, so every app-directory label hits it (#134).
     * The reach-scoped branch of the same endpoint is no better: it is hardcoded to `Post`
     * subjects, so it cannot see a profile tag at any count (pubky/pubky-nexus#1036).
     *
     * **Fails with a 404 [HttpError] on an indexer that predates the endpoint** — it landed in
     * pubky/pubky-nexus#1030 and is live on staging but not yet on prod, so callers must fall back
     * rather than treat the failure as "no Loopky users" (see
     * [com.github.jvsena42.loopky.data.repository.impl.DiscoveryRepositoryImpl.loopkyUsers]).
     *
     * Untrusted like every other tag read: `score` counts taggers and says nothing about *who*
     * tagged, so a stranger labelling someone else scores the same as a self-tag. Verify with
     * [userTaggers].
     */
    suspend fun usersByProfileTag(
        label: String,
        limit: Int = DEFAULT_PROFILE_TAG_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        // The endpoint takes a comma-separated `tags` list and ORs it; Loopky asks one label at a
        // time, so the multi-label scoring is deliberately not relied on here.
        val url = "$baseUrl/v0/search/users/by_tags" +
            "?tags=${encodeUriComponent(label)}" +
            "&limit=${limit.coerceIn(1, MAX_PROFILE_TAG_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(NexusScoredUserDto.serializer()), body)
            .map { it.user_id }
    }

    /**
     * Authors of the posts carrying [label], most recent first, each pubky once.
     *
     * Post tags are the one Loopky label that reaches the global tag index (Architecture.md §7.7
     * point 5), and a `post_key` is `{author}:{post_id}` — so the authors of every deck
     * announcement fall out of the index without fetching a single post.
     */
    suspend fun postAuthorsByTag(
        label: String,
        limit: Int = DEFAULT_POST_SEARCH_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/search/posts/by_tag/${encodeUriComponent(label)}" +
            "?limit=${limit.coerceIn(1, MAX_POST_SEARCH_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(NexusPostKeyDto.serializer()), body)
            .mapNotNull { it.post_key.substringBefore(':').takeIf { author -> author.isNotEmpty() } }
            .distinct()
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

    /**
     * Pubkys that follow [userId], most recent first.
     *
     * The one social read a homeserver cannot answer: a follow record lives on the *follower's*
     * homeserver, so "who follows me" is a network-wide reverse lookup and only the indexer holds
     * it. The forward direction needs nothing from here — list `/pub/pubky.app/follows/` on the
     * user's own homeserver instead, which is both cheaper and first-hand.
     */
    suspend fun followers(
        userId: String,
        limit: Int = DEFAULT_FOLLOWS_LIMIT,
    ): Result<List<String>> = runSuspendCatching {
        val url = "$baseUrl/v0/user/${encodeUriComponent(userId)}/followers" +
            "?limit=${limit.coerceIn(1, MAX_FOLLOWS_LIMIT)}"
        val body = http.get(url).getOrThrow()
        loopkyJson.decodeFromString(ListSerializer(String.serializer()), body)
    }

    companion object {
        /** The `/pub/{app}/tags/` segment Loopky writes deck tag records under. */
        const val LOOPKY_APP = "loopky"

        /** Nexus rejects a shorter pubky prefix than this on `/search/users/by_id`. */
        const val MIN_USER_ID_PREFIX = 3

        private const val DEFAULT_SEARCH_LIMIT = 10
        private const val DEFAULT_USER_SEARCH_LIMIT = 20
        private const val MAX_USER_SEARCH_LIMIT = 100
        private const val DEFAULT_RESOURCE_LIMIT = 30
        private const val MAX_RESOURCE_LIMIT = 100
        private const val MAX_TAGS_PER_RESOURCE = 100
        private const val DEFAULT_PROFILE_TAG_LIMIT = 50
        private const val MAX_PROFILE_TAG_LIMIT = 200
        private const val DEFAULT_POST_SEARCH_LIMIT = 100
        private const val MAX_POST_SEARCH_LIMIT = 200
        private const val DEFAULT_USER_TAGGERS_LIMIT = 40
        private const val MAX_USER_TAGGERS_LIMIT = 100
        private const val DEFAULT_FOLLOWS_LIMIT = 60
        private const val MAX_FOLLOWS_LIMIT = 200
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

/**
 * One hit of `GET /v0/search/users/by_tags` (Nexus scored user). [score] is a tagger count, not
 * evidence of a self-tag — see [NexusClient.usersByProfileTag].
 */
@Serializable
data class NexusScoredUserDto(
    val user_id: String,
    val score: Double = 0.0,
)

/** One hit of `GET /v0/search/posts/by_tag/{label}`. [post_key] is `{author}:{post_id}`. */
@Serializable
data class NexusPostKeyDto(
    val post_key: String,
)

/** `GET /v0/user/{id}/taggers/{label}` (Nexus `TaggersInfoResponse`). */
@Serializable
data class NexusTaggersDto(
    val users: List<String> = emptyList(),
    val relationship: Boolean = false,
)
