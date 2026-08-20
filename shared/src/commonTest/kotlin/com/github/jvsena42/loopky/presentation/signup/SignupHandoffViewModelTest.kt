package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSignupRepository
import com.github.jvsena42.loopky.testing.fakeSession
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * These are all about one thing: never losing a token the user paid for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignupHandoffViewModelTest {

    private val pending = PendingSignup.Redeemable(
        token = "ABCD-1234-EFGH",
        homeserverPubky = "homeserverpk",
        source = PendingSignup.Source.Lightning,
    )

    private val signupRepo = FakeSignupRepository(pending = pending)
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

    private fun viewModel() = SignupHandoffViewModel(
        signupRepository = signupRepo,
        identityRepository = identityRepo,
    )

    @Test
    fun theStoredTokenIsSpentAgainstTheHomeserverItWasIssuedFor() = runTest {
        // Not the configured environment: switching environments mid-flow must not misdirect a
        // token that already exists, because spending it on the wrong server destroys it.
        identityRepo.completionResult = Result.success(fakeSession().copy(homeserver = "homeserverpk"))
        viewModel()

        advanceUntilIdle()

        assertEquals("homeserverpk" to "ABCD-1234-EFGH", identityRepo.signUpCalls.single())
    }

    @Test
    fun retryReusesTheSameTokenAndNeverMintsANewOne() = runTest {
        // Ring keys the pubky it minted off the token, so re-sending the same one reuses that key.
        // Going back to the gate would create a second identity and charge the user twice.
        identityRepo.completionResult = Result.failure(IllegalStateException("relay dropped"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRetryClick()
        advanceUntilIdle()

        assertEquals(2, identityRepo.signUpCalls.size)
        assertEquals(identityRepo.signUpCalls[0], identityRepo.signUpCalls[1])
        assertEquals(0, signupRepo.mintCount, "a retry must not go back to the gate")
    }

    @Test
    fun aFailedHandoffKeepsTheToken() = runTest {
        identityRepo.completionResult = Result.failure(IllegalStateException("relay dropped"))
        viewModel()

        advanceUntilIdle()

        assertNotNull(signupRepo.stored, "the user still owns this token")
        assertEquals(0, signupRepo.clearCount)
    }

    @Test
    fun aSessionOnTheExpectedHomeserverClearsTheToken() = runTest {
        identityRepo.completionResult = Result.success(fakeSession().copy(homeserver = "homeserverpk"))
        viewModel()

        advanceUntilIdle()

        assertNull(signupRepo.stored)
        assertEquals(1, signupRepo.clearCount)
    }

    @Test
    fun aSessionOnADifferentHomeserverKeepsTheToken() = runTest {
        // Ring falls back to authorising an existing pubky when signup fails, which returns a
        // perfectly valid session while the token is still unspent. Clearing here would throw
        // away something the user paid for, in exactly the case where they still need it.
        identityRepo.completionResult = Result.success(fakeSession().copy(homeserver = "someone-elses-hs"))
        viewModel()

        advanceUntilIdle()

        assertNotNull(signupRepo.stored)
        assertEquals(0, signupRepo.clearCount)
    }

    @Test
    fun theTokenIsExposedSoTheUserCanWriteItDown() = runTest {
        // A token the user can read cannot be lost to a bug in any of the above.
        identityRepo.completionResult = Result.failure(IllegalStateException("relay dropped"))
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals("ABCD-1234-EFGH", vm.state.value.token)
    }
}
