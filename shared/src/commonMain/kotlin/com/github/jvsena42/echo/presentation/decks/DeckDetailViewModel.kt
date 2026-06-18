package com.github.jvsena42.echo.presentation.decks

import com.github.jvsena42.echo.data.repository.CardRepository
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.util.Log
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

@Suppress("LongParameterList")
class DeckDetailViewModel(
    private val deckId: String,
    private val authorPubky: String? = null,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val srsRepository: SrsRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<DeckDetailUiState>(DeckDetailUiState.Loading)
    val state: StateFlow<DeckDetailUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckDetailEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckDetailEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            Log.d(TAG, "load: deckId=$deckId")
            _state.value = DeckDetailUiState.Loading

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val myPubky = session?.identity?.pubky

            var deck = deckRepository.getLocal(deckId)
            if (deck == null && authorPubky != null) {
                Log.d(TAG, "load: cache miss, fetching remote author=$authorPubky")
                deck = deckRepository.fetchRemote(authorPubky, deckId)
                    .onFailure { err ->
                        Log.e(TAG, "load: remote fetch FAILED — ${err::class.simpleName}: ${err.message}", err)
                    }
                    .getOrNull()
            }
            if (deck == null) {
                _state.value = DeckDetailUiState.Error("Deck not found.")
                return@launch
            }

            runCatching { cardRepository.listByDeck(deckId) }
                .onSuccess { cards ->
                    val dueCount = runCatching { srsRepository.dueForDeck(deckId).size }
                        .getOrDefault(0)
                    val mastered = masteredPercent(cards)
                    _state.value = deck.toContent(cards, myPubky, dueCount, mastered)
                    Log.d(TAG, "load: cards=${cards.size} due=$dueCount mastered=$mastered")
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.value = DeckDetailUiState.Error(
                        err.message ?: "Could not load deck.",
                    )
                }
        }
    }

    fun onBackClick() {
        scope.launch { _effects.emit(DeckDetailEffect.NavigateBack) }
    }

    fun onShareClick() {
        scope.launch {
            val deck = deckRepository.getLocal(deckId) ?: return@launch
            _effects.emit(DeckDetailEffect.Share(deck.pubkyUri.value))
        }
    }

    fun onStudyClick() {
        scope.launch { _effects.emit(DeckDetailEffect.NavigateStudy) }
    }

    fun onEditClick() {
        scope.launch { _effects.emit(DeckDetailEffect.NavigateEditDeck(deckId)) }
    }

    fun onDeleteDeck() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.value = current.copy(showDeleteConfirm = true)
    }

    fun onDismissDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.value = current.copy(showDeleteConfirm = false)
    }

    fun onConfirmDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.value = current.copy(showDeleteConfirm = false, isDeleting = true)
        scope.launch {
            Log.d(TAG, "onConfirmDelete: deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess { _effects.emit(DeckDetailEffect.Deleted) }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmDelete: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.value = DeckDetailUiState.Error(
                        err.message ?: "Could not delete deck.",
                    )
                }
        }
    }

    fun onDispose() {
        loadJob?.cancel()
        scope.cancel()
    }

    /**
     * Share of cards whose review interval has reached SM-2's "mature" threshold.
     * `dueForDeck` has already warmed the per-session SRS cache, so [SrsRepository.stateFor]
     * is a cheap lookup here. "—" until the deck has cards.
     */
    private suspend fun masteredPercent(cards: List<Card>): String {
        if (cards.isEmpty()) return "—"
        val mastered = cards.count { card ->
            val state = runCatching { srsRepository.stateFor(card.id) }.getOrNull()
            state != null && state.intervalDays >= MATURE_INTERVAL_DAYS
        }
        return "${mastered * PERCENT / cards.size}%"
    }

    private fun Deck.toContent(
        cards: List<Card>,
        myPubky: String?,
        dueCount: Int,
        mastered: String,
    ): DeckDetailUiState.Content {
        val isOwned = authorPubky == myPubky
        return DeckDetailUiState.Content(
            deckId = id,
            title = title,
            description = description,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
            authorName = null,
            authorPubky = authorPubky,
            authorInitial = authorPubky.firstOrNull()?.uppercaseChar() ?: '?',
            isOwned = isOwned,
            tags = tags.map { it.value },
            totalCards = cardCount,
            dueCards = dueCount,
            masteredPercent = mastered,
            cardPreviews = cards.map { it.toPreview() },
        )
    }

    private fun Card.toPreview(): CardPreviewModel = CardPreviewModel(
        id = id,
        frontText = front.text ?: "",
        backText = back.text ?: "",
    )

    companion object {
        private const val TAG = "Echo/DeckDetailVM"

        /** SM-2 convention: a card with a ≥21-day interval counts as mature/mastered. */
        private const val MATURE_INTERVAL_DAYS = 21
        private const val PERCENT = 100
    }
}

sealed interface DeckDetailUiState {
    data object Loading : DeckDetailUiState
    data class Content(
        val deckId: String,
        val title: String,
        val description: String?,
        val coverEmoji: String,
        val authorName: String?,
        val authorPubky: String,
        val authorInitial: Char,
        val isOwned: Boolean,
        val tags: List<String>,
        val totalCards: Int,
        val dueCards: Int,
        val masteredPercent: String,
        val cardPreviews: List<CardPreviewModel>,
        val showDeleteConfirm: Boolean = false,
        val isDeleting: Boolean = false,
    ) : DeckDetailUiState
    data class Error(val message: String) : DeckDetailUiState
}

data class CardPreviewModel(
    val id: String,
    val frontText: String,
    val backText: String,
)

sealed interface DeckDetailEffect {
    data object NavigateBack : DeckDetailEffect
    data class NavigateEditDeck(val deckId: String) : DeckDetailEffect
    data object NavigateStudy : DeckDetailEffect
    data class Share(val uri: String) : DeckDetailEffect
    data object Deleted : DeckDetailEffect
}
