package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.PubkyUris
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * [DiscoveryRepository] backed by [PubkyClient]. Follows are stored as pubky.app-native records
 * (`/pub/pubky.app/follows/{followee}`) on the follower's homeserver, mirroring [DeckRepositoryImpl]:
 * Pubky is the source of truth with a [Mutex]-guarded in-memory follow-set cache for the session.
 *
 * [decksByTag] is a local filter over decks the user can already reach (following + own).
 * [decksByTagGlobal] and [loopkyUsers] go wider, through the Nexus indexer — and everything they
 * read is untrusted, so each entry is verified before it is returned (see [verifiedDeck]).
 */
class DiscoveryRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val deckRepository: DeckRepository,
    private val tagRepository: TagRepository,
    private val identityRepository: IdentityRepository,
) : DiscoveryRepository {

    /** Followee pubkys for the session, or null until first loaded from the homeserver. */
    private var cache: MutableSet<String>? = null
    private val cacheLock = Mutex()

    override suspend fun following(): List<String> {
        cacheLock.withLock { cache }?.let { return it.toList() }
        val owner = session.current()?.identity?.pubky ?: return emptyList()
        // Propagate transport failures for the same reason as DeckRepositoryImpl.listByAuthor:
        // "couldn't reach the homeserver" must not render as "you follow nobody".
        val listJson = pubky.list(PubkyPaths.followsRoot(owner))
            .getOrElse { if (it.isNotFound()) null else throw it }
        val followees = listJson?.let(::parseFolloweesFromList) ?: emptyList()
        cacheLock.withLock { cache = followees.toMutableSet() }
        return followees
    }

    override suspend fun isFollowing(pubky: String): Boolean = following().contains(pubky)

    override suspend fun followUser(pubky: String): Result<Unit> = runCatching {
        val owner = session.requireSession().identity.pubky
        val body = loopkyJson.encodeToString(FollowDto(created_at = epochMillis()))
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

    override suspend fun decksByTagGlobal(tag: Tag, limit: Int): List<Deck> {
        val subjects = tagRepository.taggedSubjects(tag, limit)
        val decks = subjects.mapConcurrently { subject -> verifiedDeck(subject) }
        val kept = decks.filterNotNull().distinctBy { it.id }
        Log.d(TAG, "decksByTagGlobal('${tag.value}'): ${kept.size} kept of ${subjects.size}")
        return kept
    }

    /**
     * Turn one indexer entry into a deck, or `null` if it fails any check. Anyone can tag any URI
     * with any label, so a claim is only worth as much as what it resolves to (#40):
     *
     * 1. the URI has to be shaped like a deck manifest — that alone rules out a tag pointed at an
     *    arbitrary record, a media blob, or another app's data;
     * 2. the tagger has to be the deck's own author, so labelling someone else's deck does nothing;
     * 3. the manifest has to fetch and parse, which is what makes a forged entry useless rather
     *    than merely unlikely.
     */
    private suspend fun verifiedDeck(subject: TaggedSubject): Deck? {
        val ref = PubkyUris.parseDeckManifest(subject.uri.value) ?: return null
        // An empty tagger list means the indexer capped it, not that nobody tagged it — only
        // reject when we can actually see the taggers and the author isn't among them.
        if (subject.taggers.isNotEmpty() && ref.authorPubky !in subject.taggers) {
            Log.d(TAG, "verifiedDeck: ${subject.uri.value} not tagged by its author")
            return null
        }
        return deckRepository.fetchRemote(ref.authorPubky, ref.deckId).getOrNull()
    }

    override suspend fun loopkyUsers(limit: Int): List<PubkyIdentity> {
        val me = session.current()?.identity?.pubky
        val candidates = tagRepository.taggersOf(ReservedTags.USER, limit).filterNot { it == me }
        return candidates.mapConcurrently { pubky -> verifiedUser(pubky) }.filterNotNull()
    }

    /** Kept only if the account tagged *itself*, and only if that account really exists. */
    private suspend fun verifiedUser(pubky: String): PubkyIdentity? {
        if (!tagRepository.isSelfTagged(pubky, ReservedTags.USER)) {
            Log.d(TAG, "verifiedUser: $pubky did not self-tag")
            return null
        }
        return identityRepository.fetchProfile(pubky).getOrNull()
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
        private const val TAG = "Loopky/DiscoveryRepo"
    }
}
