package com.github.jvsena42.loopky.presentation.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeySource
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
 * Sign in with a recovery phrase — the door for someone who has a pubky but no working Pubky Ring.
 *
 * The whole design of this screen is about **not lying to an anxious user**. Someone here has
 * typed twelve words they may have written down months ago, and the three ways this can fail are
 * genuinely different:
 *
 * - The words are not BIP-39. That is a real mistake and saying so is right.
 * - The words are valid but belong to no account — almost always a checksum-passing typo (one
 *   wrong word, or two transposed). The phrase is **not** invalid, and calling it invalid sends
 *   the user hunting for a problem that is not there. We show the pubky they derived instead,
 *   because they can often tell at a glance that it is not theirs.
 * - We could not reach the DHT to ask. That is not a verdict on anything and must never render as
 *   one.
 *
 * Two rules that are load-bearing rather than stylistic:
 *
 * 1. **The lookup runs on submit, never while typing.** A DHT probe per completed phrase is an
 *    enumeration oracle for "does this pubky exist", and it costs a round trip each time.
 * 2. **Nothing here holds the words after they are used.** They live in [RestorePhraseUiState]
 *    only while the field is on screen, and [onLeave] clears them.
 */
class RestorePhraseViewModel(
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RestorePhraseUiState())
    val state: StateFlow<RestorePhraseUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RestoreEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<RestoreEffect> = _effects.asSharedFlow()

    private var submitJob: Job? = null

    fun onPhraseChange(phrase: String) {
        // Clearing the outcome on edit is the point: the previous answer was about the previous
        // words, and leaving "that phrase has no account" under a field the user is fixing reads
        // as a verdict on what they are typing now.
        _state.update { it.copy(phrase = phrase, outcome = null) }
    }

    fun onSubmit() {
        if (submitJob?.isActive == true) return
        val phrase = _state.value.phrase
        if (phrase.isBlank()) return

        submitJob = viewModelScope.launch {
            _state.update { it.copy(isChecking = true, outcome = null) }

            val pubky = identityRepository.derivePubky(KeySource.Phrase(phrase)).getOrElse {
                // Never log the phrase, and never the exception message either — the FFI echoes
                // its input back in some error strings.
                Log.w(TAG, "onSubmit: phrase did not derive a key")
                _state.update { it.copy(isChecking = false, outcome = RestoreOutcome.InvalidPhrase) }
                return@launch
            }

            when (val lookup = identityRepository.lookupHomeserver(pubky)) {
                is HomeserverLookup.Registered -> signIn(phrase, lookup.homeserverPubky, pubky)

                // Valid words, no account. The pubky goes in the state so the screen can show it.
                HomeserverLookup.NoRecord -> {
                    // Stored before routing onward: the next screen offers "Register this key",
                    // and it has nothing to register unless the key is actually held.
                    // If the keystore is unavailable the key cannot be held, and the next
                    // screen's "Register this key" would die on a missing key the user never
                    // caused. Say so here instead of routing them at a broken button.
                    if (identityRepository.holdKeyForRegistration(KeySource.Phrase(phrase)).isFailure) {
                        _state.update {
                            it.copy(isChecking = false, outcome = RestoreOutcome.SignInFailed(ErrorReason.Unknown))
                        }
                        return@launch
                    }
                    // Not a dead end: this key is valid and can be registered deliberately, which
                    // is what the unregistered-key screen is for. The outcome stays in state so
                    // coming back shows what happened rather than an empty form.
                    _state.update { it.copy(isChecking = false, outcome = RestoreOutcome.NoAccount(pubky)) }
                    _effects.emit(RestoreEffect.NavigateUnregistered(pubky))
                }

                is HomeserverLookup.CouldNotCheck -> _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.CouldNotCheck(lookup.reason))
                }
            }
        }
    }

    private suspend fun signIn(phrase: String, homeserver: String, pubky: String) {
        runSuspendCatching {
            identityRepository.signInWithKey(KeySource.Phrase(phrase), knownHomeserver = homeserver).getOrThrow()
        }
            .onSuccess {
                // The words are done with the moment the session exists.
                _state.update { RestorePhraseUiState() }
                _effects.emit(RestoreEffect.NavigateHome)
            }
            .onFailure { error ->
                Log.e(TAG, "signIn: FAILED — ${error::class.simpleName}")
                emitSignInFailure(error, phrase, pubky)
            }
    }

    private suspend fun emitSignInFailure(error: Throwable, phrase: String, pubky: String) {
        val outcome = error.toRestoreOutcome(pubky)
        _state.update { it.copy(isChecking = false, outcome = outcome) }
        if (outcome is RestoreOutcome.NoAccount) {
            // Held here too, not only on the pre-flight branch. This is the path the device
            // actually takes for a pkarr record that outlived its account: the lookup answers
            // *Registered* and the homeserver then 404s at signin. Storing only on the other
            // branch left "Register this key" with nothing to register on the common case.
            // The phrase captured at submit, **not** `_state.value.phrase`. The field stays
            // editable while the lookup and sign-in are in flight, so a user still fixing a typo
            // would have had a key stored for the edited words while the next screen showed the
            // pubky for the submitted ones — and "Register this key" would then spend the token on
            // a pubky they never confirmed.
            if (identityRepository.holdKeyForRegistration(KeySource.Phrase(phrase)).isFailure) {
                _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.SignInFailed(ErrorReason.Unknown))
                }
                return
            }
            _effects.emit(RestoreEffect.NavigateUnregistered(pubky))
        }
    }

    /**
     * Drop the phrase when the user leaves the flow for good.
     *
     * Not housekeeping: a `StateFlow` outlives the composable that reads it, so without this the
     * twelve words sit in memory for as long as the ViewModel does, reachable from a heap dump
     * long after the user has left the screen.
     *
     * **Deliberately not called when routing to the unregistered-key screen.** That screen's
     * primary action is "Check my recovery phrase again" — the likeliest fix for landing there is
     * one mistyped word — and wiping the field first drops the user on a blank form to retype all
     * twelve.
     *
     * **Be precise about the cost, because it is not one hop.** The unregistered screen can also
     * push *forward* into verification, and this ViewModel lives as long as its nav back-stack
     * entry — so on that path the words stay in memory through the whole SMS/Lightning flow until
     * `popUpTo(ONBOARDING)` destroys the entry. An `onCleared` override was tried and removed: it
     * fires at exactly that moment, by which point the state is already unreachable, so it bought
     * nothing but the appearance of a bound.
     *
     * The trade being made is a filled field on the one action the next screen recommends, against
     * that lifetime on a path that ends in registering this same key anyway. If the lifetime is
     * judged too long, the fix is to clear on leaving *forward* — which needs the unregistered
     * screen to say which way it went — not another hook at the same moment as the last one.
     */
    fun onLeave() {
        submitJob?.cancel()
        _state.update { RestorePhraseUiState() }
    }

    /** True while an outcome is on screen that the user is expected to come back and act on. */
    private val isAwaitingCorrection: Boolean
        get() = _state.value.outcome is RestoreOutcome.NoAccount

    /** [onLeave], unless the user is mid-correction — see its note. */
    fun onLeaveUnlessCorrecting() {
        if (isAwaitingCorrection) return
        onLeave()
    }
}

