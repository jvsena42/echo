package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSignupRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSignupViewModelTest {

    private var signupRepo = FakeSignupRepository()
    private val identityRepo = FakeIdentityRepository(session = null)
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun redeemable() = PendingSignup.Redeemable(
        token = "token-123",
        homeserverPubky = "homeserver-z32",
        source = PendingSignup.Source.Invite,
    )

    private fun viewModel() = LocalSignupViewModel(
        signupRepository = signupRepo,
        identityRepository = identityRepo,
    )

    @Test
    fun theStoredTokenIsSpentRatherThanANewOneBeingMinted() = runTest {
        // By the time anyone reaches this screen they have spent an SMS attempt or paid sats.
        // Going back to Homegate would charge them a second time for the same account.
        signupRepo = FakeSignupRepository(redeemable())

        viewModel()
        advanceUntilIdle()

        assertEquals(listOf("homeserver-z32" to "token-123"), identityRepo.createLocalAccountCalls)
    }

    @Test
    fun theTokenIsClearedOnlyAfterTheAccountActuallyExists() = runTest {
        signupRepo = FakeSignupRepository(redeemable())

        viewModel()
        advanceUntilIdle()

        assertEquals(1, signupRepo.clearCount)
    }

    @Test
    fun aFailedRegistrationKeepsTheTokenSoTheRetryCanSpendIt() = runTest {
        // The token may never have been redeemed. Clearing it here would throw away something the
        // user paid for, in exactly the case where they still need it.
        signupRepo = FakeSignupRepository(redeemable())
        identityRepo.createLocalAccountResult = Result.failure(PubkyError("signup failure: 500"))

        viewModel()
        advanceUntilIdle()

        assertEquals(0, signupRepo.clearCount)
    }

    @Test
    fun aRetryRegistersTheSameKeyRatherThanMintingASecondIdentity() = runTest {
        // The repository stores the key before signUp runs, so the retry re-enters with the same
        // pubky. Minting again is the Pubky Ring failure mode this whole path exists to avoid.
        signupRepo = FakeSignupRepository(redeemable())
        identityRepo.createLocalAccountResult = Result.failure(PubkyError("signup failure: 500"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRetryClick()
        advanceUntilIdle()

        assertEquals(2, identityRepo.createLocalAccountCalls.size)
        assertTrue(identityRepo.createLocalAccountCalls.all { it == "homeserver-z32" to "token-123" })
    }

    @Test
    fun aMissingTokenIsReportedRatherThanQuietlyMintingAFreshOne() = runTest {
        signupRepo = FakeSignupRepository(null)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(SignupError.VerificationLost, vm.state.value.error)
        assertTrue(identityRepo.createLocalAccountCalls.isEmpty())
    }
}
