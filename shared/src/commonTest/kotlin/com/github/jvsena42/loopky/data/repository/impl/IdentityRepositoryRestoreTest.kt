package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import com.github.jvsena42.loopky.testing.homeserverLookupUnreachable
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.noHomeserverRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityRepositoryRestoreTest {

    private val pubky = FakePubkyClient()
    private val keyStore = FakeLocalKeyStore()

    private fun repository() = identityRepository(pubky = pubky, localKeyStore = keyStore)

    private val expectedPubky = fakePubkyFor(fakeSecretKeyFor(VALID_TEST_MNEMONIC))

    @Test
    fun aValidPhraseDerivesItsPubkyWithoutTouchingTheNetwork() = runTest {
        val derived = repository().derivePubky(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        assertEquals(expectedPubky, derived)
        assertTrue(pubky.gets.isEmpty(), "derivation must be local")
    }

    @Test
    fun aPubkyWithNoRecordIsAValueRatherThanAnError() = runTest {
        // "This key has no account" is an answer, not a failure — the UI has specific copy for it
        // and must not be handed a generic exception to guess from.
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())

        assertEquals(HomeserverLookup.NoRecord, repository().lookupHomeserver(expectedPubky))
    }

    @Test
    fun anUnreachableDhtIsReportedAsCouldNotCheckAndNotAsNoAccount() = runTest {
        // The error string contains "failed to resolve", which isNetworkFailure also matches. If
        // the classifier ordering ever regresses, this is the test that catches it — and the bug
        // it prevents is telling someone their recovery phrase is wrong because UDP was blocked.
        pubky.defaultHomeserverLookup = Result.failure(homeserverLookupUnreachable())

        val lookup = repository().lookupHomeserver(expectedPubky)

        val couldNotCheck = assertIs<HomeserverLookup.CouldNotCheck>(lookup)
        assertEquals(ErrorReason.Offline, couldNotCheck.reason)
    }

    @Test
    fun aRegisteredPubkyReportsTheHomeserverHostingIt() = runTest {
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")

        val lookup = repository().lookupHomeserver(expectedPubky)

        assertEquals(HomeserverLookup.Registered("homeserver-z32"), lookup)
    }

    @Test
    fun signingInWithAPhrasePersistsTheKeyAndResolvesTheHomeserverItself() = runTest {
        // The grant flow's session JSON carries no `homeserver` field at all, so a session parsed
        // straight from it has "" and Settings shows "Unknown" forever. The repository resolves it.
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")

        val session = repository().signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        assertEquals(expectedPubky, session.identity.pubky)
        assertEquals("homeserver-z32", session.homeserver)
        assertEquals(fakeSecretKeyFor(VALID_TEST_MNEMONIC), keyStore.current()?.secretKeyHex)
    }

    @Test
    fun aPhraseRestoredKeyKeepsItsWordsSoTheBackupScreensCanShowThem() = runTest {
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")

        repository().signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        assertEquals(VALID_TEST_MNEMONIC, keyStore.current()?.mnemonic)
        assertTrue(assertIs<KeyCustody.Loopky>(keyStore.custody.first()).hasPhrase)
    }

    @Test
    fun aRestoredKeyCountsAsAlreadyBackedUpBecauseTheUserJustProvedTheyHoldIt() = runTest {
        // Nagging someone to back up the phrase they typed in thirty seconds ago would be warning
        // about a risk that does not exist.
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")

        repository().signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        val custody = assertIs<KeyCustody.Loopky>(keyStore.custody.first())
        assertEquals(setOf(BackupMethod.RecoveryPhrase), custody.backedUpBy)
        assertTrue(custody.isBackedUp)
    }

    @Test
    fun noKeyIsStoredWhenTheHomeserverRefusesTheSignIn() = runTest {
        // A device holding a key for an account it cannot sign into is worse than holding nothing:
        // the backup nag would start pestering about an identity that does not work.
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")
        pubky.signInFailure = com.github.jvsena42.loopky.data.pubky.PubkyError("401 Unauthorized")

        val result = repository().signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC))

        assertTrue(result.isFailure)
        assertNull(keyStore.current())
    }

    @Test
    fun aRecoveryFileRestoreYieldsAKeyWithNoPhrase() = runTest {
        // BIP-39 runs one way: a file gives back a secret key and there are no words to recover.
        val secret = fakeSecretKeyFor(VALID_TEST_MNEMONIC)
        val blob = pubky.createRecoveryFile(secret, "correct horse battery staple").getOrThrow()
        pubky.homeserverLookups[fakePubkyFor(secret)] = Result.success("homeserver-z32")

        repository().signInWithKey(KeySource.RecoveryFile(blob, "correct horse battery staple")).getOrThrow()

        assertNull(keyStore.current()?.mnemonic)
        val custody = assertIs<KeyCustody.Loopky>(keyStore.custody.first())
        assertEquals(setOf(BackupMethod.EncryptedFile), custody.backedUpBy)
    }

    @Test
    fun aWrongPassphraseFailsAsADecryptionErrorRatherThanAsAMissingAccount() = runTest {
        val blob = pubky.createRecoveryFile(fakeSecretKeyFor(VALID_TEST_MNEMONIC), "right").getOrThrow()

        val result = repository().signInWithKey(KeySource.RecoveryFile(blob, "wrong"))

        assertTrue(result.isFailure)
        assertNull(keyStore.current())
    }

    @Test
    fun signingOutTakesTheLocalKeyWithTheSession() = runTest {
        // A signed-out device holding a secret key is a credential nobody is watching.
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")
        val repo = repository()
        repo.signInWithKey(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        repo.signOut().getOrThrow()

        assertNull(keyStore.current())
        assertEquals(KeyCustody.External, keyStore.custody.first())
    }

    @Test
    fun aCallerThatAlreadyResolvedTheHomeserverIsNotMadeToAskTheDhtTwice() = runTest {
        // Measured on device: the pre-flight lookup takes ~3s, and the grant session carries no
        // homeserver field, so resolving it again inside sign-in doubled the wait on the only path
        // back into the app for someone locked out.
        pubky.homeserverLookups[expectedPubky] = Result.success("homeserver-z32")
        val repo = repository()
        pubky.homeserverLookupCount = 0

        val session = repo.signInWithKey(
            KeySource.Phrase(VALID_TEST_MNEMONIC),
            knownHomeserver = "homeserver-z32",
        ).getOrThrow()

        assertEquals("homeserver-z32", session.homeserver)
        assertEquals(0, pubky.homeserverLookupCount, "sign-in must not re-resolve a known homeserver")
    }
}
