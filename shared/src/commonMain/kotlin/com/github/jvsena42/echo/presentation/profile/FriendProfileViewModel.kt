package com.github.jvsena42.echo.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.toErrorReason
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.DiscoveryRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.ErrorReason
import com.github.jvsena42.echo.domain.model.PubkyIdentity
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
) : ViewModel() {
    private val _state = MutableStateFlow(
        FriendProfileUiState(
            identity = PubkyIdentity(targetPubky, displayName = null, avatarUrl = null, bio = null),
        ),
    )
    val state: StateFlow<FriendProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FriendProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<FriendProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var followJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load(forceRefresh = true)

    /** [forceRefresh] reads the profile past the shared cache, for an explicit pull-to-refresh. */
    private fun load(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val profile = identityRepository.fetchProfile(targetPubky, forceRefresh).getOrNull()
            val decks = runCatching { deckRepository.listByAuthor(targetPubky) }.getOrElse {
                Log.e(TAG, "load: listByAuthor failed — ${it.message}", it)
                emptyList()
            }
            val isFollowing = runCatching { discoveryRepository.isFollowing(targetPubky) }
                .getOrDefault(false)
            // Pasting your own pubky into the add-friend sheet used to open this screen with a
            // live Follow button — you could follow yourself.
            val myPubky = runCatching { identityRepository.currentSession()?.identity?.pubky }
                .getOrNull()
            val isSelf = myPubky != null && myPubky == targetPubky

            val identity = profile ?: PubkyIdentity(targetPubky, displayName = null, avatarUrl = null, bio = null)
            _state.update {
                it.copy(
                    isLoading = false,
                    identity = identity,
                    isFollowing = isFollowing,
                    isSelf = isSelf,
                    decks = decks.map { it.toCard() },
                )
            }
            Log.d(TAG, "load: decks=${decks.size} following=$isFollowing")
        }
    }

    fun onToggleFollow() {
        if (followJob?.isActive == true || _state.value.isSelf) return
        followJob = viewModelScope.launch {
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
                    // Was emitted into an empty lambda whose comment claimed it was
                    // "surfaced inline below"; nothing rendered it at all.
                    _state.update { it.copy(errorReason = err.toErrorReason()) }
                }
        }
    }

    fun onCopyPubky() {
        viewModelScope.launch { _effects.emit(FriendProfileEffect.CopyToClipboard(targetPubky)) }
    }

    fun onOpenDeck(deckId: String) {
        viewModelScope.launch { _effects.emit(FriendProfileEffect.OpenDeck(targetPubky, deckId)) }
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
    /** Who this profile belongs to — the name, avatar and pubky all read off this one value. */
    val identity: PubkyIdentity,
    val isFollowing: Boolean = false,
    val isProcessingFollow: Boolean = false,
    /** True when this is the signed-in user's own profile — no Follow button. */
    val isSelf: Boolean = false,
    /** Set when a follow/unfollow fails; rendered inline. */
    val errorReason: ErrorReason? = null,
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
}
