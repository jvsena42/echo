package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.util.Log
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
 * No QR is rendered. On a phone the wallet is on the same device, so the invoice is offered as
 * copyable text and a `lightning:` link that opens whatever wallet is installed — which is both
 * fewer taps than scanning your own screen and one less dependency. Paying from a second device
 * would want a QR, and that can follow.
 */
class LightningVerificationViewModel(
    private val signupRepository: SignupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LightningVerificationUiState())
    val state: StateFlow<LightningVerificationUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LightningVerificationEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<LightningVerificationEffect> = _effects.asSharedFlow()

    private var awaitJob: Job? = null

    init {
        createInvoice()
    }

    fun createInvoice() {
        awaitJob?.cancel()
        awaitJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, invoice = null) }
            val invoice = signupRepository.createInvoice().getOrElse { err ->
                Log.e(TAG, "createInvoice: FAILED — ${err.message}", err)
                _state.update { it.copy(isLoading = false, error = err.toSignupError()) }
                return@launch
            }
            _state.update { it.copy(isLoading = false, invoice = invoice, isAwaitingPayment = true) }

            // The await is a long-lived poll living on viewModelScope, so leaving the screen
            // cancels it at the next iteration boundary rather than leaving a socket parked.
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
    val error: SignupError? = null,
) {
    /** An expired invoice is recoverable by asking for another, so the screen offers exactly that. */
    val canRetry: Boolean get() = error != null
}

sealed interface LightningVerificationEffect {
    data class CopyToClipboard(val text: String) : LightningVerificationEffect
    data class OpenWallet(val uri: String) : LightningVerificationEffect
    data object NavigateToHandoff : LightningVerificationEffect
}
