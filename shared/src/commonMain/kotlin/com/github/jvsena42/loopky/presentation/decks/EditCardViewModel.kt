package com.github.jvsena42.loopky.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class EditCardViewModel(
    private val deckId: String,
    private val cardId: String,
    private val cardRepository: CardRepository,
    private val deckRepository: DeckRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
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
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: deckId=$deckId cardId=$cardId")
            val deck = deckRepository.getLocal(deckId)
            val card = cardRepository.get(deckId, cardId)
            if (card == null) {
                _state.update { it.copy(error = "Card not found.") }
                return@launch
            }
            val cardIndex = deck?.cardIndex?.indexOfFirst { it.id == cardId }?.plus(1) ?: 0
            val totalCards = deck?.cardCount ?: 0
            _state.update { EditCardUiState(
                deckTitle = deck?.title ?: "",
                cardIndex = cardIndex,
                totalCards = totalCards,
                frontText = card.front.text ?: "",
                backText = card.back.text ?: "",
                frontImageRef = card.front.imageRef,
                backImageRef = card.back.imageRef,
                hasImage = card.front.imageRef != null || card.back.imageRef != null,
                hasAudio = card.front.audioRef != null || card.back.audioRef != null,
            ) }
        }
    }

    /** A web (Unsplash) image was chosen for the card front — saved by URL. */
    fun onFrontImageWebSelected(url: String) {
        val ref = MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = url)
        _state.update { it.copy(frontImageRef = ref, frontPendingBytes = null, frontPendingMime = null, hasImage = true) }
    }

    /** A gallery image was chosen for the card front — already compressed; uploaded on save. */
    fun onFrontImageGallerySelected(bytes: ByteArray, mime: String) {
        _state.update {
            it.copy(frontImageRef = null, frontPendingBytes = bytes, frontPendingMime = mime, hasImage = true)
        }
    }

    fun onRemoveFrontImage() {
        _state.update { it.copy(frontImageRef = null, frontPendingBytes = null, frontPendingMime = null) }
    }

    /** A web (Unsplash) image was chosen for the card back — saved by URL. */
    fun onBackImageWebSelected(url: String) {
        val ref = MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = url)
        _state.update { it.copy(backImageRef = ref, backPendingBytes = null, backPendingMime = null) }
    }

    /** A gallery image was chosen for the card back — already compressed; uploaded on save. */
    fun onBackImageGallerySelected(bytes: ByteArray, mime: String) {
        _state.update { it.copy(backImageRef = null, backPendingBytes = bytes, backPendingMime = mime) }
    }

    fun onRemoveBackImage() {
        _state.update { it.copy(backImageRef = null, backPendingBytes = null, backPendingMime = null) }
    }

    fun onFrontTextChanged(text: String) {
        _state.update { it.copy(frontText = text, frontError = cardTextErrorFor(text)) }
    }

    fun onBackTextChanged(text: String) {
        _state.update { it.copy(backText = text, backError = cardTextErrorFor(text)) }
    }

    fun onSpeakFront() {
        val text = _state.value.frontText
        if (text.isNotBlank()) {
            viewModelScope.launch { _effects.emit(EditCardEffect.Speak(text)) }
        }
    }

    fun onSpeakBack() {
        val text = _state.value.backText
        if (text.isNotBlank()) {
            viewModelScope.launch { _effects.emit(EditCardEffect.Speak(text)) }
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
        val frontError = cardTextErrorFor(s.frontText)
        val backError = cardTextErrorFor(s.backText)
        if (frontError != null || backError != null) {
            _state.update { it.copy(frontError = frontError, backError = backError) }
            return
        }
        saveJob = viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            Log.d(TAG, "save: cardId=$cardId")

            val existingCard = cardRepository.get(deckId, cardId)
            val now = epochMillis()
            val frontImage = resolveFrontImage(s)
            val backImage = resolveBackImage(s)
            val card = Card(
                id = cardId,
                deckId = deckId,
                updatedAt = now,
                front = CardSide(
                    text = s.frontText.ifBlank { null },
                    imageRef = frontImage,
                    audioRef = existingCard?.front?.audioRef,
                ),
                back = CardSide(
                    text = s.backText.ifBlank { null },
                    imageRef = backImage,
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
        viewModelScope.launch {
            Log.d(TAG, "delete: cardId=$cardId")
            cardRepository.delete(deckId, cardId)
                .onSuccess { _effects.emit(EditCardEffect.Deleted) }
                .onFailure { err ->
                    _state.update { it.copy(error = err.message ?: "Delete failed.") }
                }
        }
    }

    fun onCancelClick() {
        viewModelScope.launch { _effects.emit(EditCardEffect.NavigateBack) }
    }

    /** Upload a pending gallery image, or keep the chosen web/existing ref. */
    private suspend fun resolveFrontImage(s: EditCardUiState): MediaRef.Image? = when {
        s.frontPendingBytes != null ->
            mediaRepository.putImage(deckId, s.frontPendingBytes, s.frontPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "front image upload failed — ${it.message}", it) }
                .getOrNull()

        else -> s.frontImageRef
    }

    /** Upload a pending gallery image, or keep the chosen web/existing ref (card back). */
    private suspend fun resolveBackImage(s: EditCardUiState): MediaRef.Image? = when {
        s.backPendingBytes != null ->
            mediaRepository.putImage(deckId, s.backPendingBytes, s.backPendingMime ?: "image/jpeg")
                .onFailure { Log.e(TAG, "back image upload failed — ${it.message}", it) }
                .getOrNull()

        else -> s.backImageRef
    }

    private fun cardTextErrorFor(text: String): String? =
        if (text.length > CARD_TEXT_MAX_LENGTH) "Card text must be $CARD_TEXT_MAX_LENGTH characters or fewer." else null

    companion object {
        private const val TAG = "Loopky/EditCardVM"
        private const val CARD_TEXT_MAX_LENGTH = 2000
    }
}

data class EditCardUiState(
    val deckTitle: String = "",
    val cardIndex: Int = 0,
    val totalCards: Int = 0,
    val frontText: String = "",
    val backText: String = "",
    val tags: List<String> = emptyList(),
    val frontImageRef: MediaRef.Image? = null,
    val frontPendingBytes: ByteArray? = null,
    val frontPendingMime: String? = null,
    val backImageRef: MediaRef.Image? = null,
    val backPendingBytes: ByteArray? = null,
    val backPendingMime: String? = null,
    val hasImage: Boolean = false,
    val hasAudio: Boolean = false,
    val isSaving: Boolean = false,
    val frontError: String? = null,
    val backError: String? = null,
    val error: String? = null,
)

sealed interface EditCardEffect {
    data object NavigateBack : EditCardEffect
    data object SaveSuccess : EditCardEffect
    data object Deleted : EditCardEffect
    data class Speak(val text: String) : EditCardEffect
}
