package com.github.jvsena42.loopky.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
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
    appVersion: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var signOutJob: Job? = null

    init {
        load()
        // Collected rather than read once: the publish/follow/clone prompts carry their own
        // "Don't ask again", and flipping it there has to move this switch too — they are one
        // setting with two controls, not two settings.
        appPreferences.shareOnPubky
            .onEach { enabled -> _state.update { it.copy(shareOnPubky = enabled) } }
            .launchIn(viewModelScope)
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
)

sealed interface SettingsEffect {
    data object SignedOut : SettingsEffect
    data class CopyToClipboard(val text: String) : SettingsEffect
}
