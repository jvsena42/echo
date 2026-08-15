package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DecksLibraryViewModel(
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DecksLibraryUiState>(DecksLibraryUiState.Loading)
    val state: StateFlow<DecksLibraryUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DecksLibraryEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DecksLibraryEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
        // Publish and delete run on other destinations while this tab stays composed, so
        // without this the grid keeps showing "No decks yet" right after a publish, and keeps
        // listing a deck that was just deleted.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching decks (silent=$silent)")
            if (!silent) _state.update { DecksLibraryUiState.Loading }
            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val myIdentity = session?.identity

            runCatching { deckRepository.listOwned() }
                .onSuccess { decks ->
                    if (decks.isEmpty()) {
                        _state.update { DecksLibraryUiState.Empty }
                    } else {
                        _state.update { DecksLibraryUiState.Content(
                            deckCount = decks.size,
                            decks = decks.map { it.toTileModel(myIdentity) },
                        ) }
                    }
                    Log.d(TAG, "load: decks=${decks.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DecksLibraryUiState.Error(reason = err.toErrorReason()) }
                }
        }
    }

    fun onQueryChanged(query: String) {
        _state.update { s -> if (s is DecksLibraryUiState.Content) s.copy(query = query) else s }
    }

    fun onSortChanged(sort: DeckSort) {
        _state.update { s -> if (s is DecksLibraryUiState.Content) s.copy(sort = sort) else s }
    }

    fun onDeckClick(deckId: String) {
        viewModelScope.launch { _effects.emit(DecksLibraryEffect.NavigateDeckDetail(deckId)) }
    }

    fun onImportClick() {
        viewModelScope.launch { _effects.emit(DecksLibraryEffect.NavigateImport) }
    }

    fun onCreateDeckClick() {
        viewModelScope.launch { _effects.emit(DecksLibraryEffect.NavigateCreateDeck) }
    }

    /**
     * The library only lists decks you own, so the author is the session identity — no profile
     * lookup needed. Naming the author is the platform layer's job; this only carries who it is.
     */
    private fun Deck.toTileModel(myIdentity: PubkyIdentity?): DeckTileModel {
        val isOwned = authorPubky == myIdentity?.pubky
        return DeckTileModel(
            id = id,
            title = title,
            cardCount = cardCount,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
            author = myIdentity?.takeIf { isOwned }
                ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
            isOwned = isOwned,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private const val TAG = "Loopky/DecksLibVM"
    }
}

sealed interface DecksLibraryUiState {
    data object Loading : DecksLibraryUiState
    data object Empty : DecksLibraryUiState
    data class Content(
        val deckCount: Int,
        val decks: List<DeckTileModel>,
        val query: String = "",
        val sort: DeckSort = DeckSort.Recent,
    ) : DecksLibraryUiState {
        /**
         * Filtering and sorting happen over the already-loaded list — the library is small and
         * Pubky has no query API, so there is nothing to gain from a round trip.
         */
        val visibleDecks: List<DeckTileModel>
            get() = decks
                .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
                .let { filtered ->
                    when (sort) {
                        DeckSort.Recent -> filtered.sortedByDescending { it.updatedAt }
                        DeckSort.Alphabetical -> filtered.sortedBy { it.title.lowercase() }
                        DeckSort.CardCount -> filtered.sortedByDescending { it.cardCount }
                    }
                }
    }
    data class Error(val reason: ErrorReason) : DecksLibraryUiState
}

enum class DeckSort { Recent, Alphabetical, CardCount }

data class DeckTileModel(
    val id: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val author: PubkyIdentity,
    val isOwned: Boolean,
    val updatedAt: Long,
)

sealed interface DecksLibraryEffect {
    data class NavigateDeckDetail(val deckId: String) : DecksLibraryEffect
    data object NavigateImport : DecksLibraryEffect
    data object NavigateCreateDeck : DecksLibraryEffect
}
