package com.github.jvsena42.loopky.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.data.unsplash.UnsplashException
import com.github.jvsena42.loopky.data.unsplash.maskedKeySuffix
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val identityRepository: IdentityRepository,
    private val pubkyClient: PubkyClient,
    private val appPreferences: AppPreferences,
    private val unsplashKeyStore: UnsplashKeyStore,
    private val unsplashClient: UnsplashClient,
    appVersion: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var signOutJob: Job? = null
    private var unsplashKeyJob: Job? = null

    init {
        load()
        // Collected rather than read once: the publish/follow/clone prompts carry their own
        // "Don't ask again", and flipping it there has to move this switch too — they are one
        // setting with two controls, not two settings.
        appPreferences.shareOnPubky
            .onEach { enabled -> _state.update { it.copy(shareOnPubky = enabled) } }
            .launchIn(viewModelScope)
        // Collected for the same reason: saving and removing the key both go through the store,
        // and the status row has to follow it rather than a local copy.
        unsplashKeyStore.key
            .onEach { key -> _state.update { it.copy(unsplashKeyStatus = statusFor(key)) } }
            .launchIn(viewModelScope)
    }

    /**
     * Note what this never does: read [UnsplashClient]'s build-time fallback. The user is told a
     * shared key is in use, never what it is. Their own key is reduced to four characters before
     * it can reach [SettingsUiState].
     */
    private fun statusFor(userKey: String): UnsplashKeyStatus = when {
        userKey.isNotBlank() -> UnsplashKeyStatus.UserSet(maskedKeySuffix(userKey))
        unsplashClient.hasFallbackKey -> UnsplashKeyStatus.UsingBuiltIn
        else -> UnsplashKeyStatus.NotSet
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching session")
            _state.update { it.copy(isLoading = true) }

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            // Pubky Ring's session payload carries no `homeserver` field, so this was always
            // blank and Settings permanently showed "Unknown". Resolve it from the pkarr record.
            val homeserver = session?.homeserver?.takeIf { it.isNotBlank() }
                ?: session?.identity?.pubky?.let { pubky ->
                    runSuspendCatching { pubkyClient.getHomeserver(pubky).getOrNull() }.getOrNull()
                }

            _state.update {
                it.copy(
                    isLoading = false,
                    pubky = session?.identity?.pubky.orEmpty(),
                    displayName = session?.identity?.displayName,
                    homeserver = homeserver.orEmpty(),
                )
            }
            Log.d(TAG, "load: done — hasSession=${session != null}")
        }
    }

    fun onCopyPubkyClick() {
        val pubky = _state.value.pubky
        if (pubky.isNotBlank()) {
            viewModelScope.launch { _effects.emit(SettingsEffect.CopyToClipboard(pubky)) }
        }
    }

    /**
     * Announcing, not visibility. The deck is public either way — see
     * [com.github.jvsena42.loopky.domain.model.DeckAnnouncement].
     */
    fun onShareOnPubkyChange(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setShareOnPubky(enabled) }
    }

    /**
     * Verifies [key] against Unsplash before storing it, so a typo is rejected here rather than
     * days later as an unexplained empty grid.
     *
     * A network failure is not the key's fault, so an unreachable Unsplash still saves — refusing
     * would make the field unusable offline. Only an outright rejection blocks the save.
     *
     * [key] arrives as an argument and is never held in [SettingsUiState]: a draft secret in a
     * StateFlow is one state dump away from a log.
     */
    fun onSaveUnsplashKey(key: String) {
        val candidate = key.trim()
        if (candidate.isBlank() || unsplashKeyJob?.isActive == true) return
        unsplashKeyJob = viewModelScope.launch {
            _state.update { it.copy(isVerifyingUnsplashKey = true, unsplashKeyError = null) }
            val verdict = unsplashClient.verify(candidate)
            val error = verdict.exceptionOrNull()?.toUnsplashError()
            if (error == UnsplashError.InvalidKey || error == UnsplashError.MissingKey) {
                Log.d(TAG, "onSaveUnsplashKey: rejected — $error")
                _state.update { it.copy(isVerifyingUnsplashKey = false, unsplashKeyError = error) }
                return@launch
            }
            unsplashKeyStore.save(candidate)
            Log.d(TAG, "onSaveUnsplashKey: stored a user key")
            _state.update { it.copy(isVerifyingUnsplashKey = false, unsplashKeyError = null) }
        }
    }

    /** Drops the user's key; the build-time fallback, if any, takes over again. */
    fun onRemoveUnsplashKey() {
        if (unsplashKeyJob?.isActive == true) return
        unsplashKeyJob = viewModelScope.launch {
            unsplashKeyStore.clear()
            _state.update { it.copy(unsplashKeyError = null) }
        }
    }

    /** Dismisses the inline rejection when the user starts editing again. */
    fun onUnsplashKeyErrorDismissed() {
        _state.update { it.copy(unsplashKeyError = null) }
    }

    private fun Throwable.toUnsplashError(): UnsplashError =
        (this as? UnsplashException)?.error ?: UnsplashError.Unavailable

    fun onSignOutClick() {
        if (signOutJob?.isActive == true) return
        signOutJob = viewModelScope.launch {
            Log.d(TAG, "onSignOutClick: signing out")
            identityRepository.signOut()
            _effects.emit(SettingsEffect.SignedOut)
        }
    }

    companion object {
        private const val TAG = "Loopky/SettingsVM"
    }
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val pubky: String = "",
    val displayName: String? = null,
    val homeserver: String = "",
    val appVersion: String = "",
    /** Whether Loopky may offer to announce a deck on Pubky. Default on; off means never asked. */
    val shareOnPubky: Boolean = true,
    val unsplashKeyStatus: UnsplashKeyStatus = UnsplashKeyStatus.NotSet,
    val isVerifyingUnsplashKey: Boolean = false,
    val unsplashKeyError: UnsplashError? = null,
)

/**
 * What the Unsplash row reports. Carries no key material: [UserSet] holds four characters, and the
 * built-in fallback is represented by its existence alone.
 */
sealed interface UnsplashKeyStatus {
    /** Neither the user nor the build supplies a key — web image search is off. */
    data object NotSet : UnsplashKeyStatus

    /** Running on the shared build-time key, whose rate limit every install competes for. */
    data object UsingBuiltIn : UnsplashKeyStatus

    /** The user's own key. [suffix] is its last four characters and never more. */
    data class UserSet(val suffix: String) : UnsplashKeyStatus
}

sealed interface SettingsEffect {
    data object SignedOut : SettingsEffect
    data class CopyToClipboard(val text: String) : SettingsEffect
}
