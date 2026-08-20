package com.github.jvsena42.loopky.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.redactAuthUrl
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
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
 * KMP ViewModel for the onboarding / Pubky Ring login screen.
 *
 * Owns the state machine documented in the plan: Idle → Starting → AwaitingApproval →
 * Verifying → Success / Error. Side effects (open deeplink, install page, navigate home)
 * are pushed through [effects] so platform UIs can react without leaking platform APIs
 * into the VM.
 */
class OnboardingViewModel(
    private val identityRepository: IdentityRepository,
    private val pubkyRingInstallUrl: String = DEFAULT_INSTALL_URL,
) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Restoring)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    private var signInJob: Job? = null

    init {
        Log.d(TAG, "init: checking persisted session")
        viewModelScope.launch {
            // The screen sits on the branded splash until this resolves, so every exit from here
            // — including a store that throws — has to move the state on or the splash never lifts.
            val persisted = runSuspendCatching { identityRepository.loadPersistedSession() }
                .onFailure { Log.e(TAG, "init: reading the persisted session failed", it) }
                .getOrNull()
            if (persisted != null) {
                Log.d(TAG, "init: found persisted session pubky=${persisted.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                _state.update { OnboardingUiState.Success(persisted) }
                _effects.emit(OnboardingEffect.NavigateHome)
            } else {
                Log.d(TAG, "init: no persisted session")
                _state.update { OnboardingUiState.Idle }
            }
        }
    }

    fun onSignInClick() {
        if (signInJob?.isActive == true) {
            Log.d(TAG, "onSignInClick: ignored — sign-in already in progress")
            return
        }
        signInJob = viewModelScope.launch {
            Log.d(TAG, "onSignInClick: state=Starting, calling beginSignIn")
            _state.update { OnboardingUiState.Starting }
            val handleResult = identityRepository.beginSignIn()
            val handle = handleResult.getOrElse { error ->
                Log.e(TAG, "onSignInClick: beginSignIn FAILED — ${error::class.simpleName}: ${error.message}", error)
                _state.update { OnboardingUiState.Error(error.toErrorReason()) }
                return@launch
            }
            Log.d(TAG, "onSignInClick: got authUrl=${handle.authUrl.redactAuthUrl()}")

            _state.update { OnboardingUiState.AwaitingApproval }
            Log.d(TAG, "onSignInClick: state=AwaitingApproval, emitting OpenDeeplink")
            _effects.emit(OnboardingEffect.OpenDeeplink(handle.authUrl))

            Log.d(TAG, "onSignInClick: awaiting Pubky Ring approval…")
            val completion = handle.complete()
            _state.update { OnboardingUiState.Verifying }
            Log.d(TAG, "onSignInClick: state=Verifying, completion.success=${completion.isSuccess}")

            completion
                .onSuccess { session ->
                    Log.d(TAG, "onSignInClick: SUCCESS pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                    _state.update { OnboardingUiState.Success(session) }
                    _effects.emit(OnboardingEffect.NavigateHome)
                }
                .onFailure { err ->
                    Log.e(TAG, "onSignInClick: completion FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { OnboardingUiState.Error(err.toSignInReason()) }
                }
        }
    }

    /**
     * Classify a failed approval. The only network the completion touches is the auth relay — the
     * profile fetch inside it is best-effort — so a transport failure here is the relay being
     * unreachable, not the user being offline. Anything we cannot classify is still an auth
     * failure from the user's point of view, not a mystery.
     */
    private fun Throwable.toSignInReason(): ErrorReason = when (val reason = toErrorReason()) {
        ErrorReason.Offline -> ErrorReason.AuthRelayUnreachable
        ErrorReason.Unknown -> ErrorReason.AuthFailed
        else -> reason
    }

    fun onGetRingClick() {
        viewModelScope.launch { _effects.emit(OnboardingEffect.OpenInstallPage(pubkyRingInstallUrl)) }
    }

    /**
     * Called by the UI when it cannot open the Pubky Ring deeplink (e.g. Ring not installed).
     * We cancel the in-flight sign-in job so `awaitAuthApproval` doesn't keep blocking, and
     * surface an actionable error.
     */
    fun onDeeplinkUnavailable() {
        Log.w(TAG, "onDeeplinkUnavailable: no handler for pubkyauth:// — aborting flow")
        signInJob?.cancel()
        signInJob = null
        _state.update { OnboardingUiState.Error(ErrorReason.RingNotInstalled) }
    }

    companion object {
        private const val TAG = "Loopky/OnboardingVM"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /** Product landing page — forwards to the correct store for the user's platform. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}
