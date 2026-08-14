package com.github.jvsena42.echo.presentation.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.repository.CardRepository
import com.github.jvsena42.echo.data.repository.DeckRepository
import com.github.jvsena42.echo.data.repository.IdentityRepository
import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.domain.model.CardSide
import com.github.jvsena42.echo.domain.model.Deck
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.domain.model.orderedBy
import com.github.jvsena42.echo.util.Log
import com.github.jvsena42.echo.util.epochMillis
import kotlinx.coroutines.Job
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
) : ViewModel() {
    private val _state = MutableStateFlow(DeckEditorUiState())
    val state: StateFlow<DeckEditorUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DeckEditorEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<DeckEditorEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null

    /**
     * The deck as loaded from the repository. Saving republishes the whole manifest, so the
     * fields the editor does not expose (cover image, Listen/Speak) must be carried forward
     * from here or they are destroyed on the homeserver.
     */
    private var loadedDeck: Deck? = null

    init {
        if (deckId != null) loadExisting()
    }

    private fun loadExisting() {
        loadJob = viewModelScope.launch {
            Log.d(TAG, "loadExisting: deckId=$deckId")
            val deck = deckRepository.getLocal(deckId!!) ?: return@launch
            loadedDeck = deck
            val cards = runCatching { cardRepository.listByDeck(deckId).orderedBy(deck) }
                .getOrElse { emptyList() }
            _state.update { DeckEditorUiState(
                isNew = false,
                coverEmoji = deck.coverEmoji ?: deck.title.firstOrNull()?.toString() ?: "",
                title = deck.title,
                description = deck.description ?: "",
                tags = deck.tags.map { it.value },
                cards = cards.map { it.toEditable() },
            ) }
        }
    }

    fun onTitleChanged(text: String) {
        _state.update { it.copy(title = text, titleError = titleErrorFor(text)) }
    }

    fun onDescriptionChanged(text: String) {
        _state.update { it.copy(description = text, descriptionError = descriptionErrorFor(text)) }
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

    /**
     * Move a card one position. The editor's drag handle was decorative — dragging it changed
     * nothing — and reorder could not have persisted anyway until reads started honouring the
     * manifest's `cardIndex` order. `onSaveClick` writes `cardIndex` in list order, so moving
     * here is all that's needed now.
     */
    fun onMoveCard(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.cards.indices || to !in s.cards.indices || from == to) return@update s
            val reordered = s.cards.toMutableList()
            reordered.add(to, reordered.removeAt(from))
            s.copy(cards = reordered)
        }
    }

    fun onCardClick(cardId: String) {
        val currentDeckId = deckId ?: return
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateEditCard(currentDeckId, cardId)) }
    }

    fun onCloseClick() {
        viewModelScope.launch { _effects.emit(DeckEditorEffect.NavigateBack) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        val s = _state.value
        if (s.title.isBlank()) {
            _state.update { it.copy(error = "Title is required.") }
            return
        }
        val titleError = titleErrorFor(s.title)
        val descriptionError = descriptionErrorFor(s.description)
        if (titleError != null || descriptionError != null) {
            _state.update { it.copy(titleError = titleError, descriptionError = descriptionError) }
            return
        }
        saveJob = viewModelScope.launch {
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
            val existing = loadedDeck ?: deckId?.let { deckRepository.getLocal(it) }
            val cards = buildCards(s.cards, actualDeckId, now)
            val deck = buildDeck(s, authorPubky, actualDeckId, existing, cards, now)

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

    private fun titleErrorFor(text: String): String? =
        if (text.length > TITLE_MAX_LENGTH) "Title must be $TITLE_MAX_LENGTH characters or fewer." else null

    private fun descriptionErrorFor(text: String): String? =
        if (text.length > DESCRIPTION_MAX_LENGTH) "Description must be $DESCRIPTION_MAX_LENGTH characters or fewer." else null

    private fun Card.toEditable(): EditableCardModel = EditableCardModel(
        id = id,
        frontText = front.text ?: "",
        backText = back.text ?: "",
        hasImage = front.imageRef != null || back.imageRef != null,
        hasAudio = front.audioRef != null || back.audioRef != null,
        original = this,
    )

    companion object {
        private const val TAG = "Echo/DeckEditorVM"
        private const val TITLE_MAX_LENGTH = 120
        private const val DESCRIPTION_MAX_LENGTH = 500

        private fun generateId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { chars.random() }.joinToString("")
        }
    }
}

/**
 * Saving republishes every card record, so each side is rebuilt *from* the loaded card:
 * the editor only edits text, and dropping the rest would wipe the card's image and audio
 * off the homeserver.
 */
private fun buildCards(
    editables: List<EditableCardModel>,
    deckId: String,
    now: Long,
): List<Card> = editables.map { editable ->
    val original = editable.original
    val front = (original?.front ?: CardSide()).copy(text = editable.frontText.ifBlank { null })
    val back = (original?.back ?: CardSide()).copy(text = editable.backText.ifBlank { null })
    val unchanged = original != null && original.front == front && original.back == back
    Card(
        id = editable.id,
        deckId = deckId,
        // Only bump the timestamp the sync reads when the card actually changed.
        updatedAt = if (unchanged) original.updatedAt else now,
        front = front,
        back = back,
    )
}

/** [existing] supplies the fields this editor does not expose, so a save cannot destroy them. */
@Suppress("LongParameterList")
private fun buildDeck(
    s: DeckEditorUiState,
    authorPubky: String,
    deckId: String,
    existing: Deck?,
    cards: List<Card>,
    now: Long,
): Deck = Deck(
    id = deckId,
    authorPubky = authorPubky,
    title = s.title,
    description = s.description.ifBlank { null },
    coverEmoji = s.coverEmoji.ifBlank { null },
    coverImageRef = existing?.coverImageRef,
    tags = s.tags.map { Tag(it) },
    createdAt = if (s.isNew) now else existing?.createdAt ?: now,
    updatedAt = now,
    cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
    listenEnabled = existing?.listenEnabled ?: true,
    speakEnabled = existing?.speakEnabled ?: true,
)

data class DeckEditorUiState(
    val isNew: Boolean = true,
    val coverEmoji: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val cards: List<EditableCardModel> = emptyList(),
    val isSaving: Boolean = false,
    val titleError: String? = null,
    val descriptionError: String? = null,
    val error: String? = null,
)

data class EditableCardModel(
    val id: String,
    val frontText: String,
    val backText: String,
    val hasImage: Boolean,
    val hasAudio: Boolean,
    /**
     * The card this model was loaded from, kept so a save round-trip preserves the image and
     * audio refs the editor cannot edit. `null` for cards added in this session.
     */
    val original: Card? = null,
)

sealed interface DeckEditorEffect {
    data object NavigateBack : DeckEditorEffect
    data class NavigateEditCard(val deckId: String, val cardId: String) : DeckEditorEffect
    data class SaveSuccess(val deckId: String) : DeckEditorEffect
}
