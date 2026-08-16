package com.github.jvsena42.loopky.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.Log
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
 * Every deck on the network carrying one tag — where a tag chip goes when it is tapped outside
 * Discover (deck detail, a profile), per the design brief's rule that a tag leads to a
 * tag-filtered view.
 *
 * A screen with real modes, unlike Discover: there is one thing to load, so it is genuinely
 * loading, or empty, or showing content, or broken. Asks for more decks than Discover's strip
 * because this is the whole screen rather than one band of it.
 */
class TagBrowseViewModel(
    private val tag: Tag,
    private val discoveryRepository: DiscoveryRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<TagBrowseUiState>(TagBrowseUiState.Loading)
    val state: StateFlow<TagBrowseUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TagBrowseEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<TagBrowseEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private val authors = mutableMapOf<String, PubkyIdentity>()

    val label: String get() = tag.value

    init {
        load()
    }

    fun onRetry() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { TagBrowseUiState.Loading }
            // Indexer-backed and documented never to throw, so an empty result is the failure
            // mode too — there is nothing to distinguish "offline" from "nobody tagged this" yet.
            val decks = runCatching { discoveryRepository.decksByTagGlobal(tag, BROWSE_LIMIT) }
                .onFailure { Log.e(TAG, "load('${tag.value}'): FAILED — ${it.message}", it) }
                .getOrElse { emptyList() }

            Log.d(TAG, "load('${tag.value}'): ${decks.size} decks")
            _state.update {
                if (decks.isEmpty()) TagBrowseUiState.Empty else TagBrowseUiState.Content(decks.toCards(authors))
            }
            loadAuthorProfiles(decks)
        }
    }

    /** Names land after first paint, as everywhere else; a tile shows the pubky until then. */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }.awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { current ->
            if (current !is TagBrowseUiState.Content) {
                current
            } else {
                current.copy(
                    decks = current.decks.map { it.copy(author = authors[it.authorPubky] ?: it.author) },
                )
            }
        }
    }

    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(TagBrowseEffect.OpenDeck(authorPubky, deckId)) }
    }

    fun onOpenAuthor(pubky: String) {
        viewModelScope.launch { _effects.emit(TagBrowseEffect.OpenProfile(pubky)) }
    }

    companion object {
        private const val TAG = "Loopky/TagBrowseVM"

        /** A whole screen rather than one strip, so it takes the repository's own default. */
        internal const val BROWSE_LIMIT = TagRepository.DEFAULT_TAGGED_LIMIT
    }
}

sealed interface TagBrowseUiState {
    data object Loading : TagBrowseUiState
    data object Empty : TagBrowseUiState
    data class Content(val decks: List<DiscoverDeck>) : TagBrowseUiState
}

sealed interface TagBrowseEffect {
    data class OpenDeck(val authorPubky: String, val deckId: String) : TagBrowseEffect
    data class OpenProfile(val pubky: String) : TagBrowseEffect
}
