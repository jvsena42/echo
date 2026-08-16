package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
    )
    private val tagRepo = RecordingTagRepository()
    private val identityRepo = IdentityRepositoryImpl(
        pubky = pubky,
        sessionStore = NoopSessionStore(),
        sessionProvider = session,
        tagRepository = tagRepo,
    )
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        tagRepository = tagRepo,
        identityRepository = identityRepo,
    )

    private fun followUrl(followee: String) =
        "pubky://$TEST_PUBKY/pub/pubky.app/follows/$followee"

    private fun putRemoteManifest(author: String, deckId: String, updatedAt: Long) {
        val dto = testDeck(id = deckId, authorPubky = author, updatedAt = updatedAt).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }

    // ── follow / unfollow ────────────────────────────────────────────────

    @Test
    fun followUserWritesTheFollowRecord() = runTest {
        repo.followUser("friendpk").getOrThrow()

        val body = pubky.store.getValue(followUrl("friendpk"))
        assertTrue(loopkyJson.decodeFromString<FollowDto>(body).created_at > 0)
        assertTrue(repo.isFollowing("friendpk"))
    }

    @Test
    fun unfollowUserDeletesTheFollowRecord() = runTest {
        repo.followUser("friendpk").getOrThrow()

        repo.unfollowUser("friendpk").getOrThrow()

        assertTrue(followUrl("friendpk") !in pubky.store)
        assertEquals(listOf(followUrl("friendpk")), pubky.deletes)
        assertFalse(repo.isFollowing("friendpk"))
    }

    @Test
    fun followingParsesTheListPayloadOnAColdCache() = runTest {
        pubky.store[followUrl("friend1")] = """{"created_at":1}"""
        pubky.store[followUrl("friend2")] = """{"created_at":2}"""

        val following = repo.following()

        assertEquals(
            listOf("pubky://$TEST_PUBKY/pub/pubky.app/follows/"),
            pubky.listedPrefixes,
        )
        // Exact equality on purpose: the old substring scan cut each id at the *next* url's
        // "pubky://" and returned `friend1","pubky:`, which a startsWith assertion happily
        // accepted. Anything looser than this cannot tell the fix from the bug.
        assertEquals(listOf("friend1", "friend2"), following)
    }

    @Test
    fun followingIsEmptyWhenSignedOut() = runTest {
        session.set(null)

        assertEquals(emptyList(), repo.following())
    }

    // ── feed ─────────────────────────────────────────────────────────────

    @Test
    fun decksFromFollowingAggregatesNewestFirst() = runTest {
        repo.followUser("friend1").getOrThrow()
        repo.followUser("friend2").getOrThrow()
        putRemoteManifest(author = "friend1", deckId = "older", updatedAt = 100L)
        putRemoteManifest(author = "friend2", deckId = "newest", updatedAt = 300L)
        putRemoteManifest(author = "friend1", deckId = "middle", updatedAt = 200L)

        val feed = repo.decksFromFollowing()

        assertEquals(listOf("newest", "middle", "older"), feed.map { it.id })
    }

    @Test
    fun decksFromFollowingIsEmptyWithNoFollows() = runTest {
        assertEquals(emptyList(), repo.decksFromFollowing())
    }

    @Test
    fun followingThrowsWhenTheHomeserverIsUnreachable() = runTest {
        // Must not degrade to "you follow nobody" — that is indistinguishable from the real
        // empty state and hides the fact that the device is offline.
        pubky.failListWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.following() }
    }

    @Test
    fun followingReturnsEmptyWhenTheFollowsPathDoesNotExist() = runTest {
        pubky.failListWith = PubkyError("not found: pubky://$TEST_PUBKY/pub/pubky.app/follows/")

        assertEquals(emptyList(), repo.following())
    }

    // ── global browse (indexer-backed, everything untrusted) ─────────────

    private fun manifestUri(author: String, deckId: String) =
        PubkyUri("pubky://$author/pub/loopky/decks/$deckId/manifest.json")

    private fun tagged(uri: PubkyUri, taggers: List<String>) =
        TaggedSubject(uri = uri, taggers = taggers, taggersCount = taggers.size)

    @Test
    fun globalBrowseFindsADeckFromSomeoneYouDoNotFollow() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("strangerpk")),
            ),
        )

        val decks = repo.decksByTagGlobal(ReservedTags.DECK)

        assertEquals(listOf("deck1"), decks.map { it.id })
        assertTrue(repo.following().isEmpty(), "found without following anyone")
    }

    @Test
    fun globalBrowseDropsATagPointedAtSomethingThatIsNotADeck() = runTest {
        // A forged entry is only useless if it has to resolve to a real deck to be shown.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(PubkyUri("pubky://strangerpk/pub/pubky.app/posts/abc"), listOf("strangerpk")),
                tagged(PubkyUri("https://example.com/free-decks"), listOf("strangerpk")),
                tagged(manifestUri("strangerpk", "deck1"), listOf("strangerpk")),
            ),
        )
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)

        assertEquals(listOf("deck1"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun globalBrowseDropsADeckTaggedBySomeoneOtherThanItsAuthor() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("impostorpk")),
            ),
        )

        assertEquals(emptyList(), repo.decksByTagGlobal(ReservedTags.DECK))
    }

    @Test
    fun globalBrowseDropsAManifestThatDoesNotFetch() = runTest {
        // Right shape, nothing behind it — the manifest was never published or has been deleted.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "ghost"), taggers = listOf("strangerpk")),
            ),
        )

        assertEquals(emptyList(), repo.decksByTagGlobal(ReservedTags.DECK))
    }

    @Test
    fun globalBrowseDedupesRepeatedSubjects() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        val subject = tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("strangerpk"))
        tagRepo.subjectsByTag = mapOf(ReservedTags.DECK to listOf(subject, subject))

        assertEquals(expected = 1, actual = repo.decksByTagGlobal(ReservedTags.DECK).size)
    }

    // ── the Loopky user directory ────────────────────────────────────────

    @Test
    fun loopkyUsersReturnsSelfTaggedAccountsWithProfiles() = runTest {
        pubky.store["pubky://strangerpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""
        tagRepo.taggersByTag = mapOf(ReservedTags.USER to listOf("strangerpk"))
        tagRepo.selfTaggers = setOf("strangerpk")

        val users = repo.loopkyUsers()

        assertEquals(listOf("strangerpk"), users.map { it.pubky })
        assertEquals("Ada", users.single().displayName)
    }

    @Test
    fun loopkyUsersDropsAccountsThatDidNotTagThemselves() = runTest {
        // Someone tagging *other* people loopky-user shows up as a tagger; they are not a claim
        // about themselves and must not enter the directory.
        pubky.store["pubky://impostorpk/pub/pubky.app/profile.json"] =
            """{"name":"Impostor","bio":null,"image":null}"""
        tagRepo.taggersByTag = mapOf(ReservedTags.USER to listOf("impostorpk"))
        tagRepo.selfTaggers = emptySet()

        assertEquals(emptyList(), repo.loopkyUsers())
    }

    @Test
    fun loopkyUsersDropsAccountsWithNoProfileAndTheSignedInUser() = runTest {
        tagRepo.taggersByTag = mapOf(ReservedTags.USER to listOf("ghostpk", TEST_PUBKY))
        tagRepo.selfTaggers = setOf("ghostpk", TEST_PUBKY)

        assertEquals(emptyList(), repo.loopkyUsers())
    }
}

private class NoopSessionStore : SecureSessionStore {
    override suspend fun save(session: Session) = Unit
    override suspend fun load(): Session? = null
    override suspend fun clear() = Unit
}
