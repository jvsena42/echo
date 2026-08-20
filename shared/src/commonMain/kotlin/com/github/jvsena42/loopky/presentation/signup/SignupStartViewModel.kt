package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Prove you're not a robot" — pick how to obtain a signup token.
 *
 * Availability is a courtesy, not a gate. A method Homegate could not be asked about stays
 * **enabled**: locking someone out of the only route into the app because a probe timed out is
 * worse than letting that method's own screen explain itself.
 */
class SignupStartViewModel(
    private val signupRepository: SignupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SignupStartUiState())
    val state: StateFlow<SignupStartUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val availability = runSuspendCatching { signupRepository.availability() }
                .onFailure { Log.w(TAG, "load: availability failed — offering both anyway") }
                .getOrNull()
            _state.update {
                it.copy(
                    isLoading = false,
                    sms = availability?.sms ?: MethodAvailability.Unknown,
                    lightning = availability?.lightning ?: MethodAvailability.Unknown,
                )
            }
        }
    }

    private companion object {
        const val TAG = "Loopky/SignupStartVM"
    }
}

data class SignupStartUiState(
    val isLoading: Boolean = true,
    val sms: MethodAvailability = MethodAvailability.Unknown,
    val lightning: MethodAvailability = MethodAvailability.Unknown,
) {
    /** Disabled only when Homegate positively said no — never merely because we could not ask. */
    val isSmsEnabled: Boolean get() = sms !is MethodAvailability.Unavailable
    val isLightningEnabled: Boolean get() = lightning !is MethodAvailability.Unavailable

    /** Sats for the Lightning route, when known. */
    val lightningPriceSat: Long? get() = (lightning as? MethodAvailability.Available)?.priceSat
}
