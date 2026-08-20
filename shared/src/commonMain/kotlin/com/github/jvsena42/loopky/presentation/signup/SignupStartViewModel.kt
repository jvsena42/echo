package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.platform.PubkyRingPresence
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Prove you're not a robot" — pick how to obtain a signup token.
 *
 * Two gates, and they are deliberately opposite:
 *
 * - **Pubky Ring is a hard gate, checked first.** Every method here ends in a token that only Ring
 *   can redeem, and getting one costs an SMS attempt (two per week) or sats. So nothing is asked
 *   of Homegate — not even [SignupRepository.availability] — until Ring is on the device. The
 *   alternative is charging someone for a token they cannot spend and telling them so afterwards.
 * - **Method availability is a courtesy, not a gate.** A method Homegate could not be asked about
 *   stays **enabled**: locking someone out of the only route into the app because a probe timed
 *   out is worse than letting that method's own screen explain itself.
 */
class SignupStartViewModel(
    private val signupRepository: SignupRepository,
    private val ringPresence: PubkyRingPresence,
) : ViewModel() {
    private val _state = MutableStateFlow(SignupStartUiState())
    val state: StateFlow<SignupStartUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SignupStartEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SignupStartEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            if (!ringPresence.isInstalled()) {
                Log.w(TAG, "load: Pubky Ring is not installed — not minting a token that cannot be spent")
                _state.update { it.copy(isLoading = false, isRingInstalled = false) }
                return@launch
            }

            val availability = runSuspendCatching { signupRepository.availability() }
                .onFailure { Log.w(TAG, "load: availability failed — offering both anyway") }
                .getOrNull()
            _state.update {
                it.copy(
                    isLoading = false,
                    isRingInstalled = true,
                    sms = availability?.sms ?: MethodAvailability.Unknown,
                    lightning = availability?.lightning ?: MethodAvailability.Unknown,
                )
            }
        }
    }

    /**
     * Re-checks the Ring gate when the screen comes back to the foreground, so a user who left to
     * install Ring returns to a usable screen rather than a stale block.
     *
     * Deliberately not an unconditional [load]: installing happens in another app, but so does
     * every other reason this screen gets backgrounded, and asking Homegate again each time would
     * be a request per resume.
     */
    fun onScreenResumed() {
        if (_state.value.isRingInstalled) return
        load()
    }

    fun onInstallRingClick() {
        viewModelScope.launch { _effects.emit(SignupStartEffect.OpenInstallPage(ringPresence.installUrl)) }
    }

    private companion object {
        const val TAG = "Loopky/SignupStartVM"
    }
}

data class SignupStartUiState(
    val isLoading: Boolean = true,
    /**
     * Assumed true until the check says otherwise: the overwhelmingly common case is that Ring is
     * there, and starting at false would flash an install prompt at every user on every entry.
     */
    val isRingInstalled: Boolean = true,
    val sms: MethodAvailability = MethodAvailability.Unknown,
    val lightning: MethodAvailability = MethodAvailability.Unknown,
) {
    /** Disabled only when Homegate positively said no — never merely because we could not ask. */
    val isSmsEnabled: Boolean get() = isRingInstalled && sms !is MethodAvailability.Unavailable
    val isLightningEnabled: Boolean get() = isRingInstalled && lightning !is MethodAvailability.Unavailable

    /** Sats for the Lightning route, when known. */
    val lightningPriceSat: Long? get() = (lightning as? MethodAvailability.Available)?.priceSat
}

sealed interface SignupStartEffect {
    /** Send the user to the store listing / landing page for Pubky Ring. */
    data class OpenInstallPage(val url: String) : SignupStartEffect
}
