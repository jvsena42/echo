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
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Begin a Pubky Ring authorisation.
     *
     * [handoff] decides only whether we *also* fire the deeplink. The relay poll underneath is
     * identical either way — Ring posts the approval back over the relay whether it was opened by
     * a deeplink on this device or by scanning the QR on another one — which is why a tablet can
     * be signed in from a phone at all, and why this is a branch in the effect rather than a
     * second flow.
     */
    fun onSignInClick(handoff: RingHandoff = RingHandoff.ThisDevice) {
        if (signInJob?.isActive == true) {
            Log.d(TAG, "onSignInClick: ignored — sign-in already in progress")
            return
        }
        signInJob = viewModelScope.launch {
            Log.d(TAG, "onSignInClick: state=Starting, calling beginSignIn handoff=$handoff")
            _state.update { OnboardingUiState.Starting }
            val handleResult = identityRepository.beginSignIn()
            val handle = handleResult.getOrElse { error ->
                Log.e(TAG, "onSignInClick: beginSignIn FAILED — ${error::class.simpleName}: ${error.message}", error)
                _state.update { OnboardingUiState.Error(error.toErrorReason()) }
                return@launch
            }
            Log.d(TAG, "onSignInClick: got authUrl=${handle.authUrl.redactAuthUrl()}")

            _state.update { OnboardingUiState.AwaitingApproval(handle.authUrl, handoff) }
            Log.d(TAG, "onSignInClick: state=AwaitingApproval, handoff=$handoff")
            // Only when Ring is meant to be on this device. Firing the deeplink for the QR path
            // would bounce the user out to whatever claims `pubkyauth://` — or to nothing at all,
            // which is the case the QR exists to serve.
            if (handoff == RingHandoff.ThisDevice) {
                _effects.emit(OnboardingEffect.OpenDeeplink(handle.authUrl))
            }

            Log.d(TAG, "onSignInClick: awaiting Pubky Ring approval…")
            // Bounded because the relay poll is not: `await_auth_approval` blocks with no timeout
            // of its own, and there are real cases where nothing is ever posted to the relay —
            // notably a pubky the homeserver has no account for, which Ring rejects on its own
            // side and never authorises. Without this the user sits on "Waiting for Pubky Ring…"
            // forever with no error and no way back.
            val completion = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { handle.complete() }
                ?: Result.failure(RingApprovalTimeout())
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
    private fun Throwable.toSignInReason(): ErrorReason {
        // Matched by type, not message: `toErrorReason` classifies "timed out"/"timeout" as a
        // transport failure, which would render "the relay isn't responding". Ring going quiet
        // is not the relay being down — the commonest cause is Ring declining on its own side
        // and never posting anything, so this is an auth failure.
        if (this is RingApprovalTimeout) return ErrorReason.AuthFailed
        return when (val reason = toErrorReason()) {
            ErrorReason.Offline -> ErrorReason.AuthRelayUnreachable
            ErrorReason.Unknown -> ErrorReason.AuthFailed
            // The homeserver answers 404 when it has no account for the pubky Ring just
            // authorised (pubky-homeserver `routes/auth.rs::signin` → `get_or_http_error`).
            // Nothing else on this path can 404 for a *record* — no deck or profile is being
            // fetched yet — so here, and only here, a not-found is always the account. That is
            // why the remap lives in the sign-in path rather than in `toErrorReason`, which
            // classifies reads too and would turn "your library is empty" into "no account".
            ErrorReason.NotFound -> ErrorReason.NoHomeserverAccount
            else -> reason
        }
    }

    /**
     * Escape hatch from the QR panel: approve on *this* device after all, without restarting.
     *
     * The same live authorisation, so the QR stays valid and the relay poll keeps running — a
     * fresh [onSignInClick] would abandon the in-flight flow and invalidate the code the user may
     * already be pointing a phone at.
     */
    fun onOpenRingOnThisDevice() {
        val awaiting = _state.value as? OnboardingUiState.AwaitingApproval ?: run {
            Log.w(TAG, "onOpenRingOnThisDevice: ignored — no authorisation in flight")
            return
        }
        viewModelScope.launch { _effects.emit(OnboardingEffect.OpenDeeplink(awaiting.authUrl)) }
    }

    /**
     * Back out of a sign-in that is still waiting on Ring, without leaving an error behind.
     *
     * Distinct from [onDeeplinkUnavailable], which also cancels the job but lands on
     * [ErrorReason.RingNotInstalled]: the user closing the QR panel has not hit a problem, so the
     * screen goes back to [OnboardingUiState.Idle] and the CTA is live again. The abandoned
     * authorisation is left to expire on the relay — there is nothing to revoke, and the URL is
     * useless to anyone who did not already have it.
     */
    fun onCancelSignIn() {
        Log.d(TAG, "onCancelSignIn: user dismissed the handoff")
        signInJob?.cancel()
        signInJob = null
        _state.update { OnboardingUiState.Idle }
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

        /**
         * How long to wait for Pubky Ring before giving up. Generous on purpose — approving can
         * mean creating a key and writing down a recovery phrase — but finite, because the
         * alternative is a spinner that never resolves.
         */
        private const val APPROVAL_TIMEOUT_MS = 3 * 60 * 1000L

        /** Product landing page — forwards to the correct store for the user's platform. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}

/**
 * Pubky Ring never came back within the approval window.
 *
 * A distinct type rather than a message string because the message-based classifier in
 * `PubkyErrors` reads "timed out" as a transport failure, and this is not one — the relay is
 * usually fine and simply has nothing to deliver.
 */
internal class RingApprovalTimeout : RuntimeException("Pubky Ring did not complete the authorisation")
