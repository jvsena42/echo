package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val store = RecordingSessionStore()
    private val repo = IdentityRepositoryImpl(pubky = pubky, sessionStore = store, sessionProvider = session)

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
