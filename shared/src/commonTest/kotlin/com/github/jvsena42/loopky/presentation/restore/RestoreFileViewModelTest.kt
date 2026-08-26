package com.github.jvsena42.loopky.presentation.restore

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreFileViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = null)
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RestoreFileViewModel(identityRepository = identityRepo)

    private fun RestoreFileViewModel.pickAFile() = onFilePicked("recovery.pkarr", "cHVia3kub3JnL3JlY292ZXJ5")

    @Test
    fun aWrongPassphraseIsNeverReportedAsAMissingAccount() = runTest {
        // Decryption failing says nothing about whether the account exists. Reporting it as
        // "no account" would send someone hunting for a lost identity over a typo.
        identityRepo.derivedPubky = Result.failure(IllegalStateException("Failed to decrypt recovery file"))
        val vm = viewModel()
        vm.pickAFile()
        vm.onPassphraseChange("wrong")

        vm.onSubmit()
        advanceUntilIdle()

        assertIs<RestoreOutcome.WrongPassphrase>(vm.state.value.outcome)
    }

    @Test
    fun aFailedDecryptionCostsNoDhtLookup() = runTest {
        // There is nothing to look up, and the lookup is rate-limited for a reason.
        identityRepo.derivedPubky = Result.failure(IllegalStateException("Failed to decrypt recovery file"))
        val vm = viewModel()
        vm.pickAFile()
        vm.onPassphraseChange("wrong")

        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(0, identityRepo.lookupCount)
    }

    @Test
    fun aDecryptedKeyWithNoAccountShowsTheDerivedPubkyLikeThePhrasePathDoes() = runTest {
        identityRepo.derivedPubky = Result.success("pkstranger")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()
        vm.pickAFile()
        vm.onPassphraseChange("right")

        vm.onSubmit()
        advanceUntilIdle()

        assertEquals("pkstranger", assertIs<RestoreOutcome.NoAccount>(vm.state.value.outcome).pubky)
    }

    @Test
    fun aDhtOutageOffersRetryRatherThanAVerdictOnTheFile() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.CouldNotCheck(ErrorReason.HomeserverLookupFailed)
        val vm = viewModel()
        vm.pickAFile()
        vm.onPassphraseChange("right")

        vm.onSubmit()
        advanceUntilIdle()

        assertIs<RestoreOutcome.CouldNotCheck>(vm.state.value.outcome)
    }

    @Test
    fun submitDoesNothingUntilBothAFileAndAPassphraseArePresent() = runTest {
        val vm = viewModel()

        vm.onSubmit()
        advanceUntilIdle()
        assertTrue(identityRepo.signInWithKeyCalls.isEmpty())

        vm.pickAFile()
        vm.onSubmit()
        advanceUntilIdle()
        assertTrue(identityRepo.signInWithKeyCalls.isEmpty(), "a passphrase is still required")
    }

    @Test
    fun thePassphraseAndTheFileAreClearedWhenTheScreenGoesAway() = runTest {
        val vm = viewModel()
        vm.pickAFile()
        vm.onPassphraseChange("secret")

        vm.onLeave()

        assertEquals("", vm.state.value.passphrase)
        assertEquals(null, vm.state.value.fileBase64)
    }

    @Test
    fun anUnreadableFileSaysSoRatherThanFailingSilently() = runTest {
        val vm = viewModel()

        vm.onFileUnreadable()

        assertIs<RestoreOutcome.FileUnreadable>(vm.state.value.outcome)
    }
}
