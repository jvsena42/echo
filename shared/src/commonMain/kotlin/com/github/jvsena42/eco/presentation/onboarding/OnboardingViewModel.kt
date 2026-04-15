package com.github.jvsena42.eco.presentation.onboarding

import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    private var signInJob: Job? = null

    init {
        Log.d(TAG, "init: checking persisted session")
        scope.launch {
            val persisted = identityRepository.loadPersistedSession()
            if (persisted != null) {
                Log.d(TAG, "init: found persisted session pubky=${persisted.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                _state.value = OnboardingUiState.Success(persisted)
                _effects.emit(OnboardingEffect.NavigateHome)
            } else {
                Log.d(TAG, "init: no persisted session")
            }
        }
    }

    fun onSignInClick() {
        if (signInJob?.isActive == true) {
            Log.d(TAG, "onSignInClick: ignored — sign-in already in progress")
            return
        }
        signInJob = scope.launch {
            Log.d(TAG, "onSignInClick: state=Starting, calling beginSignIn")
            _state.value = OnboardingUiState.Starting
            val handleResult = identityRepository.beginSignIn()
            val handle = handleResult.getOrElse {
                Log.e(TAG, "onSignInClick: beginSignIn FAILED — ${it::class.simpleName}: ${it.message}", it)
                _state.value = OnboardingUiState.Error(
                    it.message ?: "Could not start Pubky Ring sign-in.",
                )
                return@launch
            }
            Log.d(TAG, "onSignInClick: got authUrl=${handle.authUrl}")

            _state.value = OnboardingUiState.AwaitingApproval
            Log.d(TAG, "onSignInClick: state=AwaitingApproval, emitting OpenDeeplink")
            _effects.emit(OnboardingEffect.OpenDeeplink(handle.authUrl))

            Log.d(TAG, "onSignInClick: awaiting Pubky Ring approval…")
            val completion = handle.complete()
            _state.value = OnboardingUiState.Verifying
            Log.d(TAG, "onSignInClick: state=Verifying, completion.success=${completion.isSuccess}")

            completion
                .onSuccess { session ->
                    Log.d(TAG, "onSignInClick: SUCCESS pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
                    _state.value = OnboardingUiState.Success(session)
                    _effects.emit(OnboardingEffect.NavigateHome)
                }
                .onFailure { err ->
                    Log.e(TAG, "onSignInClick: completion FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.value = OnboardingUiState.Error(
                        err.message ?: "Sign-in was not completed.",
                    )
                }
        }
    }

    fun onGetRingClick() {
        scope.launch { _effects.emit(OnboardingEffect.OpenInstallPage(pubkyRingInstallUrl)) }
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
        _state.value = OnboardingUiState.Error(
            "Pubky Ring isn't installed. Install it to sign in.",
        )
    }

    fun onRetry() {
        _state.value = OnboardingUiState.Idle
    }

    fun onDispose() {
        signInJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Echo/OnboardingVM"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /** Product landing page — forwards to the correct store for the user's platform. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}
