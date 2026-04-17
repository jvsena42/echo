package com.github.jvsena42.eco.presentation.decks

import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.CardSide
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.Tag
import com.github.jvsena42.eco.util.Log
import com.github.jvsena42.eco.util.epochMillis
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeckEditorViewModel(
    private val deckId: String?,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val identityRepository: IdentityRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(DeckEditorUiState())
    val state: StateFlow<DeckEditorUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckEditorEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckEditorEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        if (deckId != null) loadExisting()
    }

    private fun loadExisting() {
        loadJob = scope.launch {
            Log.d(TAG, "loadExisting: deckId=$deckId")
            val deck = deckRepository.getLocal(deckId!!) ?: return@launch
            val cards = runCatching { cardRepository.listByDeck(deckId) }.getOrElse { emptyList() }
            _state.value = DeckEditorUiState(
                isNew = false,
                coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.toString() ?: "",
                title = deck.title,
                description = deck.description ?: "",
                tags = deck.tags.map { it.value },
                cards = cards.map { it.toEditable() },
            )
        }
    }

    fun onTitleChanged(text: String) {
        _state.update { it.copy(title = text) }
    }

    fun onDescriptionChanged(text: String) {
        _state.update { it.copy(description = text) }
    }

    fun onCoverEmojiChanged(emoji: String) {
        _state.update { it.copy(coverEmoji = emoji) }
    }

    fun onAddTag(tag: String) {
        val trimmed = tag.trim().lowercase()
        if (trimmed.isBlank()) return
        _state.update { s -> s.copy(tags = s.tags + trimmed) }
    }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    fun onAddCard() {
        val newCard = EditableCardModel(
            id = generateId(),
            frontText = "",
            backText = "",
            hasImage = false,
            hasAudio = false,
        )
        _state.update { s -> s.copy(cards = s.cards + newCard) }
    }

    fun onCardClick(cardId: String) {
        val currentDeckId = deckId ?: return
        scope.launch { _effects.emit(DeckEditorEffect.NavigateEditCard(currentDeckId, cardId)) }
    }

    fun onCloseClick() {
        scope.launch { _effects.emit(DeckEditorEffect.NavigateBack) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        if (s.title.isBlank()) {
            _state.update { it.copy(error = "Title is required.") }
            return
        }
        saveJob = scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: title=${s.title}, cards=${s.cards.size}")

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update { it.copy(isSaving = false, error = "Not signed in.") }
                return@launch
            }

            val now = epochMillis()
            val actualDeckId = deckId ?: generateId()
            val cards = s.cards.map { editableCard ->
                Card(
                    id = editableCard.id,
                    deckId = actualDeckId,
                    updatedAt = now,
                    front = CardSide(text = editableCard.frontText),
                    back = CardSide(text = editableCard.backText),
                )
            }

            val deck = Deck(
                id = actualDeckId,
                authorPubky = authorPubky,
                title = s.title,
                description = s.description.ifBlank { null },
                coverEmoji = s.coverEmoji.ifBlank { null },
                coverImageRef = null,
                tags = s.tags.map { Tag(it) },
                createdAt = if (s.isNew) now else deckRepository.getLocal(actualDeckId)?.createdAt ?: now,
                updatedAt = now,
                cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
            )

            deckRepository.publish(deck, cards)
                .onSuccess {
                    Log.d(TAG, "save: SUCCESS deckId=$actualDeckId")
                    _state.update { it.copy(isSaving = false) }
                    _effects.emit(DeckEditorEffect.SaveSuccess(actualDeckId))
                }
                .onFailure { err ->
                    Log.e(TAG, "save: FAILED — ${err.message}", err)
                    _state.update { it.copy(isSaving = false, error = err.message ?: "Save failed.") }
                }
        }
    }

    fun onDispose() {
        loadJob?.cancel()
        saveJob?.cancel()
        scope.cancel()
    }

    private fun Card.toEditable(): EditableCardModel = EditableCardModel(
        id = id,
        frontText = front.text ?: "",
        backText = back.text ?: "",
        hasImage = front.imageRef != null || back.imageRef != null,
        hasAudio = front.audioRef != null || back.audioRef != null,
    )

    companion object {
        private const val TAG = "Echo/DeckEditorVM"

        private fun generateId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { chars.random() }.joinToString("")
        }

    }
}

data class DeckEditorUiState(
    val isNew: Boolean = true,
    val coverEmoji: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val cards: List<EditableCardModel> = emptyList(),
    val isSaving: Boolean = false,
    val error: String? = null,
)

data class EditableCardModel(
    val id: String,
    val frontText: String,
    val backText: String,
    val hasImage: Boolean,
    val hasAudio: Boolean,
)

sealed interface DeckEditorEffect {
    data object NavigateBack : DeckEditorEffect
    data class NavigateEditCard(val deckId: String, val cardId: String) : DeckEditorEffect
    data class SaveSuccess(val deckId: String) : DeckEditorEffect
}
