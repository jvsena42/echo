package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SMS verification, both phases on one screen.
 *
 * Deliberately **one ViewModel across two phases** rather than a route each: going back from the
 * code field has to return to the number the user already typed, and the resend cooldown belongs
 * to the same object that started it. Two routes would mean two instances and a lost number.
 */
class PhoneVerificationViewModel(
    private val signupRepository: SignupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PhoneVerificationUiState())
    val state: StateFlow<PhoneVerificationUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PhoneVerificationEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PhoneVerificationEffect> = _effects.asSharedFlow()

    private var sendJob: Job? = null
    private var verifyJob: Job? = null
    private var cooldownJob: Job? = null

    fun onPhoneNumberChange(value: String) {
        _state.update { it.copy(phoneNumber = value, error = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value, error = null) }
    }

    fun onSendCodeClick() {
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            signupRepository.sendSmsCode(_state.value.phoneNumber)
                .onSuccess {
                    _state.update { it.copy(isWorking = false, phase = PhoneVerificationPhase.CodeEntry) }
                    startCooldown()
                }
                .onFailure { err ->
                    Log.e(TAG, "onSendCodeClick: FAILED — ${err.message}", err)
                    _state.update { it.copy(isWorking = false, error = err.toSignupError()) }
                }
        }
    }

    /** Back from the code field — the number is kept, which is the whole point of one VM. */
    fun onBackToNumber() {
        _state.update { it.copy(phase = PhoneVerificationPhase.NumberEntry, code = "", error = null) }
    }

    fun onVerifyClick() {
        if (verifyJob?.isActive == true) return
        verifyJob = viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(isWorking = true, error = null) }
            signupRepository.redeemSmsCode(current.phoneNumber, current.code)
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                    _effects.emit(PhoneVerificationEffect.NavigateToHandoff)
                }
                .onFailure { err ->
                    Log.e(TAG, "onVerifyClick: FAILED — ${err.message}", err)
                    _state.update { it.copy(isWorking = false, error = err.toSignupError()) }
                }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var remaining = RESEND_COOLDOWN_SECONDS
            while (remaining > 0) {
                _state.update { it.copy(resendCooldownSeconds = remaining) }
                delay(ONE_SECOND_MS)
                remaining--
            }
            _state.update { it.copy(resendCooldownSeconds = 0) }
        }
    }

    private companion object {
        const val TAG = "Loopky/PhoneVerificationVM"
        const val RESEND_COOLDOWN_SECONDS = 60
        const val ONE_SECOND_MS = 1000L
    }
}

enum class PhoneVerificationPhase { NumberEntry, CodeEntry }

data class PhoneVerificationUiState(
    val phase: PhoneVerificationPhase = PhoneVerificationPhase.NumberEntry,
    val phoneNumber: String = "",
    val code: String = "",
    val isWorking: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val error: SignupError? = null,
) {
    val canSendCode: Boolean get() = phoneNumber.isNotBlank() && !isWorking
    val canVerify: Boolean get() = code.isNotBlank() && !isWorking
    val canResend: Boolean get() = resendCooldownSeconds == 0 && !isWorking

    /**
     * A weekly or yearly limit is terminal for this number — offering "resend" there would invite
     * the user to burn attempts they no longer have.
     */
    val isTerminal: Boolean
        get() = error == SignupError.RateLimitedWeekly ||
            error == SignupError.RateLimitedYearly ||
            error == SignupError.PhoneBlocked
}

sealed interface PhoneVerificationEffect {
    data object NavigateToHandoff : PhoneVerificationEffect
}
