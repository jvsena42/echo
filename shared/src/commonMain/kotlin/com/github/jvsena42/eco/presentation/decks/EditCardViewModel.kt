package com.github.jvsena42.eco.presentation.decks

import com.github.jvsena42.eco.data.repository.CardRepository
import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.MediaRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardSide
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

class EditCardViewModel(
    private val deckId: String,
    private val cardId: String,
    private val cardRepository: CardRepository,
    private val deckRepository: DeckRepository,
    private val mediaRepository: MediaRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(EditCardUiState())
    val state: StateFlow<EditCardUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EditCardEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<EditCardEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        load()
    }

    private fun load() {
        loadJob = scope.launch {
            Log.d(TAG, "load: deckId=$deckId cardId=$cardId")
            val deck = deckRepository.getLocal(deckId)
            val card = cardRepository.get(deckId, cardId)
            if (card == null) {
                _state.update { it.copy(error = "Card not found.") }
                return@launch
            }
            val cardIndex = deck?.cardIndex?.indexOfFirst { it.id == cardId }?.plus(1) ?: 0
            val totalCards = deck?.cardCount ?: 0
            _state.value = EditCardUiState(
                deckTitle = deck?.title ?: "",
                cardIndex = cardIndex,
                totalCards = totalCards,
                frontText = card.front.text ?: "",
                backText = card.back.text ?: "",
                hasImage = card.front.imageRef != null || card.back.imageRef != null,
                hasAudio = card.front.audioRef != null || card.back.audioRef != null,
            )
        }
    }

    fun onFrontTextChanged(text: String) {
        _state.update { it.copy(frontText = text) }
    }

    fun onBackTextChanged(text: String) {
        _state.update { it.copy(backText = text) }
    }

    fun onSpeakFront() {
        val text = _state.value.frontText
        if (text.isNotBlank()) {
            scope.launch { _effects.emit(EditCardEffect.Speak(text)) }
        }
    }

    fun onSpeakBack() {
        val text = _state.value.backText
        if (text.isNotBlank()) {
            scope.launch { _effects.emit(EditCardEffect.Speak(text)) }
        }
    }

    fun onAddTag(tag: String) {
        val trimmed = tag.trim().lowercase()
        if (trimmed.isBlank()) return
        _state.update { s -> s.copy(tags = s.tags + trimmed) }
    }

    fun onRemoveTag(tag: String) {
        _state.update { s -> s.copy(tags = s.tags - tag) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        if (s.frontText.isBlank() && s.backText.isBlank()) {
            _state.update { it.copy(error = "Card must have content.") }
            return
        }
        saveJob = scope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: cardId=$cardId")

            val existingCard = cardRepository.get(deckId, cardId)
            val now = epochMillis()
            val card = Card(
                id = cardId,
                deckId = deckId,
                updatedAt = now,
                front = CardSide(
                    text = s.frontText.ifBlank { null },
                    imageRef = existingCard?.front?.imageRef,
                    audioRef = existingCard?.front?.audioRef,
                ),
                back = CardSide(
                    text = s.backText.ifBlank { null },
                    imageRef = existingCard?.back?.imageRef,
                    audioRef = existingCard?.back?.audioRef,
                ),
            )

            cardRepository.upsert(card)
                .onSuccess {
                    Log.d(TAG, "save: SUCCESS")
                    _state.update { it.copy(isSaving = false) }
                    _effects.emit(EditCardEffect.SaveSuccess)
                }
                .onFailure { err ->
                    Log.e(TAG, "save: FAILED — ${err.message}", err)
                    _state.update { it.copy(isSaving = false, error = err.message ?: "Save failed.") }
                }
        }
    }

    fun onDeleteCard() {
        scope.launch {
            Log.d(TAG, "delete: cardId=$cardId")
            cardRepository.delete(deckId, cardId)
                .onSuccess { _effects.emit(EditCardEffect.Deleted) }
                .onFailure { err ->
                    _state.update { it.copy(error = err.message ?: "Delete failed.") }
                }
        }
    }

    fun onCancelClick() {
        scope.launch { _effects.emit(EditCardEffect.NavigateBack) }
    }

    fun onDispose() {
        loadJob?.cancel()
        saveJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Echo/EditCardVM"
    }
}

data class EditCardUiState(
    val deckTitle: String = "",
    val cardIndex: Int = 0,
    val totalCards: Int = 0,
    val frontText: String = "",
    val backText: String = "",
    val tags: List<String> = emptyList(),
    val hasImage: Boolean = false,
    val hasAudio: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

sealed interface EditCardEffect {
    data object NavigateBack : EditCardEffect
    data object SaveSuccess : EditCardEffect
    data object Deleted : EditCardEffect
    data class Speak(val text: String) : EditCardEffect
}
