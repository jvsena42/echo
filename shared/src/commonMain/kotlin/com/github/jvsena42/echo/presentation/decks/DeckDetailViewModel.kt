package com.github.jvsena42.echo.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.pubky.toErrorReason
import com.github.jvsena42.echo.data.repository.CardRepository
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.data.repository.MediaRepository
import com.github.jvsena42.echo.data.repository.SrsRepository
import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.ErrorReason
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.domain.model.orderedBy
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Suppress("LongParameterList")
class DeckDetailViewModel(
    private val deckId: String,
    private val authorPubky: String? = null,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    private val srsRepository: SrsRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
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
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId")
            _state.update { DeckDetailUiState.Loading }

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
                _state.update { DeckDetailUiState.Error(ErrorReason.NotFound, canRetry = false) }
                return@launch
            }

            runCatching { cardRepository.listByDeck(deckId).orderedBy(deck) }
                .onSuccess { cards ->
                    val dueCount = runCatching { srsRepository.dueForDeck(deckId).size }
                        .getOrDefault(0)
                    val mastered = masteredPercent(cards)
                    _state.update { deck.toContent(cards, myPubky, dueCount, mastered) }
                    Log.d(TAG, "load: cards=${cards.size} due=$dueCount mastered=$mastered")
                    loadCoverBlob(deck.coverImageRef)
                    loadAuthorAvatar(deck.authorPubky)
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateBack) }
    }

    fun onShareClick() {
        viewModelScope.launch {
            val deck = deckRepository.getLocal(deckId) ?: return@launch
            _effects.emit(DeckDetailEffect.Share(title = deck.title, uri = deck.pubkyUri.value))
        }
    }

    fun onStudyClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateStudy) }
    }

    fun onEditClick() {
        viewModelScope.launch { _effects.emit(DeckDetailEffect.NavigateEditDeck(deckId)) }
    }

    fun onDeleteDeck() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = true) }
    }

    fun onDismissDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false) }
    }

    fun onConfirmDelete() {
        val current = _state.value as? DeckDetailUiState.Content ?: return
        _state.update { current.copy(showDeleteConfirm = false, isDeleting = true) }
        viewModelScope.launch {
            Log.d(TAG, "onConfirmDelete: deckId=$deckId")
            deckRepository.delete(deckId)
                .onSuccess { _effects.emit(DeckDetailEffect.Deleted) }
                .onFailure { err ->
                    Log.e(TAG, "onConfirmDelete: FAILED — ${err::class.simpleName}: ${err.message}", err)
                    _state.update { DeckDetailUiState.Error(err.toErrorReason()) }
                }
        }
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

    /**
     * Fetches a homeserver blob cover and folds its Base64 bytes into the current [Content] so the
     * UI can render the real image. Remote (URL) covers need no fetch — they are already carried by
     * [DeckDetailUiState.Content.coverImageUrl]. No-ops on null/remote refs or while not in Content.
     */
    private suspend fun loadCoverBlob(ref: MediaRef.Image?) {
        if (ref == null || ref.isRemote) return
        val bytes = mediaRepository.get(deckId, ref)
            .onFailure { Log.e(TAG, "loadCoverBlob: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        val encoded = Base64.encode(bytes)
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.copy(coverImageBase64 = encoded) ?: current
        }
    }

    /**
     * Fetches the author's pubky.app profile and folds its avatar URL into the current [Content] so
     * the author row can show a real picture (falling back to the initial when absent or unset).
     */
    private suspend fun loadAuthorAvatar(authorPubky: String) {
        val avatar = identityRepository.fetchProfile(authorPubky).getOrNull()?.avatarUrl
        if (avatar.isNullOrBlank()) return
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.copy(authorAvatarUrl = avatar) ?: current
        }
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
            coverImageUrl = coverImageRef?.url,
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
        val coverImageUrl: String? = null,
        val coverImageBase64: String? = null,
        val authorName: String?,
        val authorAvatarUrl: String? = null,
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
    data class Error(
        val reason: ErrorReason,
        /** False when retrying cannot possibly succeed (e.g. the deck no longer exists). */
        val canRetry: Boolean = true,
    ) : DeckDetailUiState
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
    data class Share(val title: String, val uri: String) : DeckDetailEffect
    data object Deleted : DeckDetailEffect
}
