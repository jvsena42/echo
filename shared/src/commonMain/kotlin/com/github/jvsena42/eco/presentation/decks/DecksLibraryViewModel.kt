package com.github.jvsena42.eco.presentation.decks

import com.github.jvsena42.eco.data.repository.DeckRepository
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
import kotlinx.coroutines.launch

class DecksLibraryViewModel(
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<DecksLibraryUiState>(DecksLibraryUiState.Loading)
    val state: StateFlow<DecksLibraryUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DecksLibraryEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DecksLibraryEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            Log.d(TAG, "load: fetching decks")
            _state.value = DecksLibraryUiState.Loading
            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val myPubky = session?.identity?.pubky

            runCatching { deckRepository.listOwned() }
                .onSuccess { decks ->
                    if (decks.isEmpty()) {
                        _state.value = DecksLibraryUiState.Empty
                    } else {
                        _state.value = DecksLibraryUiState.Content(
                            deckCount = decks.size,
                            decks = decks.map { it.toTileModel(myPubky) },
                        )
                    }
                    Log.d(TAG, "load: decks=${decks.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.value = DecksLibraryUiState.Error(
                        message = err.message ?: "Could not load decks.",
                    )
                }
        }
    }

    fun onDeckClick(deckId: String) {
        scope.launch { _effects.emit(DecksLibraryEffect.NavigateDeckDetail(deckId)) }
    }

    fun onImportClick() {
        scope.launch { _effects.emit(DecksLibraryEffect.NavigateImport) }
    }

    fun onCreateDeckClick() {
        scope.launch { _effects.emit(DecksLibraryEffect.NavigateCreateDeck) }
    }

    fun onDispose() {
        loadJob?.cancel()
        scope.cancel()
    }

    private fun Deck.toTileModel(myPubky: String?): DeckTileModel = DeckTileModel(
        id = id,
        title = title,
        cardCount = cardCount,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
        authorLabel = if (authorPubky == myPubky) "@you" else "@${authorPubky.take(6)}",
    )

    companion object {
        private const val TAG = "Echo/DecksLibVM"
    }
}

sealed interface DecksLibraryUiState {
    data object Loading : DecksLibraryUiState
    data object Empty : DecksLibraryUiState
    data class Content(
        val deckCount: Int,
        val decks: List<DeckTileModel>,
    ) : DecksLibraryUiState
    data class Error(val message: String) : DecksLibraryUiState
}

data class DeckTileModel(
    val id: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    val authorLabel: String,
)

sealed interface DecksLibraryEffect {
    data class NavigateDeckDetail(val deckId: String) : DecksLibraryEffect
    data object NavigateImport : DecksLibraryEffect
    data object NavigateCreateDeck : DecksLibraryEffect
}
