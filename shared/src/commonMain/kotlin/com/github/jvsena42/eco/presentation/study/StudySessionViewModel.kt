package com.github.jvsena42.eco.presentation.study

import com.github.jvsena42.eco.data.repository.DeckRepository
import com.github.jvsena42.eco.data.repository.SrsRepository
import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.SrsGrade
import com.github.jvsena42.eco.domain.model.previewIntervals
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
import kotlinx.coroutines.launch

/**
 * Drives the spaced-repetition study loop (`design/main/phone-echo.pen` → "03 Study Session").
 *
 * [deckId] `null` studies every due card across owned decks (Home "Start studying"); a non-null
 * value studies one deck (DeckDetail). Grading delegates to [SrsRepository.review], which owns the
 * SM-2-lite scheduler — the VM only sequences the queue and tracks reveal/progress.
 */
class StudySessionViewModel(
    private val deckId: String?,
    private val srsRepository: SrsRepository,
    private val deckRepository: DeckRepository,
    mainScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        mainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<StudySessionUiState>(StudySessionUiState.Loading)
    val state: StateFlow<StudySessionUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudySessionEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<StudySessionEffect> = _effects.asSharedFlow()

    private var queue: List<Card> = emptyList()
    private var index = 0
    private var revealed = false
    private var reviewedCount = 0
    private var deckTitle = ""
    private var gradeJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        scope.launch {
            _state.value = StudySessionUiState.Loading
            deckTitle = deckId?.let { deckRepository.getLocal(it)?.title }.orEmpty()
            runCatching {
                if (deckId == null) srsRepository.dueToday() else srsRepository.dueForDeck(deckId)
            }
                .onSuccess { cards ->
                    queue = cards
                    index = 0
                    revealed = false
                    reviewedCount = 0
                    emitCurrent()
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.value = StudySessionUiState.Error(err.message ?: "Could not load cards.")
                }
        }
    }

    fun onReveal() {
        if (!revealed) {
            revealed = true
            emitCurrent()
        }
    }

    fun onGrade(grade: SrsGrade) {
        if (gradeJob?.isActive == true) return
        val card = queue.getOrNull(index) ?: return
        gradeJob = scope.launch {
            srsRepository.review(card, grade)
                .onFailure { Log.e(TAG, "grade: FAILED — ${it.message}", it) }
            reviewedCount++
            index++
            revealed = false
            emitCurrent()
        }
    }

    fun onSpeak() {
        val card = queue.getOrNull(index) ?: return
        val text = (if (revealed) card.back.text else card.front.text)?.takeIf { it.isNotBlank() }
            ?: return
        scope.launch { _effects.emit(StudySessionEffect.Speak(text)) }
    }

    fun onClose() {
        scope.launch { _effects.emit(StudySessionEffect.Close) }
    }

    fun onDispose() {
        gradeJob?.cancel()
        scope.cancel()
    }

    private fun emitCurrent() {
        if (queue.isEmpty()) {
            _state.value = StudySessionUiState.Empty(deckTitle)
            return
        }
        val card = queue.getOrNull(index)
        if (card == null) {
            _state.value = StudySessionUiState.Complete(reviewedCount)
            return
        }
        scope.launch {
            // Cache is warmed by the queue build; a null state means a new (never-reviewed) card.
            val srsState = srsRepository.stateFor(card.id)
            val labels = srsState.previewIntervals(card.id, epochMillis())
            _state.value = StudySessionUiState.Reviewing(
                deckTitle = deckTitle.ifBlank { card.deckId },
                position = index + 1,
                total = queue.size,
                frontText = card.front.text.orEmpty(),
                backText = card.back.text.orEmpty(),
                backLabel = card.front.text?.uppercase(),
                revealed = revealed,
                intervals = labels,
            )
        }
    }

    companion object {
        private const val TAG = "Echo/StudyVM"
    }
}

sealed interface StudySessionUiState {
    data object Loading : StudySessionUiState

    /** No cards due — "all caught up". */
    data class Empty(val deckTitle: String) : StudySessionUiState

    data class Reviewing(
        val deckTitle: String,
        val position: Int,
        val total: Int,
        val frontText: String,
        val backText: String,
        /** Small uppercase hint shown above the answer on the card back (the front prompt). */
        val backLabel: String?,
        val revealed: Boolean,
        val intervals: Map<SrsGrade, String>,
    ) : StudySessionUiState

    data class Complete(val reviewed: Int) : StudySessionUiState

    data class Error(val message: String) : StudySessionUiState
}

sealed interface StudySessionEffect {
    data class Speak(val text: String) : StudySessionEffect
    data object Close : StudySessionEffect
}
