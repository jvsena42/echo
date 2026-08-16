package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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
        // Studying runs on its own full-screen destination while this screen stays composed
        // behind it, so without this the due count keeps the value it had before the session.
        viewModelScope.launch {
            srsRepository.changes
                .filter { it == deckId }
                .collect { load(silent = true) }
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps existing content on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a review that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId (silent=$silent)")
            if (!silent) _state.update { DeckDetailUiState.Loading }

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()

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

            // Must be a fetch, not a cache read: nothing has loaded this deck's cards yet on a
            // cold launch, and for a deck you don't own nothing ever will.
            runCatching { cardRepository.fetchByDeck(deck).getOrThrow().inStudyOrder() }
                .onSuccess { cards ->
                    val dueCount = runCatching { srsRepository.dueForDeck(deckId).size }
                        .getOrDefault(0)
                    val mastered = masteredPercent(cards)
                    _state.update { deck.toContent(cards, session?.identity, dueCount, mastered) }
                    Log.d(TAG, "load: cards=${cards.size} due=$dueCount mastered=$mastered")
                    loadCoverBlob(deck.coverImageRef, deck.authorPubky)
                    loadAuthorProfile(deck.authorPubky)
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
    private suspend fun loadCoverBlob(ref: MediaRef.Image?, authorPubky: String) {
        if (ref == null || ref.isRemote) return
        val bytes = mediaRepository.get(authorPubky, deckId, ref)
            .onFailure { Log.e(TAG, "loadCoverBlob: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        val encoded = Base64.encode(bytes)
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.copy(coverImageBase64 = encoded) ?: current
        }
    }

    /**
     * Fetches the author's pubky.app profile and folds it into the current [Content], so the author
     * row shows the same name and picture the author's own profile screen does. Keeps whatever the
     * session already gave us when the author has published no profile.
     */
    private suspend fun loadAuthorProfile(authorPubky: String) {
        val profile = identityRepository.fetchProfile(authorPubky).getOrNull() ?: return
        _state.update { current ->
            (current as? DeckDetailUiState.Content)?.let {
                it.copy(
                    author = it.author.copy(
                        displayName = profile.displayName ?: it.author.displayName,
                        avatarUrl = profile.avatarUrl ?: it.author.avatarUrl,
                    ),
                )
            } ?: current
        }
    }

    private fun Deck.toContent(
        cards: List<Card>,
        myIdentity: PubkyIdentity?,
        dueCount: Int,
        mastered: String,
    ): DeckDetailUiState.Content {
        val isOwned = authorPubky == myIdentity?.pubky
        return DeckDetailUiState.Content(
            deckId = id,
            title = title,
            description = description,
            coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
            coverImageUrl = coverImageRef?.url,
            // Your own decks can name you straight away from the session; for anyone else the
            // pubky stands in until loadAuthorProfile lands.
            author = myIdentity?.takeIf { isOwned }
                ?: PubkyIdentity(authorPubky, displayName = null, avatarUrl = null, bio = null),
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
        private const val TAG = "Loopky/DeckDetailVM"

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
        val author: PubkyIdentity,
        /** Ownership is a separate concern from identity — the author row shows both. */
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
