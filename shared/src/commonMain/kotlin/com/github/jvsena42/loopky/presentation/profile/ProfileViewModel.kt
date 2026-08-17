package com.github.jvsena42.loopky.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.requiresReauth
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.avatarInitial
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

class ProfileViewModel(
    private val identityRepository: IdentityRepository,
    private val deckRepository: DeckRepository,
    private val srsRepository: SrsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        load()
        // The deck/card counters shown here go stale the moment a deck is published or deleted.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
        // Same for the due counter: studying runs on its own destination while this tab stays
        // composed, so without this it keeps the value it had before the session.
        viewModelScope.launch {
            srsRepository.changes.collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps the profile on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching profile + stats (silent=$silent)")
            if (!silent) _state.update { it.copy(isLoading = true) }

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            if (session == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            val pubky = session.identity.pubky

            // Fetch fresh profile from homeserver — this screen is where the name is edited, so it
            // reads past the cache the rest of the app shares.
            val profile = runSuspendCatching {
                identityRepository.fetchProfile(pubky, forceRefresh = true).getOrNull()
            }.getOrNull() ?: session.identity

            // Fetch deck stats
            val decksResult = runSuspendCatching { deckRepository.listOwned() }
            if (decksResult.exceptionOrNull()?.requiresReauth() == true) {
                handleSessionExpired()
                return@launch
            }
            val decks = decksResult.getOrElse { emptyList() }
            val deckCount = decks.size
            val cardCount = decks.sumOf { it.cardCount }
            // Degrade to 0 rather than failing the whole profile load if the SRS read fails.
            val dueCount = runSuspendCatching { srsRepository.dueToday().size }.getOrDefault(0)

            val displayName = profile.displayName ?: session.identity.displayName
            _state.update {
                it.copy(
                    isLoading = false,
                    displayName = displayName,
                    pubky = pubky,
                    bio = profile.bio ?: session.identity.bio,
                    avatarInitial = profile.copy(displayName = displayName).avatarInitial,
                    deckCount = deckCount,
                    cardCount = cardCount,
                    dueCount = dueCount,
                )
            }
            Log.d(TAG, "load: done — decks=$deckCount cards=$cardCount due=$dueCount")
        }
    }

    fun onEditProfileClick() {
        val current = _state.value
        _state.update {
            it.copy(
                showEditSheet = true,
                editName = current.displayName.orEmpty(),
                editBio = current.bio.orEmpty(),
            )
        }
    }

    fun onDismissEditSheet() {
        _state.update { it.copy(showEditSheet = false) }
    }

    fun onEditNameChanged(text: String) {
        _state.update { it.copy(editName = text) }
    }

    fun onEditBioChanged(text: String) {
        _state.update { it.copy(editBio = text) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(isSaving = true) }
            Log.d(TAG, "onSaveClick: saving profile")

            identityRepository.updateProfile(
                name = current.editName.ifBlank { null },
                bio = current.editBio.ifBlank { null },
            ).onSuccess { identity ->
                Log.d(TAG, "onSaveClick: saved")
                _state.update {
                    it.copy(
                        isSaving = false,
                        showEditSheet = false,
                        displayName = identity.displayName,
                        bio = identity.bio,
                        avatarInitial = identity.avatarInitial,
                    )
                }
            }.onFailure { err ->
                Log.e(TAG, "onSaveClick: FAILED — ${err.message}", err)
                _state.update { it.copy(isSaving = false) }
                if (err.requiresReauth()) {
                    handleSessionExpired()
                } else {
                    _effects.emit(ProfileEffect.ShowError(err.message ?: "Could not save profile."))
                }
            }
        }
    }

    fun onShareClick() {
        val pubky = _state.value.pubky
        if (pubky.isNotBlank()) {
            viewModelScope.launch { _effects.emit(ProfileEffect.ShareProfile("pubky://$pubky")) }
        }
    }

    /** Best-effort sign-out + redirect to onboarding when the session can't be refreshed. */
    private suspend fun handleSessionExpired() {
        Log.d(TAG, "handleSessionExpired: session expired — signing out")
        runSuspendCatching { identityRepository.signOut() }
        _state.update { it.copy(isLoading = false, isSaving = false, showEditSheet = false) }
        _effects.emit(ProfileEffect.NavigateToOnboarding)
    }

    fun onSignOutClick() {
        viewModelScope.launch {
            Log.d(TAG, "onSignOutClick: signing out")
            identityRepository.signOut()
            _effects.emit(ProfileEffect.NavigateToOnboarding)
        }
    }

    companion object {
        private const val TAG = "Loopky/ProfileVM"
    }
}

data class ProfileUiState(
    val isLoading: Boolean = true,
    val displayName: String? = null,
    val pubky: String = "",
    val bio: String? = null,
    val avatarInitial: Char = '?',
    val deckCount: Int = 0,
    val cardCount: Int = 0,
    val dueCount: Int = 0,
    val showEditSheet: Boolean = false,
    val editName: String = "",
    val editBio: String = "",
    val isSaving: Boolean = false,
)

sealed interface ProfileEffect {
    data object NavigateToOnboarding : ProfileEffect
    data class ShareProfile(val uri: String) : ProfileEffect
    data class ShowError(val message: String) : ProfileEffect
}
