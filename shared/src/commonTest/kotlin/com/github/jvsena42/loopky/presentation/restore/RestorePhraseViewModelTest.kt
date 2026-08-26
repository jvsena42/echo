package com.github.jvsena42.loopky.presentation.restore

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class RestorePhraseViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = null)
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RestorePhraseViewModel(identityRepository = identityRepo)

    private fun TestScope.collectEffects(vm: RestorePhraseViewModel): List<RestoreEffect> {
        val effects = mutableListOf<RestoreEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun aChecksumValidTypoIsNeverCalledInvalidAndTheDerivedPubkyIsShown() = runTest {
        // The commonest reason anyone lands on this screen: one wrong word, or two transposed,
        // that still passes the BIP-39 checksum. The phrase is valid — it just is not theirs.
        // Telling them it is invalid sends them hunting for a problem that does not exist, and
        // showing the pubky is the one diagnosis only they can make.
        identityRepo.derivedPubky = Result.success("pkstranger")
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        val outcome = assertIs<RestoreOutcome.NoAccount>(vm.state.value.outcome)
        assertEquals("pkstranger", outcome.pubky)
    }

    @Test
    fun aDhtOutageOffersRetryAndNeverSaysTheAccountDoesNotExist() = runTest {
        // pkarr resolves over UDP, which plenty of networks drop while HTTP works fine. A confident
        // "this phrase belongs to no account" here would be a lie we have no basis for.
        identityRepo.homeserverLookup = HomeserverLookup.CouldNotCheck(ErrorReason.HomeserverLookupFailed)
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        val outcome = assertIs<RestoreOutcome.CouldNotCheck>(vm.state.value.outcome)
        assertEquals(ErrorReason.HomeserverLookupFailed, outcome.reason)
    }

    @Test
    fun noHomeserverLookupHappensUntilSubmitIsTapped() = runTest {
        // A DHT probe per completed phrase would make this screen an enumeration oracle for
        // "does this pubky exist", besides costing a round trip on every keystroke.
        val vm = viewModel()

        VALID_TEST_MNEMONIC.forEachIndexed { i, _ ->
            vm.onPhraseChange(VALID_TEST_MNEMONIC.take(i + 1))
        }
        advanceUntilIdle()

        assertEquals(0, identityRepo.lookupCount)
    }

    @Test
    fun nothingIsSignedInWhenThePreFlightSaysNoRecord() = runTest {
        // Signup must never happen implicitly from a failed restore: a typo'd phrase would strand
        // the real account behind words the user now believes are broken, and a wallet seed would
        // become a published public identity.
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(identityRepo.signInWithKeyCalls.isEmpty(), "no sign-in may be attempted")
    }

    @Test
    fun aRegisteredPubkySignsInAndGoesHome() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()
        val effects = collectEffects(vm)

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<KeySource.Phrase>(identityRepo.signInWithKeyCalls.single())
        assertEquals(listOf(RestoreEffect.NavigateHome), effects)
    }

    @Test
    fun aPhraseThatIsNotBip39IsReportedAsInvalidWithoutAskingTheNetwork() = runTest {
        // The honest failure. It also must not cost a DHT lookup — there is nothing to look up.
        identityRepo.derivedPubky = Result.failure(IllegalArgumentException("Invalid mnemonic phrase"))
        val vm = viewModel()

        vm.onPhraseChange("nonsense words")
        vm.onSubmit()
        advanceUntilIdle()

        assertIs<RestoreOutcome.InvalidPhrase>(vm.state.value.outcome)
        assertEquals(0, identityRepo.lookupCount)
    }

    @Test
    fun theSecretPhraseIsClearedFromStateOnceTheSessionExists() = runTest {
        // A StateFlow outlives the composable reading it, so the words would otherwise sit in
        // memory — and in any heap dump — for the life of the ViewModel.
        identityRepo.homeserverLookup = HomeserverLookup.Registered("homeserver-pubky")
        val vm = viewModel()

        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals("", vm.state.value.phrase)
    }

    @Test
    fun leavingTheScreenClearsThePhraseEvenWhenNothingWasSubmitted() = runTest {
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)

        vm.onLeave()

        assertEquals("", vm.state.value.phrase)
    }

    @Test
    fun editingThePhraseClearsTheLastOutcomeSoItIsNotReadAsAVerdictOnTheNewWords() = runTest {
        identityRepo.homeserverLookup = HomeserverLookup.NoRecord
        val vm = viewModel()
        vm.onPhraseChange(VALID_TEST_MNEMONIC)
        vm.onSubmit()
        advanceUntilIdle()

        vm.onPhraseChange("$VALID_TEST_MNEMONIC ")

        assertEquals(null, vm.state.value.outcome)
    }
}
