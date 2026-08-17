package com.github.jvsena42.loopky.presentation.onboarding

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.fakeSession
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
class OnboardingViewModelTest {

    /** Signed out, so `init` leaves the VM on the sign-in screen instead of navigating home. */
    private val identityRepo = FakeIdentityRepository(session = null)

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(identityRepository = identityRepo)

    /** Subscribes eagerly so the deeplink effect emitted during sign-in is not dropped. */
    private fun TestScope.collectEffects(vm: OnboardingViewModel): List<OnboardingEffect> {
        val effects = mutableListOf<OnboardingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun anUnreachableRelayIsNamedInsteadOfBlamingPubkyRing() = runTest {
        // The relay is a different host from the homeserver — "you're offline" would send the user
        // to check a connection that is fine, and "couldn't confirm with Ring" blames an app that
        // never saw the request (#59).
        identityRepo.completionResult = Result.failure(
            PubkyError(
                "Auth approval failed: Request failed: HTTP transport error: error sending " +
                    "request for url (https://httprelay.pubky.app/inbox/j42EOsZz)",
            ),
        )
        val vm = viewModel()

        vm.onSignInClick()
        advanceUntilIdle()

        val state = assertIs<OnboardingUiState.Error>(vm.state.value)
        assertEquals(ErrorReason.AuthRelayUnreachable, state.reason)
    }

    @Test
    fun anUnclassifiableApprovalFailureIsStillASignInFailure() = runTest {
        identityRepo.completionResult = Result.failure(PubkyError("auth request was declined"))
        val vm = viewModel()

        vm.onSignInClick()
        advanceUntilIdle()

        val state = assertIs<OnboardingUiState.Error>(vm.state.value)
        assertEquals(ErrorReason.AuthFailed, state.reason)
    }

    @Test
    fun approvingOpensRingAndThenNavigatesHome() = runTest {
        identityRepo.completionResult = Result.success(fakeSession())
        val vm = viewModel()
        val effects = collectEffects(vm)

        vm.onSignInClick()
        advanceUntilIdle()

        assertIs<OnboardingUiState.Success>(vm.state.value)
        assertEquals(identityRepo.authUrl, (effects.first() as OnboardingEffect.OpenDeeplink).url)
        assertTrue(effects.contains(OnboardingEffect.NavigateHome))
    }

    @Test
    fun signingInAgainAfterAFailureStartsAFreshFlow() = runTest {
        // The FFI auth flow is single-use, so the only recovery is another `beginSignIn`.
        identityRepo.completionResult = Result.failure(PubkyError("No auth flow in progress"))
        val vm = viewModel()

        vm.onSignInClick()
        advanceUntilIdle()
        vm.onSignInClick()
        advanceUntilIdle()

        assertEquals(expected = 2, actual = identityRepo.beginSignInCount)
    }
}
