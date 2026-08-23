package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.TriageDecision
import com.github.jvsena42.loopky.domain.model.frontBackOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Review-cards / triage step (spec §5.4). Walks the parsed draft one card
 * at a time letting the user keep, discard, or edit each. "Approve all" keeps everything and
 * proceeds straight to publish.
 */
class TriageViewModel(
    private val importRepository: ImportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    /** Index of the card whose decision `onUndo` reverts; null once undone or before any decision. */
    private var lastDecidedIndex: Int? = null

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
        lastDecidedIndex = s.currentIndex
        val decisions = importRepository.decisions()
        val kept = decisions.count { it.value == TriageDecision.Keep }
        val discarded = decisions.count { it.value == TriageDecision.Discard }
        val isLast = s.currentIndex >= s.total - 1
        if (isLast) {
            _state.update { it.copy(keptCount = kept, discardedCount = discarded, canUndo = true) }
            proceed()
        } else {
            _state.update {
                it.copy(
                    currentIndex = it.currentIndex + 1,
                    keptCount = kept,
                    discardedCount = discarded,
                    canUndo = true,
                )
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
        // Revert the last card *decided*, not simply the one before the cursor: discarding the
        // final card does not advance the index, so a step-back-only undo could never recover
        // it — exactly the card most worth recovering.
        val target = lastDecidedIndex ?: return
        val s = _state.value
        val card = s.cards.getOrNull(target) ?: return
        importRepository.setDecision(card.rowIndex, TriageDecision.Keep)
        lastDecidedIndex = null
        val decisions = importRepository.decisions()
        _state.update {
            it.copy(
                currentIndex = target,
                keptCount = decisions.count { d -> d.value == TriageDecision.Keep },
                discardedCount = decisions.count { d -> d.value == TriageDecision.Discard },
                canUndo = false,
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
    /** A decision has been made that can still be reverted. */
    val canUndo: Boolean = false,
) {
    val currentCard: TriageCard? get() = cards.getOrNull(currentIndex)
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
