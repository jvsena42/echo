package com.github.jvsena42.loopky.presentation.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.IdentityRepository
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
 * Sign in with an encrypted recovery file — the other half of the restore door.
 *
 * Same three-way honesty as [RestorePhraseViewModel], plus one failure that path does not have: a
 * **wrong passphrase**. That is deliberately its own outcome. Decryption failing says nothing
 * about whether the account exists, and reporting it as "no account" would send someone hunting
 * for a lost identity when all they did was mistype.
 *
 * The file arrives already Base64-encoded, because that is the envelope the FFI's
 * `decrypt_recovery_file` expects. The bytes **on disk** are raw — pubky-app writes
 * `recovery.pkarr` that way — so the platform layer that read the file owns the encoding.
 */
class RestoreFileViewModel(
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreFileUiState())
    val state: StateFlow<RestoreFileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RestoreEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<RestoreEffect> = _effects.asSharedFlow()

    private var submitJob: Job? = null

    /** A file was chosen. [base64] is its raw bytes, Base64-encoded by the platform layer. */
    fun onFilePicked(fileName: String, base64: String) {
        _state.update { it.copy(fileName = fileName, fileBase64 = base64, outcome = null) }
    }

    /** The picker could not read what the user chose — a permission or a provider problem. */
    fun onFileUnreadable() {
        _state.update { it.copy(outcome = RestoreOutcome.FileUnreadable) }
    }

    fun onPassphraseChange(passphrase: String) {
        _state.update { it.copy(passphrase = passphrase, outcome = null) }
    }

    fun onSubmit() {
        if (submitJob?.isActive == true) return
        val current = _state.value
        val base64 = current.fileBase64 ?: return
        if (current.passphrase.isEmpty()) return

        submitJob = viewModelScope.launch {
            _state.update { it.copy(isChecking = true, outcome = null) }
            val source = KeySource.RecoveryFile(base64, current.passphrase)

            val pubky = identityRepository.derivePubky(source).getOrElse {
                // Never log the exception message: the FFI echoes some inputs back.
                Log.w(TAG, "onSubmit: recovery file did not open")
                _state.update { it.copy(isChecking = false, outcome = RestoreOutcome.WrongPassphrase) }
                return@launch
            }

            when (val lookup = identityRepository.lookupHomeserver(pubky)) {
                is HomeserverLookup.Registered -> signIn(source, lookup.homeserverPubky)

                HomeserverLookup.NoRecord -> _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.NoAccount(pubky))
                }

                is HomeserverLookup.CouldNotCheck -> _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.CouldNotCheck(lookup.reason))
                }
            }
        }
    }

    private suspend fun signIn(source: KeySource, homeserver: String) {
        runSuspendCatching {
            identityRepository.signInWithKey(source, knownHomeserver = homeserver).getOrThrow()
        }
            .onSuccess {
                _state.update { RestoreFileUiState() }
                _effects.emit(RestoreEffect.NavigateHome)
            }
            .onFailure { error ->
                Log.e(TAG, "signIn: FAILED — ${error::class.simpleName}")
                _state.update {
                    it.copy(isChecking = false, outcome = RestoreOutcome.SignInFailed(error.toErrorReason()))
                }
            }
    }

    /** Drop the passphrase and the file when the screen goes away, for the same reason the phrase is dropped. */
    fun onLeave() {
        submitJob?.cancel()
        _state.update { RestoreFileUiState() }
    }

    private companion object {
        const val TAG = "Loopky/RestoreFileVM"
    }
}

data class RestoreFileUiState(
    val fileName: String? = null,
    /** Base64 of the file's raw bytes. Never rendered. */
    val fileBase64: String? = null,
    val passphrase: String = "",
    val isChecking: Boolean = false,
    val outcome: RestoreOutcome? = null,
) {
    val canSubmit: Boolean get() = fileBase64 != null && passphrase.isNotEmpty() && !isChecking
}
