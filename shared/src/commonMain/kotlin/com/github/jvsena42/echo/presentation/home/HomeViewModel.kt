package com.github.jvsena42.echo.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.requiresReauth
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
import com.github.jvsena42.echo.domain.model.Deck
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

class HomeViewModel(
    private val identityRepository: IdentityRepository,
    private val deckRepository: DeckRepository,
    private val srsRepository: SrsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching session + decks")
            _state.update { HomeUiState.Loading }
            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val greetingName = session?.identity?.displayName?.takeIf { it.isNotBlank() }
                ?: session?.identity?.pubky?.let { "pk:${it.take(PUBKY_PREFIX_LEN)}" }
                ?: "there"

            runCatching { deckRepository.listOwned() }
                .onSuccess { decks ->
                    _state.update { if (decks.isEmpty()) {
                        HomeUiState.Empty(greetingName)
                    } else {
                        val dueByDeck = runCatching { srsRepository.dueToday() }
                            .getOrDefault(emptyList())
                            .groupingBy { it.deckId }
                            .eachCount()
                        HomeUiState.Content(
                            greetingName = greetingName,
                            dueToday = dueByDeck.values.sum(),
                            // No persisted session history in v1; "done today" is tracked
                            // within the study session screen, not here.
                            doneToday = 0,
                            decks = decks.map { it.toSummary(dueByDeck[it.id] ?: 0) },
                        )
                    } }
                    Log.d(TAG, "load: decks=${decks.size}")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    if (err.requiresReauth()) {
                        Log.d(TAG, "load: session expired — signing out")
                        runCatching { identityRepository.signOut() }
                        _state.update { HomeUiState.Error(
                            greetingName = greetingName,
                            message = "Your session expired. Please sign in again.",
                        ) }
                        _effects.emit(HomeEffect.NavigateToOnboarding)
                    } else {
                        _state.update { HomeUiState.Error(
                            greetingName = greetingName,
                            message = err.message ?: "Could not load decks.",
                        ) }
                    }
                }
        }
    }

    fun onStartStudyClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateStartStudy) }
    }

    fun onCreateDeckClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateCreateDeck) }
    }

    fun onBrowseExamplesClick() {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateBrowseExamples) }
    }

    fun onDeckClick(deckId: String) {
        viewModelScope.launch { _effects.emit(HomeEffect.NavigateDeck(deckId)) }
    }

    private fun Deck.toSummary(dueCount: Int): DeckSummary = DeckSummary(
        id = id,
        title = title,
        cardCount = cardCount,
        dueCount = dueCount,
        coverInitial = title.firstOrNull()?.uppercaseChar() ?: '•',
    )

    companion object {
        private const val TAG = "Echo/HomeVM"
        private const val PUBKY_PREFIX_LEN = 6
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Empty(val greetingName: String) : HomeUiState
    data class Content(
        val greetingName: String,
        val dueToday: Int,
        val doneToday: Int,
        val decks: List<DeckSummary>,
    ) : HomeUiState
    data class Error(val greetingName: String, val message: String) : HomeUiState
}

data class DeckSummary(
    val id: String,
    val title: String,
    val cardCount: Int,
    val dueCount: Int,
    val coverInitial: Char,
)

sealed interface HomeEffect {
    data object NavigateCreateDeck : HomeEffect
    data object NavigateBrowseExamples : HomeEffect
    data object NavigateStartStudy : HomeEffect
    data class NavigateDeck(val deckId: String) : HomeEffect

    /** The stored session can no longer be used — the user must sign in again. */
    data object NavigateToOnboarding : HomeEffect
}
