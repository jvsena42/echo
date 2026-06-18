package com.github.jvsena42.echo.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.requiresReauth
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.util.Log
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
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching profile + stats")
            _state.update { it.copy(isLoading = true) }

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()

            if (session == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            val pubky = session.identity.pubky

            // Fetch fresh profile from homeserver
            val profile = runCatching {
                identityRepository.fetchProfile(pubky).getOrNull()
            }.getOrNull() ?: session.identity

            // Fetch deck stats
            val decksResult = runCatching { deckRepository.listOwned() }
            if (decksResult.exceptionOrNull()?.requiresReauth() == true) {
                handleSessionExpired()
                return@launch
            }
            val decks = decksResult.getOrElse { emptyList() }
            val deckCount = decks.size
            val cardCount = decks.sumOf { it.cardCount }

            val displayName = profile.displayName ?: session.identity.displayName
            _state.update {
                it.copy(
                    isLoading = false,
                    displayName = displayName,
                    pubky = pubky,
                    bio = profile.bio ?: session.identity.bio,
                    avatarInitial = displayName?.firstOrNull()?.uppercaseChar()
                        ?: pubky.firstOrNull()?.uppercaseChar()
                        ?: '?',
                    deckCount = deckCount,
                    cardCount = cardCount,
                )
            }
            Log.d(TAG, "load: done — decks=$deckCount cards=$cardCount")
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
                        avatarInitial = identity.displayName?.firstOrNull()?.uppercaseChar()
                            ?: identity.pubky.firstOrNull()?.uppercaseChar()
                            ?: '?',
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
        runCatching { identityRepository.signOut() }
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
        private const val TAG = "Echo/ProfileVM"
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
    val streakDays: Int = 0,
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