data class RestorePhraseUiState(
    val phrase: String = "",
    val isChecking: Boolean = false,
    val outcome: RestoreOutcome? = null,
) {
    val canSubmit: Boolean get() = phrase.isNotBlank() && !isChecking
}

/**
 * Why a restore attempt stopped. Deliberately not a single error string: these four need different
 * copy, different affordances, and in two cases must not read as a judgement on the phrase at all.
 */
sealed interface RestoreOutcome {

    /** The words are not a valid BIP-39 phrase. An honest "this is wrong". */
    data object InvalidPhrase : RestoreOutcome

    /**
     * Valid words, but this pubky has an account nowhere. [pubky] is shown to the user: recognising
     * that it is not theirs is the fastest diagnosis available, and only they can make it.
     */
    data class NoAccount(val pubky: String) : RestoreOutcome

    /** The DHT did not answer. Offers a retry and says nothing about the phrase. */
    data class CouldNotCheck(val reason: ErrorReason) : RestoreOutcome

    /** The account exists but signing in failed — a homeserver or transport problem, not the key. */
    data class SignInFailed(val reason: ErrorReason) : RestoreOutcome

    /**
     * A recovery file would not decrypt: wrong passphrase, or the wrong file.
     *
     * Its own outcome because decryption failing says **nothing** about whether the account
     * exists. Reporting it as "no account" would send someone hunting for a lost identity when
     * all they did was mistype.
     */
    data object WrongPassphrase : RestoreOutcome

    /** The picker handed back something unreadable — a permission or a provider problem. */
    data object FileUnreadable : RestoreOutcome
}

/**
 * Classify a sign-in failure on a restore path.
 *
 * **A 404 here means "no account for this pubky", not "not found".** The homeserver answers 404 at
 * signin for a pubky it has never registered, and nothing else on this path is fetching a record —
 * no deck, no profile — so a not-found is always the account. Without this remap the generic
 * classifier renders `ErrorReason.NotFound`, whose copy is *"This deck no longer exists"*: deck
 * copy, on a recovery-phrase screen, for someone who has no account. That is the exact confusion
 * `NoHomeserverAccount` was added to end, and `OnboardingViewModel` carries the same remap for the
 * same reason.
 *
 * It is also reachable in a state the pre-flight cannot rule out: a pkarr homeserver record can
 * outlive the account it points at, so `getHomeserver` says *Registered* and the homeserver still
 * 404s. Seen on staging.
 */
internal fun Throwable.toRestoreOutcome(pubky: String): RestoreOutcome =
    when (val reason = toErrorReason()) {
        ErrorReason.NotFound, ErrorReason.NoHomeserverAccount -> RestoreOutcome.NoAccount(pubky)
        else -> RestoreOutcome.SignInFailed(reason)
    }

sealed interface RestoreEffect {
    data object NavigateHome : RestoreEffect

    /**
     * A valid key with no account. Carries the derived [pubky] so the next screen can show it —
     * recognising it is not theirs is the fastest diagnosis the user can make.
     */
    data class NavigateUnregistered(val pubky: String) : RestoreEffect
}

private const val TAG = "Loopky/RestorePhraseVM"
