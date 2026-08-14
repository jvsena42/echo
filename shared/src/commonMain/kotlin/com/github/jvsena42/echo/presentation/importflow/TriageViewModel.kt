package com.github.jvsena42.echo.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.echo.data.repository.ImportRepository
import com.github.jvsena42.echo.domain.model.TriageDecision
import com.github.jvsena42.echo.domain.model.frontBackOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Review-cards / triage step (design node U92Nh, spec §5.5). Walks the parsed draft one card
 * at a time letting the user keep, discard, or edit each. "Approve all" keeps everything and
 * proceeds straight to publish.
 */
class TriageViewModel(
    private val importRepository: ImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TriageEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<TriageEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    /** Rebuilds card text from the draft (e.g. after returning from the edit screen). */
    fun refresh() {
        val draft = importRepository.currentDraft()
        if (draft == null) {
            _state.update { TriageUiState() }
            return
        }
        val decisions = importRepository.decisions()
        val cards = draft.rows.map { row ->
            val (front, back) = draft.frontBackOf(row)
            TriageCard(rowIndex = row.index, front = front, back = back)
        }
        _state.update { current ->
            current.copy(
                cards = cards,
                total = cards.size,
                currentIndex = current.currentIndex.coerceIn(0, maxOf(0, cards.size - 1)),
                keptCount = decisions.count { it.value == TriageDecision.Keep },
                discardedCount = decisions.count { it.value == TriageDecision.Discard },
            )
        }
    }

    fun onKeep() = decide(TriageDecision.Keep)

    fun onDiscard() = decide(TriageDecision.Discard)

    private fun decide(decision: TriageDecision) {
        val s = _state.value
        val card = s.cards.getOrNull(s.currentIndex) ?: return
        importRepository.setDecision(card.rowIndex, decision)
        val decisions = importRepository.decisions()
        val kept = decisions.count { it.value == TriageDecision.Keep }
        val discarded = decisions.count { it.value == TriageDecision.Discard }
        val isLast = s.currentIndex >= s.total - 1
        if (isLast) {
            _state.update { it.copy(keptCount = kept, discardedCount = discarded) }
            proceed()
        } else {
            _state.update {
                it.copy(currentIndex = it.currentIndex + 1, keptCount = kept, discardedCount = discarded)
            }
        }
    }

    /**
     * Step back to the previous card and reset it to Keep.
     *
     * Discarding was irreversible: a card discarded by mistake — including any image just
     * attached to it in the triage editor — was gone with no undo and no way back.
     */
    fun onUndo() {
        val s = _state.value
        if (s.currentIndex <= 0) return
        val previous = s.cards.getOrNull(s.currentIndex - 1) ?: return
        importRepository.setDecision(previous.rowIndex, TriageDecision.Keep)
        val decisions = importRepository.decisions()
        _state.update {
            it.copy(
                currentIndex = it.currentIndex - 1,
                keptCount = decisions.count { d -> d.value == TriageDecision.Keep },
                discardedCount = decisions.count { d -> d.value == TriageDecision.Discard },
            )
        }
    }

    fun onEditClick() {
        val card = _state.value.cards.getOrNull(_state.value.currentIndex) ?: return
        viewModelScope.launch { _effects.emit(TriageEffect.NavigateEditCard(card.rowIndex)) }
    }

    /** Keep every card not yet discarded and go to publish. */
    fun onApproveAll() {
        _state.value.cards.forEach { card ->
            if (importRepository.decisions()[card.rowIndex] != TriageDecision.Discard) {
                importRepository.setDecision(card.rowIndex, TriageDecision.Keep)
            }
        }
        proceed()
    }

    fun onBackClick() {
        viewModelScope.launch { _effects.emit(TriageEffect.NavigateBack) }
    }

    private fun proceed() {
        if (importRepository.keptRows().isEmpty()) {
            _state.update { it.copy(error = "Keep at least one card to continue.") }
            return
        }
        viewModelScope.launch { _effects.emit(TriageEffect.NavigatePublish) }
    }
}

data class TriageUiState(
    val cards: List<TriageCard> = emptyList(),
    val currentIndex: Int = 0,
    val total: Int = 0,
    val keptCount: Int = 0,
    val discardedCount: Int = 0,
    val error: String? = null,
) {
    val currentCard: TriageCard? get() = cards.getOrNull(currentIndex)

    /** There is a previous card to step back to. */
    val canUndo: Boolean get() = currentIndex > 0
}

data class TriageCard(
    val rowIndex: Int,
    val front: String,
    val back: String,
)

sealed interface TriageEffect {
    data object NavigateBack : TriageEffect
    data class NavigateEditCard(val rowIndex: Int) : TriageEffect
    data object NavigatePublish : TriageEffect
}
