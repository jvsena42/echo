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
                is HomeserverLookup.Registered -> signIn(phrase, lookup.homeserverPubky)

                // Valid words, no account. The pubky goes in the state so the screen can show it.
                HomeserverLookup.NoRecord -> _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.NoAccount(pubky))
                }

                is HomeserverLookup.CouldNotCheck -> _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.CouldNotCheck(lookup.reason))
                }
            }
        }
    }

    private suspend fun signIn(phrase: String, homeserver: String) {
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
                _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.SignInFailed(error.toErrorReason()))
                }
            }
    }

    /**
     * Drop the phrase when the screen goes away.
     *
     * Not housekeeping: a `StateFlow` outlives the composable that reads it, so without this the
     * twelve words sit in memory for as long as the ViewModel does, reachable from a heap dump
     * long after the user has left the screen.
     */
    fun onLeave() {
        submitJob?.cancel()
        _state.update { RestorePhraseUiState() }
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

sealed interface RestoreEffect {
    data object NavigateHome : RestoreEffect
}

private const val TAG = "Loopky/RestorePhraseVM"
