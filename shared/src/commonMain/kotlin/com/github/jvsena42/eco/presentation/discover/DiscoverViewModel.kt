package com.github.jvsena42.eco.presentation.discover

import com.github.jvsena42.eco.data.repository.DiscoveryRepository
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.Tag
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
import kotlinx.coroutines.launch

/**
 * Discover feed: decks from people the user follows, with a tag-chip filter. Trending tags and
 * global search are intentionally absent — they need a backend indexer the FFI can't provide
 * (see [com.github.jvsena42.eco.data.repository.TagRepository.trending]).
 */
class DiscoverViewModel(
    private val discoveryRepository: DiscoveryRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DiscoverEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DiscoverEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    /** Full feed kept so tag filtering is a local pass with no extra network round-trips. */
    private var feed: List<Deck> = emptyList()

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.value = DiscoverUiState.Loading
            runCatching { discoveryRepository.decksFromFollowing() }
                .onSuccess { decks ->
                    feed = decks
                    if (decks.isEmpty()) {
                        _state.value = DiscoverUiState.Empty
                    } else {
                        _state.value = DiscoverUiState.Content(
                            tags = decks.flatMap { it.tags }.distinct(),
                            selectedTag = null,
                            decks = decks.map { it.toCard() },
                        )
                    }
                    Log.d(TAG, "load: feed=${decks.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.value = DiscoverUiState.Error(err.message ?: "Could not load Discover.")
                }
        }
    }

    fun onTagSelected(tag: Tag?) {
        val current = _state.value as? DiscoverUiState.Content ?: return
        val next = if (tag == current.selectedTag) null else tag
        val filtered = if (next == null) feed else feed.filter { next in it.tags }
        _state.value = current.copy(selectedTag = next, decks = filtered.map { it.toCard() })
    }

    fun onAddFriend() {
        scope.launch { _effects.emit(DiscoverEffect.OpenAddFriend) }
    }

    fun onOpenAuthor(pubky: String) {
        scope.launch { _effects.emit(DiscoverEffect.OpenProfile(pubky)) }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        scope.launch { _effects.emit(DiscoverEffect.OpenDeck(authorPubky, deckId)) }
    }

    fun onDispose() {
        loadJob?.cancel()
        scope.cancel()
    }

    private fun Deck.toCard(): DiscoverDeck = DiscoverDeck(
        id = id,
        authorPubky = authorPubky,
        title = title,
        cardCount = cardCount,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
        authorLabel = "@${authorPubky.take(PUBKY_LABEL_LEN)}",
        tags = tags.map { it.value },
    )

    companion object {
        private const val TAG = "Echo/DiscoverVM"
        private const val PUBKY_LABEL_LEN = 6
    }
}

sealed interface DiscoverUiState {
    data object Loading : DiscoverUiState
    data object Empty : DiscoverUiState
    data class Content(
        val tags: List<Tag>,
        val selectedTag: Tag?,
        val decks: List<DiscoverDeck>,
    ) : DiscoverUiState
    data class Error(val message: String) : DiscoverUiState
}

data class DiscoverDeck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val authorLabel: String,
    val tags: List<String>,
)

sealed interface DiscoverEffect {
    data object OpenAddFriend : DiscoverEffect
    data class OpenProfile(val pubky: String) : DiscoverEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : DiscoverEffect
}
