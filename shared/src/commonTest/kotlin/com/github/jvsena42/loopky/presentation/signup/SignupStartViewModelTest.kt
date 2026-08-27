package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.repository.SignupAvailability
import com.github.jvsena42.loopky.testing.FakePriceSource
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These are all about one thing: nothing may stand between a user and the only way into the app.
 *
 * The screen used to gate every method on Pubky Ring being installed, because Ring was the one
 * thing that could spend the token. Loopky redeems it itself now, so the only reason a method is
 * ever disabled is Homegate positively saying no.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignupStartViewModelTest {

    private val signupRepo = FakeSignupRepository()
    private val priceSource = FakePriceSource()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SignupStartViewModel(
        signupRepository = signupRepo,
        priceSource = priceSource,
    )

    @Test
    fun availabilityIsFetchedOnceOnEntry() = runTest {
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Unavailable,
            lightning = MethodAvailability.Available(priceSat = 500),
        )

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, signupRepo.availabilityCount)
        assertFalse(vm.state.value.isSmsEnabled, "Homegate positively said no to SMS")
        assertTrue(vm.state.value.isLightningEnabled)
        assertEquals(500, vm.state.value.lightningPriceSat)
    }

    @Test
    fun aMethodHomegateCouldNotBeAskedAboutStaysEnabled() = runTest {
        // Availability is a courtesy, not a gate. Locking someone out of the only route into the
        // app because a probe timed out is worse than letting that method's screen explain itself.
        signupRepo.availabilityError = IllegalStateException("Homegate unreachable")

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.isSmsEnabled)
        assertTrue(vm.state.value.isLightningEnabled)
    }

    @Test
    fun aFailedRateLookupLeavesTheSatsOnlyPriceInPlace() = runTest {
        // The path that actually runs for anyone offline or geoblocked, and the one that would
        // regress in silence. No rate must mean no fiat string at all — never "$0.00", never a
        // spinner, never an error.
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Available(),
            lightning = MethodAvailability.Available(priceSat = 2_000),
        )
        priceSource.rate = null

        val vm = viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.fiatPrice)
        assertEquals(2_000L, vm.state.value.lightningPriceSat)
    }

    @Test
    fun aRateIsFormattedOnceIntoTheStateRatherThanInTheComposable() = runTest {
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Available(),
            lightning = MethodAvailability.Available(priceSat = 2_000),
        )
        priceSource.rate = 100_000.0

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("≈ US\$2.00", vm.state.value.fiatPrice)
    }

    @Test
    fun theLightningMethodStaysEnabledWhileTheQuoteIsUnavailable() = runTest {
        // This is the screen where someone is trying to get *into* the app. The sats figure is the
        // price; the dollar figure is a courtesy, and a courtesy must never gate a CTA.
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Available(),
            lightning = MethodAvailability.Available(priceSat = 2_000),
        )
        priceSource.rate = null

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.isLightningEnabled)
    }

    @Test
    fun noRateIsFetchedWhenHomegateQuotedNoPrice() = runTest {
        // Keeps the third-party request on the Lightning branch rather than making it for
        // everyone who opens onboarding.
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Available(),
            lightning = MethodAvailability.Unknown,
        )

        viewModel()
        advanceUntilIdle()

        assertEquals(0, priceSource.calls)
    }
}
