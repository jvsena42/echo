package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Redeem a signup token **in Loopky**, minting the key here rather than handing it to Pubky Ring.
 *
 * The sibling of [SignupHandoffViewModel]: same stored token, same "a session is not proof"
 * discipline, no deeplink. Everything before this step — the human check and its three methods —
 * is the identical flow, which is why those ViewModels are untouched.
 *
 * Three rules carried over from the Ring path, for the same reasons:
 *
 * 1. **Reuse the stored token; never mint a second one.** By the time anyone is here the user has
 *    spent an SMS attempt or paid sats. [onRetryClick] re-reads what is stored rather than going
 *    back to Homegate, which would charge them twice.
 * 2. **A session is not proof the token was spent on the right key.** The repository asserts the
 *    returned pubky matches the key it registered; only then is the token cleared.
 * 3. **The key is stored before `signUp` runs**, so a failure part-way leaves the same key to
 *    retry with rather than minting a second identity and stranding the first.
 */
class LocalSignupViewModel(
    private val signupRepository: SignupRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalSignupUiState())
    val state: StateFlow<LocalSignupUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LocalSignupEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<LocalSignupEffect> = _effects.asSharedFlow()

    private var job: Job? = null

    init {
        start()
    }

    /**
     * Retry after a failed registration.
     *
     * Plain re-entry: `createLocalAccount` reuses an unregistered key already on the device and
     * mints only when there is none. That covers both shapes this can take — a `signUp` that
     * failed *after* the key was stored (register that same pubky) and one that failed *before* it
     * (mint, because nothing was kept). An earlier version forced the register path here and
     * deadlocked the second shape: every retry hit "No local key to register" with no way out but
     * backing out of the screen.
     */
    fun onRetryClick() = start()

    private fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }

            val pending = runSuspendCatching { signupRepository.pending.first() }.getOrNull()
                as? PendingSignup.Redeemable
            if (pending == null) {
                // The token is gone or was never stored. Minting a fresh one here would charge the
                // user a second time for something they may already have paid for.
                Log.w(TAG, "start: no redeemable token")
                _state.update { it.copy(isWorking = false, error = SignupError.VerificationLost) }
                return@launch
            }

            val registration = identityRepository.createLocalAccount(
                homeserverPubky = pending.homeserverPubky,
                signupToken = pending.token,
            )

            registration
                .onSuccess { account ->
                    // Only now: the repository has already asserted that the pubky we registered is
                    // the pubky that came back.
                    runSuspendCatching { signupRepository.clearPending() }
                        .onFailure { Log.w(TAG, "start: could not clear the spent token — ${it.message}") }

                    _state.update {
                        it.copy(isWorking = false, error = null, pubky = account.pubky, isComplete = true)
                    }
                    _effects.emit(LocalSignupEffect.NavigateBackup)
                }
                .onFailure { error ->
                    // Class name only. The repository below deliberately withholds the message
                    // because `toResult` wraps the FFI string verbatim and these calls were handed
                    // a mnemonic or a secret key — re-logging it here undid that one layer up.
                    Log.e(TAG, "start: FAILED — ${error::class.simpleName}")
                    // The token is deliberately kept: if signUp never landed it is still spendable,
                    // and the key is already stored so a retry registers the same pubky.
                    _state.update { it.copy(isWorking = false, error = error.toSignupError()) }
                }
        }
    }

    /**
     * Drop a refused token and start again.
     *
     * Only offered for [SignupError.TokenRejected]. The token is cleared *here* rather than on the
     * failure itself, so a user who backgrounds the app mid-error still has it if it turns out to
     * have been usable after all.
     */
    fun onStartOverClick() {
        viewModelScope.launch {
            runSuspendCatching { signupRepository.clearPending() }
                .onFailure { Log.w(TAG, "startOver: could not clear the refused token") }
            _effects.emit(LocalSignupEffect.NavigateStartOver)
        }
    }

    private companion object {
        const val TAG = "Loopky/LocalSignupVM"
    }
}

data class LocalSignupUiState(
    val isWorking: Boolean = true,
    val isComplete: Boolean = false,
    /** The account that was created, once it exists. */
    val pubky: String? = null,
    val error: SignupError? = null,
) {
    /**
     * Whether retrying could possibly help.
     *
     * False for a refused token: the retry re-sends the same dead value, so offering the button
     * is offering a loop.
     */
    val canRetry: Boolean get() = error != null && error != SignupError.TokenRejected

    /** The way out when a retry cannot work. */
    val canStartOver: Boolean get() = error == SignupError.TokenRejected
}

/**
 * Abandon a token the homeserver refused, and go back for a new one.
 *
 * Clearing is safe *only* for [SignupError.TokenRejected]: that is a definitive verdict that the
 * token cannot be spent. Any other failure keeps it, because the user may have paid for it and a
 * transient error must never throw that away.
 */
sealed interface LocalSignupEffect {
    /**
     * Straight to the backup step, not to home.
     *
     * This is the only moment in the app where a key exists that **nobody has a copy of** — not
     * Pubky Ring, not a phrase on paper, nothing but this device's keystore. Backing up is still
     * skippable from there, but it is not something to discover later in Settings.
     */
    data object NavigateBackup : LocalSignupEffect

    /** Back to the method picker, with the dead token dropped. */
    data object NavigateStartOver : LocalSignupEffect
}
