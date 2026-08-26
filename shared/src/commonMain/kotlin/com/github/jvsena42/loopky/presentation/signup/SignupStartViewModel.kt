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
 * - **Pubky Ring is a hard gate for the Ring redeemer, checked first.** A token redeemed through
 *   Ring can only be spent with Ring installed, and getting one costs an SMS attempt (two per
 *   week) or sats. So nothing is asked of Homegate — not even [SignupRepository.availability] —
 *   until Ring is on the device. The alternative is charging someone for a token they cannot spend
 *   and telling them so afterwards.
 *
 *   It is **not** a gate for [TokenRedeemer.Loopky], which redeems with `signUp(secretKey, …)` and
 *   needs nothing installed. Same token, same cost, different spender — so the check follows the
 *   spender rather than the flow. Ring stays the default and the recommendation (#147).
 * - **Method availability is a courtesy, not a gate.** A method Homegate could not be asked about
 *   stays **enabled**: locking someone out of the only route into the app because a probe timed
 *   out is worse than letting that method's own screen explain itself.
 */
class SignupStartViewModel(
    private val signupRepository: SignupRepository,
    private val ringPresence: PubkyRingPresence,
    /**
     * Who will spend the token this flow produces. Defaults to Ring, so every existing caller —
     * and every existing test — keeps the behaviour it had.
     */
    private val redeemer: TokenRedeemer = TokenRedeemer.PubkyRing,
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

            // Only the Ring redeemer needs Ring. Gating the Loopky one on it would refuse to
            // quote a price for a token this device can spend perfectly well by itself.
            if (redeemer == TokenRedeemer.PubkyRing && !ringPresence.isInstalled()) {
                Log.w(TAG, "load: Pubky Ring is not installed — not minting a token that cannot be spent")
                _state.update { it.copy(isLoading = false, isRingInstalled = false, hasRedeemer = false) }
                return@launch
            }

            val availability = runSuspendCatching { signupRepository.availability() }
                .onFailure { Log.w(TAG, "load: availability failed — offering both anyway") }
                .getOrNull()
            _state.update {
                it.copy(
                    isLoading = false,
                    isRingInstalled = true,
                    hasRedeemer = true,
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

    /** Which spender this flow was entered for, so the screen can route its "done" step. */
    val tokenRedeemer: TokenRedeemer get() = redeemer

    fun onInstallRingClick() {
        viewModelScope.launch { _effects.emit(SignupStartEffect.OpenInstallPage(ringPresence.installUrl)) }
    }

    private companion object {
        const val TAG = "Loopky/SignupStartVM"
    }
}

/** Who spends the signup token this flow produces. */
enum class TokenRedeemer {
    /** Pubky Ring mints the key and redeems the token; Loopky never holds a secret key. */
    PubkyRing,

    /** Loopky mints the key and redeems the token itself with `signUp(secretKey, …)`. */
    Loopky,
    ;

    companion object {
        /** Parse a nav argument, defaulting to Ring — the recommended path — for anything unknown. */
        fun fromNameOrRing(name: String?): TokenRedeemer =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PubkyRing
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
    /**
     * Whether a spender for the token exists on this device at all.
     *
     * Always true for [TokenRedeemer.Loopky]: it is its own spender.
     */
    val hasRedeemer: Boolean = true,
) {
    /** Disabled only when Homegate positively said no — never merely because we could not ask. */
    val isSmsEnabled: Boolean get() = hasRedeemer && sms !is MethodAvailability.Unavailable
    val isLightningEnabled: Boolean get() = hasRedeemer && lightning !is MethodAvailability.Unavailable

    /** Sats for the Lightning route, when known. */
    val lightningPriceSat: Long? get() = (lightning as? MethodAvailability.Available)?.priceSat
}

sealed interface SignupStartEffect {
    /** Send the user to the store listing / landing page for Pubky Ring. */
    data class OpenInstallPage(val url: String) : SignupStartEffect
}
