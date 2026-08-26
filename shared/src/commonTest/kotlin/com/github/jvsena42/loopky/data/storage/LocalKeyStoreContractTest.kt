package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour every [LocalKeyStore] has to have, exercised over the in-memory double. The
 * platform implementations are the same logic over KVault; what is pinned here is the contract
 * they share.
 */
class LocalKeyStoreContractTest {

    private val secret = fakeSecretKeyFor(VALID_TEST_MNEMONIC)

    private fun key(mnemonic: String? = VALID_TEST_MNEMONIC) = LocalKey(
        secretKeyHex = secret,
        pubky = fakePubkyFor(secret),
        mnemonic = mnemonic,
    )

    @Test
    fun anEmptyStoreReportsRingCustodyRatherThanAnEmptyLocalOne() = runTest {
        // The distinction the whole backup surface hangs off: "Ring holds it" has nothing to back
        // up, where "Loopky holds it, backed up by nothing" is the state that needs a nag.
        assertEquals(KeyCustody.External, FakeLocalKeyStore().custody.first())
    }

    @Test
    fun aSavedKeyIsCustodyLoopkyAndIsNotYetBackedUp() = runTest {
        val store = FakeLocalKeyStore()

        store.save(key())

        val custody = assertIs<KeyCustody.Loopky>(store.custody.first())
        assertEquals(fakePubkyFor(secret), custody.pubky)
        assertFalse(custody.isBackedUp)
        assertTrue(custody.hasPhrase)
    }

    @Test
    fun custodyNamesTheAccountWithoutCarryingTheSecret() = runTest {
        // KeyCustody reaches UiStates, logs and composables, so this is the guard that it can.
        val store = FakeLocalKeyStore()
        store.save(key())

        val custody = assertIs<KeyCustody.Loopky>(store.custody.first())

        assertFalse(secret in custody.toString(), "custody must never carry the secret key")
        assertFalse(VALID_TEST_MNEMONIC in custody.toString(), "custody must never carry the mnemonic")
    }

    @Test
    fun aKeyWithNoPhraseSaysSoSoNoScreenOffersToShowOne() = runTest {
        // A recovery-file restore has no words: BIP-39 runs one way. A phrase screen for this
        // account would render nothing at all.
        val store = FakeLocalKeyStore()

        store.save(key(mnemonic = null))

        assertFalse(assertIs<KeyCustody.Loopky>(store.custody.first()).hasPhrase)
    }

    @Test
    fun backupMethodsAccumulateRatherThanReplacingEachOther() = runTest {
        // Writing the words down is not a reason to stop offering the encrypted file, and the UI
        // ticks each method separately.
        val store = FakeLocalKeyStore()
        store.save(key())

        store.markBackedUp(BackupMethod.RecoveryPhrase)
        store.markBackedUp(BackupMethod.EncryptedFile)

        val custody = assertIs<KeyCustody.Loopky>(store.custody.first())
        assertEquals(setOf(BackupMethod.RecoveryPhrase, BackupMethod.EncryptedFile), custody.backedUpBy)
        assertTrue(custody.isBackedUp)
    }

    @Test
    fun markingTheSameMethodTwiceDoesNotRewriteTheKeystore() = runTest {
        val store = FakeLocalKeyStore()
        store.save(key())
        store.markBackedUp(BackupMethod.RecoveryPhrase)
        val writesAfterFirst = store.writes

        store.markBackedUp(BackupMethod.RecoveryPhrase)

        assertEquals(writesAfterFirst, store.writes)
    }

    @Test
    fun markingBackedUpOnAnEmptyStoreIsANoOpRatherThanACrash() = runTest {
        val store = FakeLocalKeyStore()

        store.markBackedUp(BackupMethod.PubkyRing)

        assertEquals(KeyCustody.External, store.custody.first())
    }

    @Test
    fun clearingDropsBothTheKeyAndTheCustody() = runTest {
        // Sign-out's job. Whether it was *safe* to do this is decided upstream, by the confirm the
        // repository forces — the store itself does not second-guess the caller.
        val store = FakeLocalKeyStore()
        store.save(key())

        store.clear()

        assertNull(store.current())
        assertEquals(KeyCustody.External, store.custody.first())
    }

    @Test
    fun theStoredKeyRoundTripsThroughTheSerializerTheVaultsUse() {
        // The platform stores persist this with sessionStoreJson; a field that will not round-trip
        // would strand a real account on the next app start with nothing reporting it.
        val json = sessionStoreJson.encodeToString(key())

        val decoded = sessionStoreJson.decodeFromString<LocalKey>(json)

        assertEquals(key(), decoded)
    }
}
