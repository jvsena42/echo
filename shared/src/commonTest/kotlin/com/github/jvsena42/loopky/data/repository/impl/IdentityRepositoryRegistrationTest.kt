package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.SECOND_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.noHomeserverRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key lifecycle across mint → register → retry.
 *
 * This layer had no tests, which is why two bugs lived here while the ViewModel tests above them
 * passed: the fakes returned success for whichever repository method was called, so the tests
 * never observed *which* key was registered.
 */
class IdentityRepositoryRegistrationTest {

    private val pubky = FakePubkyClient()
    private val keyStore = FakeLocalKeyStore()

    // Unconfined, so the fire-and-forget cleanup runs the moment it is launched rather than
    // waiting on a scheduler tick this test would then have to guess at.
    private fun TestScope.repository() = identityRepository(
        pubky = pubky,
        localKeyStore = keyStore,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun aMintedKeyIsStoredUnregisteredBeforeSignUpIsEvenAttempted() = runTest {
        // If signUp lands but its response is lost, this is the only record that the identity
        // exists at all.
        pubky.signUpFailure = PubkyError("signup failure: 500")

        val result = repository().createLocalAccount("homeserver-z32", "token-1")

        assertTrue(result.isFailure)
        val held = assertNotNull(keyStore.current(), "the key must survive a failed registration")
        assertFalse(held.registered, "it has no account yet")
    }

    @Test
    fun registeringTheHeldKeyAfterAFailureUsesTheSamePubky() = runTest {
        // The whole point. Minting again would strand the first identity — the Pubky Ring failure
        // mode this path exists to avoid.
        pubky.signUpFailure = PubkyError("signup failure: 500")
        val repo = repository()
        repo.createLocalAccount("homeserver-z32", "token-1")
        val mintedPubky = assertNotNull(keyStore.current()).pubky
        pubky.signUpFailure = null
        pubky.signUpCalls.clear()

        val session = repo.registerHeldKey("homeserver-z32", "token-1").getOrThrow()

        assertEquals(mintedPubky, session.identity.pubky)
        assertEquals(1, pubky.signUpCalls.size)
        assertEquals(assertNotNull(keyStore.current()).secretKeyHex, pubky.signUpCalls.single().secretKey)
    }

    @Test
    fun aSuccessfulRegistrationMarksTheKeyRegisteredSoItCannotBeRegisteredTwice() = runTest {
        val repo = repository()
        repo.createLocalAccount("homeserver-z32", "token-1").getOrThrow()

        assertTrue(assertNotNull(keyStore.current()).registered)
        assertTrue(
            repo.registerHeldKey("homeserver-z32", "token-2").isFailure,
            "an account that already exists must not be registered again",
        )
    }

    @Test
    fun aRestoredPhraseWithNoAccountIsHeldSoItCanActuallyBeRegistered() = runTest {
        // Before this, nothing on restore→unregistered stored the key, so the screen's own
        // "Register this key" button threw on a missing key the user never caused.
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())
        val repo = repository()

        val pubkyHeld = repo.holdKeyForRegistration(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        assertEquals(fakePubkyFor(fakeSecretKeyFor(VALID_TEST_MNEMONIC)), pubkyHeld)
        val held = assertNotNull(keyStore.current())
        assertFalse(held.registered)
        assertEquals(VALID_TEST_MNEMONIC, held.mnemonic)
    }

    @Test
    fun aHeldRestoredKeyCanThenBeRegisteredForThatSamePubky() = runTest {
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())
        val repo = repository()
        repo.holdKeyForRegistration(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        val session = repo.registerHeldKey("homeserver-z32", "token-1").getOrThrow()

        assertEquals(fakePubkyFor(fakeSecretKeyFor(VALID_TEST_MNEMONIC)), session.identity.pubky)
    }

    @Test
    fun registeringWithNothingHeldFailsRatherThanMintingSomethingNew() = runTest {
        // Never mints. A "register" that quietly creates a different identity is the exact bug
        // this method exists to avoid.
        val result = repository().registerHeldKey("homeserver-z32", "token-1")

        assertTrue(result.isFailure)
        assertTrue(pubky.signUpCalls.isEmpty())
    }

    @Test
    fun aHomeserverThatAnswersAboutADifferentPubkyIsAFailureNotASession() = runTest {
        pubky.signUpReturnsPubky = "pksomeoneelse"

        val result = repository().createLocalAccount("homeserver-z32", "token-1")

        assertTrue(result.isFailure, "a session for a key we did not register must not be accepted")
    }

    @Test
    fun callingCreateLocalAccountAgainAfterAFailureKeepsTheSamePubky() = runTest {
        // The retry path, and the fix for the bug that made "Register this key" mint a stranger:
        // three routes re-enter this method with a key already on the device, and minting over it
        // published a different identity than the one the user confirmed.
        pubky.signUpFailure = PubkyError("signup failure: 500")
        val repo = repository()
        repo.createLocalAccount("homeserver-z32", "token-1")
        val first = assertNotNull(keyStore.current()).pubky
        pubky.signUpFailure = null

        val account = repo.createLocalAccount("homeserver-z32", "token-1").getOrThrow()

        assertEquals(first, account.pubky, "the retry must register the key already held")
    }

    @Test
    fun anAlreadyRegisteredKeyIsNotReusedByANewSignup() = runTest {
        // The other side of it: a key with a working account is somebody's identity, and a fresh
        // signup must not re-register it.
        val repo = repository()
        repo.createLocalAccount("homeserver-z32", "token-1").getOrThrow()
        val registered = assertNotNull(keyStore.current()).pubky
        // The fake's RNG is deterministic, so the next mint is spelled out rather than assumed.
        pubky.mintedMnemonic = SECOND_TEST_MNEMONIC

        val second = repo.createLocalAccount("homeserver-z32", "token-2").getOrThrow()

        assertTrue(second.pubky != registered, "a registered key must not be re-registered")
    }

    @Test
    fun theKeyIsMarkedRegisteredAsSoonAsSignUpSucceeds() = runTest {
        // Marked after signUp rather than after the session save: if persisting throws, a key
        // reading `registered = false` would be re-registered next time, spending a second token
        // on an account that already exists.
        val repo = repository()

        repo.createLocalAccount("homeserver-z32", "token-1").getOrThrow()

        assertTrue(assertNotNull(keyStore.current()).registered)
    }

    @Test
    fun creatingAnAccountNeverAdoptsAKeyLeftBehindByAnAbandonedRestore() = runTest {
        // "Create an account in Loopky" means a new identity. Adopting whatever unregistered key
        // happened to be on the device meant a user who had earlier restored a phrase with no
        // account, and walked away, got *that* identity instead — and if it came from a recovery
        // file it carries no mnemonic, so the phrase backup step silently disappears too.
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())
        val repo = repository()
        repo.holdKeyForRegistration(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()
        val restored = assertNotNull(keyStore.current()).pubky
        // The fake's RNG is deterministic, so the fresh mint is spelled out.
        pubky.mintedMnemonic = SECOND_TEST_MNEMONIC

        val created = repo.createLocalAccount("homeserver-z32", "token-1").getOrThrow()

        assertTrue(created.pubky != restored, "a new account must not adopt a restored key")
        assertTrue(created.mnemonic.isNotEmpty(), "a minted account must carry its phrase")
    }

    @Test
    fun aRestoredKeyIsStillRegisterableDeliberatelyThroughRegisterHeldKey() = runTest {
        // The other half: adopting a restored key is a real thing to want — it is just something
        // the user confirms by name on the unregistered screen, not something inferred.
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())
        val repo = repository()
        repo.holdKeyForRegistration(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()
        val restored = assertNotNull(keyStore.current()).pubky

        val session = repo.registerHeldKey("homeserver-z32", "token-1").getOrThrow()

        assertEquals(restored, session.identity.pubky)
    }

    @Test
    fun discardingDropsARestoredKeyAndSparesAMintedOne() = runTest {
        // A minted key exists nowhere else, so an interrupted signup is something to let the user
        // finish rather than clean up behind them. A restored one can be retyped.
        pubky.defaultHomeserverLookup = Result.failure(noHomeserverRecord())
        val repo = repository()
        repo.holdKeyForRegistration(KeySource.Phrase(VALID_TEST_MNEMONIC)).getOrThrow()

        repo.discardUnregisteredKey()
        assertNull(keyStore.current(), "an unregistered restored key is dropped")

        pubky.signUpFailure = PubkyError("signup failure: 500")
        repo.createLocalAccount("homeserver-z32", "token-1")
        assertNotNull(keyStore.current(), "the minted key survives its failed signup")

        repo.discardUnregisteredKey()
        assertNotNull(keyStore.current(), "and is never discarded, because nothing else holds it")
    }
}
