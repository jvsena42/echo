package com.github.jvsena42.loopky.presentation.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.presentation.signup.SignupError
import com.github.jvsena42.loopky.presentation.signup.toSignupError
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
 * "This key is valid, and it has no account."
 *
 * Reached from two places that look different and are the same problem: a recovery phrase whose
 * pkarr pre-flight said `HomeserverLookup.NoRecord`, and a pubky Pubky Ring authorised that the
 * homeserver 404s. Never from a *successful* sign-in.
 *
 * The rules this screen exists to enforce:
 *
 * - **"Check my phrase again" is the primary action; registering is secondary.** A checksum-passing
 *   typo is by far the likeliest reason to be here, and registering is the branch that costs money
 *   and publishes an identity. Ranked by likelihood, not by which is easier to build.
 * - **No implicit signup, ever.** Registration happens on an explicit confirm, with the pubky on
 *   screen, and it registers *the key already in hand* — never a freshly minted one.
 * - **Reuse a stored token before minting.** The user may already have paid; [PendingSignup] may
 *   hold a `Redeemable`. It is only usable if its homeserver matches, since a token is bound to
 *   the Homegate that issued it.
 * - **Who holds the key changes the options.** Loopky holding it means we can register it right
 *   here. Ring holding it means we cannot — `signUp` needs the secret key — so the honest answer
 *   is the guided route, not a button that quietly creates a second identity.
 */
class UnregisteredKeyViewModel(
    private val pubky: String,
    private val custody: KeyCustody,
    private val signupRepository: SignupRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        UnregisteredKeyUiState(pubky = pubky, loopkyHoldsKey = custody is KeyCustody.Loopky),
    )
    val state: StateFlow<UnregisteredKeyUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UnregisteredKeyEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<UnregisteredKeyEffect> = _effects.asSharedFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val usable = usableToken()
            _state.update {
                it.copy(hasUsableToken = usable != null, homeserverPubky = usable?.homeserverPubky)
            }
        }
    }

    /**
     * A stored token that can actually be spent.
     *
     * A token is bound to the Homegate that issued it, so one for a different homeserver is not
     * "a token we have": spending it there fails, and it is single-use with no expiry, so that
     * failure is permanent.
     */
    private suspend fun usableToken(): PendingSignup.Redeemable? =
        runSuspendCatching { signupRepository.pending.first() }.getOrNull() as? PendingSignup.Redeemable

    /** Back to the phrase field — the likeliest fix, and the reason this is the primary action. */
    fun onCheckPhraseAgainClick() {
        viewModelScope.launch { _effects.emit(UnregisteredKeyEffect.NavigateBack) }
    }

    /**
     * Register the key already in hand.
     *
     * Only reachable from an explicit confirm with the pubky visible: this is the step that
     * publishes a pkarr record and makes the identity real, and it must not happen on a mis-tap.
     */
    fun onRegisterConfirmed() {
        if (job?.isActive == true) return
        // Ring holds the key, so there is no secret key to sign up with. Offering the button at
        // all would be offering to create a *different* identity.
        if (custody !is KeyCustody.Loopky) return

        job = viewModelScope.launch {
            _state.update { it.copy(isRegistering = true, error = null) }

            val token = usableToken()
            if (token == null) {
                // Nothing spendable yet: the human check has to run first, and that is a whole
                // flow rather than something to do silently here.
                _state.update { it.copy(isRegistering = false) }
                _effects.emit(UnregisteredKeyEffect.NavigateSignup)
                return@launch
            }

            identityRepository.registerHeldKey(token.homeserverPubky, token.token)
                .onSuccess {
                    // The repository already asserted the pubky matches the key it registered, so
                    // reaching here is proof the token was spent on the right account.
                    runSuspendCatching { signupRepository.clearPending() }
                        .onFailure { Log.w(TAG, "register: could not clear the spent token") }
                    _state.update { it.copy(isRegistering = false) }
                    _effects.emit(UnregisteredKeyEffect.NavigateBackup)
                }
                .onFailure { error ->
                    Log.e(TAG, "register: FAILED — ${error::class.simpleName}")
                    // The token is kept: if signUp never landed, it is still spendable.
                    _state.update { it.copy(isRegistering = false, error = error.toSignupError()) }
                }
        }
    }

    private companion object {
        const val TAG = "Loopky/UnregisteredKeyVM"
    }
}

data class UnregisteredKeyUiState(
    /** Always shown. Recognising it is not theirs is the fastest diagnosis, and only they can make it. */
    val pubky: String,
    /**
     * Whether Loopky can register this key itself.
     *
     * False when Pubky Ring holds it: `signUp` needs the secret key, so the only routes that keep
     * the user's key are Ring's Edit-pubky sheet or importing the phrase into Loopky.
     */
    val loopkyHoldsKey: Boolean,
    val hasUsableToken: Boolean = false,
    val homeserverPubky: String? = null,
    val isRegistering: Boolean = false,
    val error: SignupError? = null,
)

sealed interface UnregisteredKeyEffect {
    data object NavigateBack : UnregisteredKeyEffect
    data object NavigateSignup : UnregisteredKeyEffect
    data object NavigateBackup : UnregisteredKeyEffect
}
