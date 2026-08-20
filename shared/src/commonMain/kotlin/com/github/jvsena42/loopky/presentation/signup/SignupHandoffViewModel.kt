package com.github.jvsena42.loopky.presentation.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.redactAuthUrl
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.domain.model.Session
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The last step: hand the stored signup token to Pubky Ring, which mints a key, redeems the token
 * against the homeserver, and authorises back over the relay.
 *
 * This screen is reachable on a cold start whenever a token is stored, so a user who paid and then
 * lost the app resumes here rather than paying again.
 */
class SignupHandoffViewModel(
    private val signupRepository: SignupRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SignupHandoffUiState())
    val state: StateFlow<SignupHandoffUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SignupHandoffEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SignupHandoffEffect> = _effects.asSharedFlow()

    private var handoffJob: Job? = null

    init {
        viewModelScope.launch {
            val pending = signupRepository.pending.first()
            if (pending == null) {
                Log.w(TAG, "init: no pending signup — nothing to hand off")
                _state.update { it.copy(isWorking = false, error = SignupError.VerificationLost) }
            } else {
                _state.update { it.copy(token = pending.token) }
                start(pending)
            }
        }
    }

    fun onRetryClick() {
        viewModelScope.launch {
            // Deliberately re-reads the stored token instead of asking Homegate for another. Ring
            // keys the pubky it minted off the token, so re-sending the same one reuses that key
            // and skips an already-redeemed signup; minting a fresh token would instead create a
            // second identity and spend the user's money twice.
            val pending = signupRepository.pending.first() ?: return@launch
            start(pending)
        }
    }

    private fun start(pending: PendingSignup) {
        if (handoffJob?.isActive == true) return
        handoffJob = viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }

            val handle = identityRepository.beginSignUp(
                homeserverPubky = pending.homeserverPubky,
                signupToken = pending.token,
            ).getOrElse { err ->
                Log.e(TAG, "start: beginSignUp FAILED — ${err.message}", err)
                _state.update { it.copy(isWorking = false, error = SignupError.RingFailed) }
                return@launch
            }

            Log.d(TAG, "start: authUrl=${handle.authUrl.redactAuthUrl()}")
            _effects.emit(SignupHandoffEffect.OpenDeeplink(handle.authUrl))

            // Bounded for the same reason sign-in is: the relay poll has no timeout of its own, and
            // Ring posts nothing when it declines, so without this the screen waits forever.
            val completion = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { handle.complete() }
            if (completion == null) {
                Log.w(TAG, "start: Ring never answered")
                _state.update { it.copy(isWorking = false, error = SignupError.RingFailed) }
                return@launch
            }

            completion
                .onSuccess { session -> onApproved(session, pending) }
                .onFailure { err ->
                    Log.e(TAG, "start: approval FAILED — ${err.message}", err)
                    _state.update { it.copy(isWorking = false, error = err.toSignupError()) }
                }
        }
    }

    /**
     * A session came back — but that alone does not prove the token was spent.
     *
     * If Ring's signup fails while it holds another already-signed-up pubky, it quietly authorises
     * *that* pubky instead and returns a perfectly valid session, leaving the token unredeemed.
     * Clearing here unconditionally would throw away something the user paid for in exactly the
     * case where they still need it, so the token is only dropped when the pubky we ended up
     * signed in as is the one this signup created.
     */
    private suspend fun onApproved(session: Session, pending: PendingSignup) {
        val signedUpHere = session.homeserver.isBlank() ||
            session.homeserver == pending.homeserverPubky

        if (signedUpHere) {
            runSuspendCatching { signupRepository.clearPending() }
                .onFailure { Log.w(TAG, "onApproved: could not clear the token — ${it.message}") }
        } else {
            // Keep it: the user is signed in as someone else and this token is still spendable.
            Log.w(TAG, "onApproved: signed in on a different homeserver — keeping the token")
        }

        _state.update { it.copy(isWorking = false, error = null, isComplete = true) }
        _effects.emit(SignupHandoffEffect.NavigateHome)
    }

    /** The user wants out, but keeps the token — they can spend it later. */
    fun onUseExistingPubkyClick() {
        viewModelScope.launch { _effects.emit(SignupHandoffEffect.NavigateSignIn) }
    }

    fun onCopyTokenClick() {
        val token = _state.value.token ?: return
        viewModelScope.launch { _effects.emit(SignupHandoffEffect.CopyToClipboard(token)) }
    }

    fun onDeeplinkUnavailable() {
        handoffJob?.cancel()
        handoffJob = null
        _state.update { it.copy(isWorking = false, error = SignupError.RingNotInstalled) }
    }

    companion object {
        private const val TAG = "Loopky/SignupHandoffVM"

        /** Matches sign-in: long enough to create a key and write down a recovery phrase. */
        private const val APPROVAL_TIMEOUT_MS = 3 * 60 * 1000L
    }
}

data class SignupHandoffUiState(
    val isWorking: Boolean = true,
    val isComplete: Boolean = false,
    /** Shown so the user can copy it — a token they can read cannot be lost to a bug in here. */
    val token: String? = null,
    val error: SignupError? = null,
)

sealed interface SignupHandoffEffect {
    data class OpenDeeplink(val url: String) : SignupHandoffEffect
    data class CopyToClipboard(val text: String) : SignupHandoffEffect
    data object NavigateHome : SignupHandoffEffect
    data object NavigateSignIn : SignupHandoffEffect
}
