package com.github.jvsena42.eco.presentation.profile

import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.DiscoveryRepository
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.domain.model.Deck
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Another user's public profile: their pubky.app profile (display name, bio, avatar initial), a grid
 * of their published decks, and a Follow / Unfollow toggle. Reads are public; the follow toggle
 * writes to the current user's pubky.app follows via [DiscoveryRepository].
 */
class FriendProfileViewModel(
    private val targetPubky: String,
    private val identityRepository: IdentityRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val deckRepository: DeckRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(FriendProfileUiState(pubky = targetPubky))
    val state: StateFlow<FriendProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FriendProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<FriendProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var followJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.update { it.copy(isLoading = true) }

            val profile = identityRepository.fetchProfile(targetPubky).getOrNull()
            val decks = runCatching { deckRepository.listByAuthor(targetPubky) }.getOrElse {
                Log.e(TAG, "load: listByAuthor failed — ${it.message}", it)
                emptyList()
            }
            val isFollowing = runCatching { discoveryRepository.isFollowing(targetPubky) }
                .getOrDefault(false)

            val displayName = profile?.displayName
            _state.update {
                it.copy(
                    isLoading = false,
                    displayName = displayName,
                    bio = profile?.bio,
                    avatarInitial = displayName?.firstOrNull()?.uppercaseChar()
                        ?: targetPubky.firstOrNull()?.uppercaseChar() ?: '?',
                    isFollowing = isFollowing,
                    decks = decks.map { it.toCard() },
                )
            }
            Log.d(TAG, "load: decks=${decks.size} following=$isFollowing")
        }
    }

    fun onToggleFollow() {
        if (followJob?.isActive == true) return
        followJob = scope.launch {
            val wasFollowing = _state.value.isFollowing
            // Optimistic flip; revert on failure.
            _state.update { it.copy(isFollowing = !wasFollowing, isProcessingFollow = true) }

            val result = if (wasFollowing) {
                discoveryRepository.unfollowUser(targetPubky)
            } else {
                discoveryRepository.followUser(targetPubky)
            }
            result
                .onSuccess { _state.update { it.copy(isProcessingFollow = false) } }
                .onFailure { err ->
                    Log.e(TAG, "onToggleFollow: FAILED — ${err.message}", err)
                    _state.update { it.copy(isFollowing = wasFollowing, isProcessingFollow = false) }
                    _effects.emit(FriendProfileEffect.ShowError(err.message ?: "Could not update follow."))
                }
        }
    }

    fun onCopyPubky() {
        scope.launch { _effects.emit(FriendProfileEffect.CopyToClipboard(targetPubky)) }
    }

    fun onOpenDeck(deckId: String) {
        scope.launch { _effects.emit(FriendProfileEffect.OpenDeck(targetPubky, deckId)) }
    }

    fun onDispose() {
        loadJob?.cancel()
        followJob?.cancel()
        scope.cancel()
    }

    private fun Deck.toCard(): FriendDeck = FriendDeck(
        id = id,
        title = title,
        cardCount = cardCount,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
        tags = tags.map { it.value },
    )

    companion object {
        private const val TAG = "Echo/FriendProfileVM"
    }
}

data class FriendProfileUiState(
    val isLoading: Boolean = true,
    val pubky: String = "",
    val displayName: String? = null,
    val bio: String? = null,
    val avatarInitial: Char = '?',
    val isFollowing: Boolean = false,
    val isProcessingFollow: Boolean = false,
    val decks: List<FriendDeck> = emptyList(),
)

data class FriendDeck(
    val id: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val tags: List<String>,
)

sealed interface FriendProfileEffect {
    data class CopyToClipboard(val text: String) : FriendProfileEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : FriendProfileEffect
    data class ShowError(val message: String) : FriendProfileEffect
}
