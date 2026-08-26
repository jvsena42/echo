package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.domain.model.UnbackedUpLocalKey
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import com.github.jvsena42.loopky.testing.identityRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityRepositorySignOutGuardTest {

    private val pubky = FakePubkyClient()
    private val secret = fakeSecretKeyFor(VALID_TEST_MNEMONIC)

    private fun keyStore(backedUpBy: Set<BackupMethod>) = FakeLocalKeyStore(
        LocalKey(
            secretKeyHex = secret,
            pubky = fakePubkyFor(secret),
            mnemonic = VALID_TEST_MNEMONIC,
            backedUpBy = backedUpBy,
        ),
    )

    @Test
    fun signingOutIsRefusedWhileTheOnlyCopyOfTheKeyIsOnThisDevice() = runTest {
        // The refusal is what forces the UI to raise a confirm. A dialog-only guard would let any
        // future caller sign out silently and take the identity with it.
        val store = keyStore(emptySet())
        val repo = identityRepository(pubky = pubky, localKeyStore = store)

        val error = repo.signOut().exceptionOrNull()

        assertIs<UnbackedUpLocalKey>(error)
        assertEquals(fakePubkyFor(secret), error.pubky)
        assertNotNull(store.current(), "the key must survive a refused sign-out")
    }

    @Test
    fun forcingItThroughIsWhatTheConfirmDoes() = runTest {
        val store = keyStore(emptySet())
        val repo = identityRepository(pubky = pubky, localKeyStore = store)

        assertTrue(repo.signOut(force = true).isSuccess)
        assertNull(store.current())
    }

    @Test
    fun aBackedUpKeySignsOutWithNoPromptAtAll() = runTest {
        // Nothing is lost: the phrase is written down. Prompting anyway would train people to tap
        // through the warning that matters.
        val store = keyStore(setOf(BackupMethod.RecoveryPhrase))
        val repo = identityRepository(pubky = pubky, localKeyStore = store)

        assertTrue(repo.signOut().isSuccess)
        assertNull(store.current())
    }

    @Test
    fun aRingHeldAccountSignsOutWithNoPromptBecauseThereIsNothingHereToLose() = runTest {
        val store = FakeLocalKeyStore()
        val repo = identityRepository(pubky = pubky, localKeyStore = store)

        assertTrue(repo.signOut().isSuccess)
    }

    @Test
    fun aRestoredAccountSignsOutWithoutAPromptBecauseTheUserHoldsThePhrase() = runTest {
        // They typed it in to get here, so warning them they might lose it is warning about a
        // risk that does not exist.
        val store = FakeLocalKeyStore()
        pubky.homeserverLookups[fakePubkyFor(secret)] = Result.success("homeserver-z32")
        val repo = identityRepository(pubky = pubky, localKeyStore = store)
        repo.signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        assertTrue(repo.signOut().isSuccess)
    }
}
