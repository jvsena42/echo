package com.github.jvsena42.echo.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.toErrorReason
import com.github.jvsena42.echo.data.repository.DiscoveryRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.TagRepository
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.ErrorReason
import com.github.jvsena42.echo.domain.model.PubkyIdentity
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Discover feed: decks from people the user follows, with a tag-chip filter. Local feed tags
 * are merged with network-wide trending tags from the Nexus indexer
 * ([TagRepository.trending]); trending is best-effort and the feed renders without it.
 */
class DiscoverViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val tagRepository: TagRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DiscoverEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DiscoverEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    /** Full feed kept so tag filtering is a local pass with no extra network round-trips. */
    private var feed: List<Deck> = emptyList()

    /** Resolved author profiles, so tag filtering re-renders tiles with names already in hand. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { DiscoverUiState.Loading }
            runCatching { discoveryRepository.decksFromFollowing() }
                .onSuccess { decks ->
                    feed = decks
                    if (decks.isEmpty()) {
                        _state.update { DiscoverUiState.Empty }
                    } else {
                        _state.update { DiscoverUiState.Content(
                            tags = decks.flatMap { it.tags }.distinct(),
                            trendingTags = emptyList(),
                            selectedTag = null,
                            decks = decks.map { it.toCard() },
                        ) }
                        loadTrending()
                        loadAuthorProfiles(decks)
                    }
                    Log.d(TAG, "load: feed=${decks.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.update { DiscoverUiState.Error(err.toErrorReason()) }
                }
        }
    }

    /** Trending arrives after first paint — the feed never waits on the indexer. */
    private fun loadTrending() {
        viewModelScope.launch {
            val trending = tagRepository.trending()
            val current = _state.value as? DiscoverUiState.Content ?: return@launch
            _state.update { current.copy(trendingTags = trending - current.tags.toSet()) }
        }
    }

    /**
     * Author names arrive after first paint, like trending — a tile shows the pubky until its
     * author's profile lands, and an author with no published profile simply keeps it.
     */
    private fun loadAuthorProfiles(decks: List<Deck>) {
        viewModelScope.launch {
            val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
            if (pending.isEmpty()) return@launch
            val resolved = coroutineScope {
                pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
            }
            resolved.filterNotNull().forEach { authors[it.pubky] = it }
            if (resolved.all { it == null }) return@launch
            _state.update { current ->
                if (current is DiscoverUiState.Content) {
                    current.copy(decks = current.decks.map { it.copy(author = authors[it.authorPubky] ?: it.author) })
                } else {
                    current
                }
            }
            Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
        }
    }

    fun onTagSelected(tag: Tag?) {
        val current = _state.value as? DiscoverUiState.Content ?: return
        val next = if (tag == current.selectedTag) null else tag
        val filtered = if (next == null) feed else feed.filter { next in it.tags }
        _state.update { current.copy(selectedTag = next, decks = filtered.map { it.toCard() }) }
    }

    fun onAddFriend() {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenAddFriend) }
    }

    fun onOpenAuthor(pubky: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenProfile(pubky)) }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(DiscoverEffect.OpenDeck(authorPubky, deckId)) }
    }

    private fun Deck.toCard(): DiscoverDeck = DiscoverDeck(
        id = id,
        authorPubky = authorPubky,
        title = title,
        cardCount = cardCount,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
        author = authors[authorPubky]
            ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
        tags = tags.map { it.value },
    )

    companion object {
        private const val TAG = "Echo/DiscoverVM"
    }
}

sealed interface DiscoverUiState {
    data object Loading : DiscoverUiState
    data object Empty : DiscoverUiState
    data class Content(
        val tags: List<Tag>,
        /** Network-wide trending labels from Nexus, minus ones already in [tags]. */
        val trendingTags: List<Tag> = emptyList(),
        val selectedTag: Tag?,
        val decks: List<DiscoverDeck>,
    ) : DiscoverUiState
    data class Error(val reason: ErrorReason) : DiscoverUiState
}

data class DiscoverDeck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val author: PubkyIdentity,
    val tags: List<String>,
)

sealed interface DiscoverEffect {
    data object OpenAddFriend : DiscoverEffect
    data class OpenProfile(val pubky: String) : DiscoverEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : DiscoverEffect
}
