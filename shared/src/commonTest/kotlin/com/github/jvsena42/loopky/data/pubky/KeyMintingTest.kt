package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.SECOND_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyMintingTest {

    private val pubky = FakePubkyClient()

    @Test
    fun aMintedKeypairIsReturnedOnlyAfterThePhraseReproducesIt() {
        val minted = pubky.mintValidatedKeypair().getOrThrow()

        assertEquals(VALID_TEST_MNEMONIC, minted.mnemonic)
        assertEquals(fakeSecretKeyFor(VALID_TEST_MNEMONIC), minted.secretKeyHex)
        assertEquals(fakePubkyFor(minted.secretKeyHex), minted.pubky)
    }

    @Test
    fun aPhraseThatDerivesADifferentKeyIsRejectedRatherThanShownToTheUser() {
        // The one failure a user cannot detect: the words on screen are valid BIP-39 and the
        // account works today, but the phrase does not derive the key that owns it. They would
        // find out only when restoring on a new device, with nothing left to restore from.
        pubky.mintedSecretKeyOverride = fakeSecretKeyFor("some-other-key")

        val error = pubky.mintValidatedKeypair().exceptionOrNull()

        assertIs<WeakKeyMaterial>(error)
        assertTrue("secret key" in error.stage, "expected the secret-key mismatch stage, got: ${error.stage}")
    }

    @Test
    fun anAllZeroSecretKeyIsRejectedAsABrokenEntropySource() {
        // What a stubbed or failed CSPRNG returns. It is a structurally valid ed25519 secret, so
        // nothing else in the chain would object to it.
        pubky.secretKeyOverride = "0".repeat(64)

        val error = assertIs<WeakKeyMaterial>(pubky.mintValidatedKeypair().exceptionOrNull())
        assertTrue("zero" in error.stage, "expected the all-zero stage, got: ${error.stage}")
    }

    @Test
    fun aSingleRepeatedByteSecretKeyIsRejected() {
        pubky.secretKeyOverride = "ab".repeat(32)

        val error = assertIs<WeakKeyMaterial>(pubky.mintValidatedKeypair().exceptionOrNull())
        assertTrue("repeated" in error.stage, "expected the repeated-byte stage, got: ${error.stage}")
    }

    @Test
    fun aSecretKeyOfTheWrongLengthIsRejected() {
        pubky.secretKeyOverride = "abcdef"

        assertIs<WeakKeyMaterial>(pubky.mintValidatedKeypair().exceptionOrNull())
    }

    @Test
    fun aFalseFromValidateIsTreatedAsInvalidEvenThoughTheCallSucceeded() {
        // `validate_mnemonic_phrase` never fails — it answers through its payload. A caller
        // checking Result.isSuccess would accept every phrase ever passed to it while looking
        // exactly like it was validating them.
        pubky.mintedMnemonic = "not a real phrase"
        pubky.validMnemonics.remove("not a real phrase")

        assertTrue(pubky.validateMnemonicPhrase("not a real phrase").isSuccess)
        assertIs<WeakKeyMaterial>(pubky.mintValidatedKeypair().exceptionOrNull())
    }

    @Test
    fun anEntropyFailureIsTerminalAndIsNeverRetriedWithSomethingWeaker() {
        pubky.mintFailure = PubkyError("Failed to generate mnemonic: entropy unavailable")

        assertTrue(pubky.mintValidatedKeypair().isFailure)
    }

    @Test
    fun derivingFromAUserSuppliedPhraseKeepsTheWordsAndDerivesThePubky() {
        val derived = pubky.keypairFromMnemonic(SECOND_TEST_MNEMONIC).getOrThrow()

        assertEquals(SECOND_TEST_MNEMONIC, derived.mnemonic)
        assertEquals(fakePubkyFor(derived.secretKeyHex), derived.pubky)
    }

    @Test
    fun aPhraseThatIsNotBip39IsRejectedAsInvalidRatherThanAsAMissingAccount() {
        // The honest half of the restore path: this phrase really is wrong, and saying so is
        // right. It must not be confused with a valid phrase that has no account, which is a
        // completely different message and a completely different remedy.
        val error = pubky.keypairFromMnemonic("nonsense words that are not bip39 at all").exceptionOrNull()

        assertIs<InvalidMnemonic>(error)
    }

    @Test
    fun aPastedPhraseSurvivesOddWhitespaceAndTheSeparatorsPubkyRingUses() {
        // Ring's own formatImportData normalises -, _ and + to spaces, so a phrase exported from
        // it in any of those forms has to come back in.
        val messy = "  ABANDON abandon\tabandon-abandon_abandon+abandon abandon abandon " +
            "abandon   abandon abandon about  "

        val derived = pubky.keypairFromMnemonic(messy).getOrThrow()

        assertEquals(VALID_TEST_MNEMONIC, derived.mnemonic)
    }

    @Test
    fun aKeyRestoredFromARecoveryFileHasNoPhraseRatherThanAnInventedOne() {
        // BIP-39 runs one way, so a file-restored key genuinely has no words. Anything downstream
        // offering a "show your recovery phrase" screen for it would render nothing.
        val secret = fakeSecretKeyFor(VALID_TEST_MNEMONIC)

        val derived = pubky.keypairFromSecretKey(secret).getOrThrow()

        assertNull(derived.mnemonic)
        assertEquals(fakePubkyFor(secret), derived.pubky)
    }
}
