package com.github.jvsena42.loopky.presentation.signup

import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.storage.PendingSignup
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
 * The one distinction this screen has to keep straight: paying an invoice it can show, versus
 * waiting on one it cannot. A resumed invoice has no BOLT11 — copy that keeps asking for payment
 * there invites a second payment for a token the user may already own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightningVerificationViewModelTest {

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

    private fun viewModel() = LightningVerificationViewModel(
        signupRepository = signupRepo,
        priceSource = priceSource,
    )

    /** A payment that goes through, so the await settles without an error masking what is asserted. */
    private fun grantOnPayment() {
        signupRepo.redeemResult = Result.success(
            PendingSignup.Redeemable(
                token = "token",
                homeserverPubky = "homeserver",
                source = PendingSignup.Source.Lightning,
            ),
        )
    }

    @Test
    fun aResumedInvoiceIsCheckingAnEarlierPayment() = runTest {
        grantOnPayment()
        signupRepo.resumableInvoice = LnInvoice(
            id = "verification-1",
            bolt11 = "",
            amountSat = 10,
            expiresAtMillis = 0,
        )

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isResumed)
        assertTrue(state.isCheckingEarlierPayment)
        // No second invoice was issued. The fake fails createInvoice unless one is set, so an
        // error here would mean the resumed claim had been thrown away and re-minted.
        assertNull(state.error)
    }

    @Test
    fun aFreshInvoiceIsNotCheckingAnEarlierPayment() = runTest {
        grantOnPayment()
        signupRepo.invoiceResult = Result.success(
            LnInvoice(
                id = "verification-2",
                bolt11 = "lnbc100n1p4fqupy",
                amountSat = 10,
                expiresAtMillis = 0,
            ),
        )

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isResumed)
        assertFalse(state.isCheckingEarlierPayment)
        assertEquals("lnbc100n1p4fqupy", state.invoice?.bolt11)
    }

    @Test
    fun anErrorOnAResumedInvoiceOffersARetryRatherThanKeepingTheCheckingCopy() = runTest {
        signupRepo.resumableInvoice = LnInvoice(
            id = "verification-3",
            bolt11 = "",
            amountSat = 10,
            expiresAtMillis = 0,
        )
        // awaitInvoice mints, and the fake fails that without a redeem result set.
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.canRetry)
        // "Confirming with the server…" over an error block would be a lie about a dead wait.
        assertFalse(state.isCheckingEarlierPayment)
    }
}
