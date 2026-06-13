package com.github.jvsena42.echo.data.repository.impl

import com.github.jvsena42.echo.data.pubky.FollowDto
import com.github.jvsena42.echo.data.pubky.PubkyClient
import com.github.jvsena42.echo.data.pubky.PubkyPaths
import com.github.jvsena42.echo.data.pubky.SessionProvider
import com.github.jvsena42.echo.data.pubky.SessionRevalidator
import com.github.jvsena42.echo.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.echo.data.pubky.putWithSessionRetry
import com.github.jvsena42.echo.data.pubky.requireSession
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.DiscoveryRepository
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.util.Log
import com.github.jvsena42.echo.util.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [DiscoveryRepository] backed by [PubkyClient]. Follows are stored as pubky.app-native records
 * (`/pub/pubky.app/follows/{followee}`) on the follower's homeserver, mirroring [DeckRepositoryImpl]:
 * Pubky is the source of truth with a [Mutex]-guarded in-memory follow-set cache for the session.
 *
 * Tag discovery is a local filter over decks the user can already reach (following + own); a
 * network-wide tag index would need a backend indexer (see [com.github.jvsena42.echo.data.repository.TagRepository.trending]).
 */
class DiscoveryRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
) : DiscoveryRepository {

    /** Followee pubkys for the session, or null until first loaded from the homeserver. */
    private var cache: MutableSet<String>? = null
    private val cacheLock = Mutex()

    override suspend fun following(): List<String> {
        cacheLock.withLock { cache }?.let { return it.toList() }
        val owner = session.current()?.identity?.pubky ?: return emptyList()
        val followees = pubky.list(PubkyPaths.followsRoot(owner)).getOrNull()
            ?.let(::parseFolloweesFromList)
            ?: emptyList()
        cacheLock.withLock { cache = followees.toMutableSet() }
        return followees
    }

    override suspend fun isFollowing(pubky: String): Boolean = following().contains(pubky)

    override suspend fun followUser(pubky: String): Result<Unit> = runCatching {
        val owner = session.requireSession().identity.pubky
        val body = echoJson.encodeToString(FollowDto(created_at = epochMillis()))
        this.pubky.putWithSessionRetry(PubkyPaths.follow(owner, pubky), body, session, revalidator)
            .getOrThrow()
        cacheLock.withLock { (cache ?: mutableSetOf<String>().also { cache = it }).add(pubky) }
        Unit
    }

    override suspend fun unfollowUser(pubky: String): Result<Unit> = runCatching {
        val owner = session.requireSession().identity.pubky
        this.pubky.deleteWithSessionRetry(PubkyPaths.follow(owner, pubky), session, revalidator)
            .getOrThrow()
        cacheLock.withLock { cache?.remove(pubky) }
        Unit
    }

    override suspend fun decksFromFollowing(): List<Deck> {
        val followees = following()
        return followees
            .flatMap { author ->
                runCatching { deckRepository.listByAuthor(author) }.getOrElse {
                    Log.e(TAG, "decksFromFollowing: listByAuthor failed for $author — ${it.message}", it)
                    emptyList()
                }
            }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun decksByTag(tag: Tag): List<Deck> {
        val following = decksFromFollowing()
        val own = runCatching { deckRepository.listOwned() }.getOrElse { emptyList() }
        return (following + own)
            .distinctBy { it.id }
            .filter { tag in it.tags }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Parse the FFI `list` result for follow records by extracting the path segment after
     * `/pub/pubky.app/follows/`. Same defensive substring scan as [DeckRepositoryImpl]'s deck-id
     * parser, since the list payload format is not a stable contract.
     */
    private fun parseFolloweesFromList(payload: String): List<String> {
        val marker = "/pub/pubky.app/follows/"
        val ids = linkedSetOf<String>()
        var index = 0
        while (true) {
            val hit = payload.indexOf(marker, index)
            if (hit == -1) break
            val start = hit + marker.length
            val end = payload.indexOf('/', start).let { if (it == -1) payload.length else it }
            val candidate = payload.substring(start, end).trim('"', ' ', '\n', '\r', ',')
            if (candidate.isNotEmpty()) ids.add(candidate)
            index = end
        }
        return ids.toList()
    }

    companion object {
        private const val TAG = "Echo/DiscoveryRepo"
    }
}
