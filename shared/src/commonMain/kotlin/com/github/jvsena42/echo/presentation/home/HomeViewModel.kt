package com.github.jvsena42.echo.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.requiresReauth
import com.github.jvsena42.echo.data.pubky.toErrorReason
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
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
        // Publishing and deleting happen on other destinations while this tab stays composed,
        // so without this a new deck doesn't show up until the process restarts.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
        // Same reason for reviews: studying runs on its own destination, so without this the
        // "due today" count and the per-deck badges keep the values they had before the session.
        viewModelScope.launch {
            srsRepository.changes.collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching session + decks (silent=$silent)")
            if (!silent) _state.update { HomeUiState.Loading }
            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            // Carry who the user is, not what to call them — the platform layer owns the words.
            val identity = session?.identity

            runCatching { deckRepository.listOwned() }
                .onSuccess { decks ->
                    _state.update { if (decks.isEmpty()) {
                        HomeUiState.Empty(identity)
                    } else {
                        val dueByDeck = runCatching { srsRepository.dueToday() }
                            .getOrDefault(emptyList())
                            .groupingBy { it.deckId }
                            .eachCount()
                        val dueCount = dueByDeck.values.sum()
                        HomeUiState.Content(
                            identity = identity,
                            dueToday = dueCount,
                            // No persisted session history in v1; "done today" is tracked
                            // within the study session screen, not here.
                            doneToday = 0,
                            decks = decks.map { it.toSummary(dueByDeck[it.id] ?: 0) },
                            // Only meaningful when the queue is empty; skip the lookup otherwise.
                            nextDueAtMillis = if (dueCount == 0) {
                                runCatching { srsRepository.nextDueAt() }.getOrNull()
                            } else {
                                null
                            },
                        )
                    } }
                    Log.d(TAG, "load: decks=${decks.size}")
                    refreshIdentity(identity)
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    if (err.requiresReauth()) {
                        Log.d(TAG, "load: session expired — signing out")
                        runCatching { identityRepository.signOut() }
                        _state.update { HomeUiState.Error(
                            identity = identity,
                            reason = ErrorReason.SessionExpired,
                        ) }
                        _effects.emit(HomeEffect.NavigateToOnboarding)
                    } else {
                        _state.update { HomeUiState.Error(
                            identity = identity,
                            reason = err.toErrorReason(),
                        ) }
                    }
                }
        }
    }

    /**
     * The session's copy of the name is written at sign-in, so a name edited on Profile would
     * otherwise never reach the greeting. Runs after first paint — the greeting never waits on it.
     */
    private suspend fun refreshIdentity(stored: PubkyIdentity?) {
        if (stored == null) return
        val fresh = identityRepository.fetchProfile(stored.pubky).getOrNull() ?: return
        if (fresh.displayName == stored.displayName) return
        _state.update { current ->
            when (current) {
                is HomeUiState.Content -> current.copy(identity = fresh)
                is HomeUiState.Empty -> HomeUiState.Empty(fresh)
                is HomeUiState.Error -> current.copy(identity = fresh)
                HomeUiState.Loading -> current
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
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** [identity] is null only while signed out — the screen greets a nameless user then. */
    data class Empty(val identity: PubkyIdentity?) : HomeUiState
    data class Content(
        val identity: PubkyIdentity?,
        val dueToday: Int,
        val doneToday: Int,
        val decks: List<DeckSummary>,
        /**
         * When the next card becomes reviewable, if [dueToday] is 0. Lets the UI say "you're
         * caught up, next review in 4h" instead of reusing the no-decks empty state, which told
         * users who owned decks to "create or import a deck".
         */
        val nextDueAtMillis: Long? = null,
    ) : HomeUiState {
        val isCaughtUp: Boolean get() = dueToday == 0
    }
    data class Error(val identity: PubkyIdentity?, val reason: ErrorReason) : HomeUiState
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
