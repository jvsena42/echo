package com.github.jvsena42.eco.presentation.import_flow

import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.data.repository.ImportRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.CardSide
import com.github.jvsena42.eco.domain.model.ColumnRole
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

class PublishDeckViewModel(
    private val importRepository: ImportRepository,
    private val deckRepository: DeckRepository,
    private val identityRepository: IdentityRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PublishDeckUiState())
    val state: StateFlow<PublishDeckUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PublishDeckEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PublishDeckEffect> = _effects.asSharedFlow()

    private var publishJob: Job? = null

    init {
        val draft = importRepository.currentDraft()
        if (draft != null) {
            _state.update { it.copy(cardCount = draft.rows.size) }
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

    fun onBackClick() {
        scope.launch { _effects.emit(PublishDeckEffect.NavigateBack) }
    }

    fun onPublishClick() {
        if (publishJob?.isActive == true) return
        val s = _state.value
        if (s.title.isBlank()) {
            _state.update { it.copy(error = "Title is required.") }
            return
        }

        val draft = importRepository.currentDraft()
        if (draft == null) {
            _state.update { it.copy(error = "No import data. Please go back and paste again.") }
            return
        }

        publishJob = scope.launch {
            _state.update { it.copy(isPublishing = true, error = null) }
            Log.d(TAG, "publish: title=${s.title}, cards=${draft.rows.size}")

            val session = runCatching { identityRepository.currentSession() }.getOrNull()
                ?: runCatching { identityRepository.loadPersistedSession() }.getOrNull()
            val authorPubky = session?.identity?.pubky ?: run {
                _state.update { it.copy(isPublishing = false, error = "Not signed in.") }
                return@launch
            }

            val now = epochMillis()
            val deckId = generateId()
            val mapping = draft.columnMapping.assignments

            val cards = draft.rows.mapIndexed { idx, row ->
                val frontIdx = mapping.indexOfFirst { it == ColumnRole.Front }.takeIf { it >= 0 } ?: 0
                val backIdx = mapping.indexOfFirst { it == ColumnRole.Back }.takeIf { it >= 0 } ?: 1
                Card(
                    id = generateId(),
                    deckId = deckId,
                    updatedAt = now,
                    front = CardSide(text = row.fields.getOrElse(frontIdx) { "" }.takeIf { it.isNotBlank() }),
                    back = CardSide(text = row.fields.getOrElse(backIdx) { "" }.takeIf { it.isNotBlank() }),
                )
            }

            val deck = Deck(
                id = deckId,
                authorPubky = authorPubky,
                title = s.title,
                description = s.description.ifBlank { null },
                coverEmoji = s.coverEmoji.ifBlank { null },
                coverImageRef = null,
                tags = s.tags.map { Tag(it) },
                createdAt = now,
                updatedAt = now,
                cardIndex = cards.map { CardIndexEntry(it.id, it.updatedAt) },
            )

            deckRepository.publish(deck, cards)
                .onSuccess {
                    Log.d(TAG, "publish: SUCCESS deckId=$deckId")
                    importRepository.clear()
                    _state.update { it.copy(isPublishing = false) }
                    _effects.emit(PublishDeckEffect.Published(deckId))
                }
                .onFailure { err ->
                    Log.e(TAG, "publish: FAILED — ${err.message}", err)
                    _state.update { it.copy(isPublishing = false, error = err.message ?: "Publish failed.") }
                }
        }
    }

    fun onDispose() {
        publishJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Echo/PublishVM"

        private fun generateId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..12).map { chars.random() }.joinToString("")
        }
    }
}

data class PublishDeckUiState(
    val title: String = "",
    val description: String = "",
    val coverEmoji: String = "",
    val tags: List<String> = emptyList(),
    val cardCount: Int = 0,
    val isPublishing: Boolean = false,
    val error: String? = null,
)

sealed interface PublishDeckEffect {
    data object NavigateBack : PublishDeckEffect
    data class Published(val deckId: String) : PublishDeckEffect
}
