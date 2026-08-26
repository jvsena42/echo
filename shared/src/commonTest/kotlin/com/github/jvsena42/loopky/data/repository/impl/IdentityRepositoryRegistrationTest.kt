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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private fun repository() = identityRepository(pubky = pubky, localKeyStore = keyStore)

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
}
