package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.price.PriceSource
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.domain.model.formatSatsAsUsd
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pay a small Lightning invoice for a signup token.
 *
 * The invoice is offered three ways, because the wallet may not be on this device: as a QR to
 * scan from another phone, as copyable text, and as a `lightning:` link that opens whatever wallet
 * is installed here — the last being fewer taps than scanning your own screen when it applies.
 */
class LightningVerificationViewModel(
    private val signupRepository: SignupRepository,
    private val priceSource: PriceSource,
) : ViewModel() {
    private val _state = MutableStateFlow(LightningVerificationUiState())
    val state: StateFlow<LightningVerificationUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LightningVerificationEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<LightningVerificationEffect> = _effects.asSharedFlow()

    private var awaitJob: Job? = null

    init {
        start()
    }

    /**
     * Resume an outstanding invoice if there is one, otherwise ask for a new one.
     *
     * Paying happens in a *different app*, so Loopky is backgrounded — and may be killed — for the
     * whole of it. Issuing a second invoice on return would leave a payment already made with
     * nothing listening for it.
     */
    private fun start() {
        awaitJob?.cancel()
        awaitJob = viewModelScope.launch {
            val resumed = runSuspendCatching { signupRepository.resumableInvoice() }.getOrNull()
            if (resumed != null) {
                Log.d(TAG, "start: resuming an invoice that may already have been paid")
                _state.update { it.copy(isLoading = false, invoice = resumed, isAwaitingPayment = true, isResumed = true) }
                // A resumed invoice is the commitment point too, so it gets a fresh quote rather
                // than none — and a fresh one, not a cached stale figure.
                loadFiatQuote(resumed)
                awaitPayment(resumed)
            } else {
                createInvoice()
            }
        }
    }

    fun createInvoice() {
        awaitJob?.cancel()
        awaitJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, invoice = null, isResumed = false) }
            val invoice = signupRepository.createInvoice().getOrElse { err ->
                Log.e(TAG, "createInvoice: FAILED — ${err.message}", err)
                _state.update { it.copy(isLoading = false, error = err.toSignupError()) }
                return@launch
            }
            _state.update { it.copy(isLoading = false, invoice = invoice, isAwaitingPayment = true) }
            loadFiatQuote(invoice)
            awaitPayment(invoice)
        }
    }

    /**
     * Quote the invoice in dollars, once per invoice.
     *
     * Explicitly *not* driven by the expiry countdown, which ticks every second — that would be a
     * network request per second and a money format per frame.
     */
    private fun loadFiatQuote(invoice: LnInvoice) {
        viewModelScope.launch {
            val rate = priceSource.usdPerBtc()
            _state.update { it.copy(fiatPrice = formatSatsAsUsd(invoice.amountSat, rate)) }
        }
    }

    /** The long-lived poll. On viewModelScope, so leaving the screen cancels it at the next
     *  iteration boundary rather than leaving a socket parked. */
    private suspend fun awaitPayment(invoice: LnInvoice) {
        run {
            signupRepository.awaitInvoice(invoice)
                .onSuccess {
                    _state.update { it.copy(isAwaitingPayment = false) }
                    _effects.emit(LightningVerificationEffect.NavigateToHandoff)
                }
                .onFailure { err ->
                    Log.e(TAG, "awaitInvoice: FAILED — ${err.message}", err)
                    _state.update {
                        it.copy(isAwaitingPayment = false, error = err.toSignupError())
                    }
                }
        }
    }

    fun onCopyInvoiceClick() {
        val bolt11 = _state.value.invoice?.bolt11 ?: return
        viewModelScope.launch { _effects.emit(LightningVerificationEffect.CopyToClipboard(bolt11)) }
    }

    fun onOpenWalletClick() {
        val bolt11 = _state.value.invoice?.bolt11 ?: return
        viewModelScope.launch { _effects.emit(LightningVerificationEffect.OpenWallet("lightning:$bolt11")) }
    }

    private companion object {
        const val TAG = "Loopky/LightningVerificationVM"
    }
}

data class LightningVerificationUiState(
    val isLoading: Boolean = true,
    val invoice: LnInvoice? = null,
    val isAwaitingPayment: Boolean = false,
    /** True when this invoice was picked up again after the app was killed mid-payment. */
    val isResumed: Boolean = false,
    val error: SignupError? = null,
    /** Pre-formatted dollar approximation, or null — see [SignupStartUiState.fiatPrice]. */
    val fiatPrice: String? = null,
) {
    /** An expired invoice is recoverable by asking for another, so the screen offers exactly that. */
    val canRetry: Boolean get() = error != null
}

sealed interface LightningVerificationEffect {
    data class CopyToClipboard(val text: String) : LightningVerificationEffect
    data class OpenWallet(val uri: String) : LightningVerificationEffect
    data object NavigateToHandoff : LightningVerificationEffect
}
