package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdentityRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val store = RecordingSessionStore()
    private val tags = RecordingTagRepository()
    private val repo = IdentityRepositoryImpl(
        pubky = pubky,
        sessionStore = store,
        sessionProvider = session,
        tagRepository = tags,
    )

    private val profileUri = PubkyUri(PubkyPaths.profile(TEST_PUBKY))

    private fun publishProfile(name: String, target: String = TEST_PUBKY) {
        pubky.store[PubkyPaths.profile(target)] = """{"name":"$name","bio":null,"image":null}"""
    }

    @Test
    fun profileIsFetchedOnceAndThenServedFromCache() = runTest {
        publishProfile("Ada")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
        // A second read must not hit the homeserver — a deck grid resolves the same author per tile.
        pubky.failGetWith = IllegalStateException("homeserver should not be called again")
        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    @Test
    fun forceRefreshReadsPastTheCache() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        publishProfile("Ada Lovelace")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
        assertEquals("Ada Lovelace", repo.fetchProfile(TEST_PUBKY, forceRefresh = true).getOrThrow().displayName)
    }

    @Test
    fun aMissingProfileIsNotCached() = runTest {
        assertTrue(repo.fetchProfile(TEST_PUBKY).isFailure)

        // Publishing later must become visible without restarting the app.
        publishProfile("Ada")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    @Test
    fun updatingTheProfileRefreshesTheCache() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals("Ada Lovelace", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    // ── Pubky Ring sign-in ───────────────────────────────────────────────

    @Test
    fun aFailedApprovalIsSurfacedAsIsAndTheFlowIsPolledOnce() = runTest {
        // The FFI takes its auth flow on the first poll, so polling again could only answer
        // "No auth flow in progress" — replacing the real cause with a bogus one (#59).
        val relayDown = PubkyError(
            "Auth approval failed: Request failed: HTTP transport error: error sending " +
                "request for url (https://httprelay.pubky.app/inbox/j42EOsZz)",
        )
        pubky.approvalResult = Result.failure(relayDown)

        val failure = repo.beginSignIn().getOrThrow().complete().exceptionOrNull()

        assertSame(relayDown, failure)
        assertEquals(expected = 1, actual = pubky.awaitApprovalCalls)
    }

    @Test
    fun anApprovedSignInPersistsTheSession() = runTest {
        pubky.approvalResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"s3cret"}""",
        )

        val signedIn = repo.beginSignIn().getOrThrow().complete().getOrThrow()

        assertEquals(TEST_PUBKY, signedIn.identity.pubky)
        assertEquals(TEST_PUBKY, store.saved?.identity?.pubky)
    }

    // ── the loopky-user self-tag ─────────────────────────────────────────

    @Test
    fun restoringASessionAnnouncesTheAccountAsALoopkyUser() = runTest {
        // Without this the account is invisible to the only global directory Loopky has (#40).
        store.saved = fakeSession()

        repo.loadPersistedSession()

        assertEquals(listOf(profileUri to ReservedTags.USER), tags.putReservedTags)
    }

    @Test
    fun theSelfTagIsWrittenOnceNotOnEveryCall() = runTest {
        store.saved = fakeSession()
        publishProfile("Ada")

        repo.loadPersistedSession()
        repo.loadPersistedSession()
        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        // The record is content-addressed, so repeats would overwrite rather than duplicate — but
        // there is no reason to spend the round-trips.
        assertEquals(expected = 1, actual = tags.putReservedTags.size)
    }

    @Test
    fun updatingTheProfileAnnouncesTheAccountIfLoginCouldNot() = runTest {
        publishProfile("Ada")

        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals(listOf(profileUri to ReservedTags.USER), tags.putReservedTags)
    }

    @Test
    fun aFailedSelfTagDoesNotFailTheCallThatTriggeredIt() = runTest {
        tags.failWith = IllegalStateException("indexer write failed")
        store.saved = fakeSession()

        assertEquals(TEST_PUBKY, repo.loadPersistedSession()?.identity?.pubky)
        assertTrue(repo.updateProfile(name = "Ada", bio = null).isSuccess)
    }

    @Test
    fun updatingTheProfileKeepsAPictureTheSessionNeverLearnedAbout() = runTest {
        // Sign-in only enriches the session when the profile has a name or a bio, so an account
        // with a picture and neither reaches updateProfile with avatarUrl = null. Echoing that
        // back used to erase the avatar the first time the user renamed themselves.
        val image = "pubky://$TEST_PUBKY/pub/pubky.app/files/0035JHD6154X0"
        pubky.store[PubkyPaths.profile(TEST_PUBKY)] = """{"name":null,"bio":null,"image":"$image"}"""

        val updated = repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals(image, updated.avatarUrl)
        assertEquals(image, repo.fetchProfile(TEST_PUBKY, forceRefresh = true).getOrThrow().avatarUrl)
    }

    @Test
    fun signingOutForgetsCachedProfiles() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        repo.signOut().getOrThrow()
        pubky.failGetWith = IllegalStateException("offline")

        assertTrue(repo.fetchProfile(TEST_PUBKY).isFailure)
    }
}

private class RecordingSessionStore : SecureSessionStore {
    var saved: Session? = null

    override suspend fun save(session: Session) {
        saved = session
    }

    override suspend fun load(): Session? = saved

    override suspend fun clear() {
        saved = null
    }
}
