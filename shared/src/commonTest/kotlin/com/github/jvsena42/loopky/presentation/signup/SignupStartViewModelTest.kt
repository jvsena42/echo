package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.repository.SignupAvailability
import com.github.jvsena42.loopky.testing.FakePriceSource
import com.github.jvsena42.loopky.testing.FakePubkyRingPresence
import com.github.jvsena42.loopky.testing.FakeSignupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
 * These are all about one thing: never minting a token the device cannot spend. A token costs an
 * SMS attempt or sats, is single-use, and only Pubky Ring can redeem it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignupStartViewModelTest {

    private val signupRepo = FakeSignupRepository()
    private val ring = FakePubkyRingPresence()
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
        ringPresence = ring,
        priceSource = priceSource,
    )

    @Test
    fun homegateIsNeverAskedAnythingWhileRingIsMissing() = runTest {
        ring.installed = false

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(0, signupRepo.availabilityCount, "availability is a Homegate call — it must wait for Ring")
        assertFalse(vm.state.value.isRingInstalled)
    }

    @Test
    fun everyMethodIsDisabledWhileRingIsMissing() = runTest {
        ring.installed = false
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Available(priceSat = 0),
            lightning = MethodAvailability.Available(priceSat = 500),
        )

        val vm = viewModel()
        advanceUntilIdle()

        // Not merely cosmetic: each of these leads to a screen that mints a token on entry.
        assertFalse(vm.state.value.isSmsEnabled)
        assertFalse(vm.state.value.isLightningEnabled)
    }

    @Test
    fun availabilityIsFetchedOnceRingIsThere() = runTest {
        signupRepo.availability = SignupAvailability(
            sms = MethodAvailability.Unavailable,
            lightning = MethodAvailability.Available(priceSat = 500),
        )

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, signupRepo.availabilityCount)
        assertTrue(vm.state.value.isRingInstalled)
        assertFalse(vm.state.value.isSmsEnabled, "Homegate positively said no to SMS")
        assertTrue(vm.state.value.isLightningEnabled)
        assertEquals(500, vm.state.value.lightningPriceSat)
    }

    @Test
    fun installingRingAndComingBackUnblocksTheFlow() = runTest {
        ring.installed = false
        val vm = viewModel()
        advanceUntilIdle()

        ring.installed = true
        vm.onScreenResumed()
        advanceUntilIdle()

        assertTrue(vm.state.value.isRingInstalled)
        assertEquals(1, signupRepo.availabilityCount, "the gate lifting is what releases the first Homegate call")
    }

    @Test
    fun aResumeWithRingAlreadyThereDoesNotReAskHomegate() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onScreenResumed()
        vm.onScreenResumed()
        advanceUntilIdle()

        assertEquals(1, signupRepo.availabilityCount, "one request per entry, not one per resume")
    }

    @Test
    fun theInstallActionCarriesThePlatformsOwnUrl() = runTest {
        ring.installed = false
        ring.installUrl = "https://play.google.com/store/apps/details?id=to.pubky.ring"
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<SignupStartEffect>()
        val collector = launch { vm.effects.toList(effects) }

        vm.onInstallRingClick()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(
            listOf<SignupStartEffect>(SignupStartEffect.OpenInstallPage(ring.installUrl)),
            effects,
        )
    }

    @Test
    fun theLoopkyRedeemerAsksHomegateEvenWithNoRingInstalled() = runTest {
        // The gate follows the *spender*, not the flow. Loopky redeems with signUp(secretKey, …)
        // and needs nothing installed, so refusing to even quote a price would block a token this
        // device can spend perfectly well by itself (#147).
        ring.installed = false
        val vm = SignupStartViewModel(
            signupRepository = signupRepo,
            ringPresence = ring,
            redeemer = TokenRedeemer.Loopky,
            priceSource = priceSource,
        )
        advanceUntilIdle()

        assertEquals(1, signupRepo.availabilityCount)
    }

    @Test
    fun everyMethodStaysEnabledForTheLoopkyRedeemerWithoutRing() = runTest {
        ring.installed = false
        val vm = SignupStartViewModel(
            signupRepository = signupRepo,
            ringPresence = ring,
            redeemer = TokenRedeemer.Loopky,
            priceSource = priceSource,
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.isSmsEnabled)
        assertTrue(vm.state.value.isLightningEnabled)
    }

    @Test
    fun anUnknownRedeemerArgumentFallsBackToRingRatherThanToLocalKeys() = runTest {
        // A typo in a deeplink must not silently choose the path that puts a secret key on the
        // device. Ring is the recommendation, so it is the default.
        assertEquals(TokenRedeemer.PubkyRing, TokenRedeemer.fromNameOrRing("nonsense"))
        assertEquals(TokenRedeemer.PubkyRing, TokenRedeemer.fromNameOrRing(null))
        assertEquals(TokenRedeemer.Loopky, TokenRedeemer.fromNameOrRing("loopky"))
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
