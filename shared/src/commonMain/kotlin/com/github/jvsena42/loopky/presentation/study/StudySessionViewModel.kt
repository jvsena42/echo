package com.github.jvsena42.loopky.presentation.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SpeakMatcher
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.previewIntervals
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

/**
 * Drives the spaced-repetition study loop (`design/main/phone-loopky.pen` → "03 Study Session").
 *
 * [deckId] `null` studies every due card across owned decks (Home "Start studying"); a non-null
 * value studies one deck (DeckDetail). Grading delegates to [SrsRepository.review], which owns the
 * SM-2-lite scheduler — the VM only sequences the queue and tracks reveal/progress.
 */
@Suppress("TooManyFunctions")
class StudySessionViewModel(
    private val deckId: String?,
    private val srsRepository: SrsRepository,
    private val deckRepository: DeckRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<StudySessionUiState>(StudySessionUiState.Loading)
    val state: StateFlow<StudySessionUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudySessionEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<StudySessionEffect> = _effects.asSharedFlow()

    private var queue: List<Card> = emptyList()
    private var index = 0
    private var revealed = false
    private var reviewedCount = 0
    private var deckTitle = ""
    private var speakPhase: SpeakPhase = SpeakPhase.Idle

    /** id → title, warmed lazily from [DeckRepository.listOwned] so multi-deck sessions can label each card. */
    private var deckTitles: Map<String, String> = emptyMap()
    private var gradeJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update { StudySessionUiState.Loading }
            deckTitle = deckId?.let { resolveDeckTitle(it) }.orEmpty()
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
                    _state.update { StudySessionUiState.Error(err.toErrorReason()) }
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
        gradeJob = viewModelScope.launch {
            srsRepository.review(card, grade)
                .onFailure { Log.e(TAG, "grade: FAILED — ${it.message}", it) }
            reviewedCount++
            index++
            revealed = false
            speakPhase = SpeakPhase.Idle
            emitCurrent()
        }
    }

    /** Start pronunciation practice for the revealed card back (gated on the deck's speak opt-in). */
    fun onSpeakTest() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.revealed || !s.speakEnabled) return
        val expected = queue.getOrNull(index)?.back?.text?.takeIf { it.isNotBlank() } ?: return
        setSpeakPhase(SpeakPhase.Listening)
        viewModelScope.launch { _effects.emit(StudySessionEffect.StartSpeechRecognition(expected)) }
    }

    fun onSpeechResult(text: String) {
        val expected = queue.getOrNull(index)?.back?.text.orEmpty()
        val result = SpeakMatcher.match(text, expected)
        setSpeakPhase(
            if (result.correct) {
                SpeakPhase.Correct(result.heard)
            } else {
                SpeakPhase.Wrong(heard = result.heard, expected = result.expected)
            },
        )
    }

    fun onSpeechError() {
        // Treat recognition errors (no match, timeout, etc.) as a dismissal back to the card.
        setSpeakPhase(SpeakPhase.Idle)
    }

    fun onSpeakRetry() {
        val expected = queue.getOrNull(index)?.back?.text?.takeIf { it.isNotBlank() } ?: return
        setSpeakPhase(SpeakPhase.Listening)
        viewModelScope.launch { _effects.emit(StudySessionEffect.StartSpeechRecognition(expected)) }
    }

    fun onSpeakDismiss() {
        setSpeakPhase(SpeakPhase.Idle)
    }

    private fun setSpeakPhase(phase: SpeakPhase) {
        speakPhase = phase
        val s = _state.value
        if (s is StudySessionUiState.Reviewing) {
            _state.update { s.copy(speakPhase = phase) }
        }
    }

    fun onSpeak() {
        val card = queue.getOrNull(index) ?: return
        val text = (if (revealed) card.back.text else card.front.text)?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch { _effects.emit(StudySessionEffect.Speak(text)) }
    }

    fun onClose() {
        // flushAsync, not a launch here: viewModelScope is cancelled in onCleared(), so a flush
        // started as this screen goes away would be killed before it finished — losing exactly
        // the reviews it was meant to save.
        srsRepository.flushAsync()
        viewModelScope.launch { _effects.emit(StudySessionEffect.Close) }
    }

    /** Buffered reviews must reach the homeserver even if the process is about to be backgrounded. */
    override fun onCleared() {
        srsRepository.flushAsync()
        super.onCleared()
    }

    private fun emitCurrent() {
        if (queue.isEmpty()) {
            _state.update { StudySessionUiState.Empty(deckTitle) }
            return
        }
        val card = queue.getOrNull(index)
        if (card == null) {
            // Queue exhausted — persist the session's reviews.
            srsRepository.flushAsync()
            _state.update { StudySessionUiState.Complete(reviewedCount) }
            return
        }
        viewModelScope.launch {
            // Cache is warmed by the queue build; a null state means a new (never-reviewed) card.
            val srsState = srsRepository.stateFor(card.id)
            val labels = srsState.previewIntervals(card.id, epochMillis())
            val title = deckTitle.ifBlank { resolveDeckTitle(card.deckId) }.ifBlank { card.deckId }
            val deck = deckRepository.getLocal(card.deckId)
            _state.update { StudySessionUiState.Reviewing(
                deckTitle = title,
                position = index + 1,
                total = queue.size,
                frontText = card.front.text.orEmpty(),
                backText = card.back.text.orEmpty(),
                backLabel = card.front.text?.uppercase(),
                revealed = revealed,
                intervals = labels,
                listenEnabled = deck?.listenEnabled ?: true,
                speakEnabled = deck?.speakEnabled ?: true,
                speakPhase = speakPhase,
                deckId = card.deckId,
                authorPubky = deck?.authorPubky.orEmpty(),
                frontImageRef = card.front.imageRef,
            ) }
        }
    }

    /**
     * Resolves a deck title for the header. Tries the in-memory cache first ([DeckRepository.getLocal]);
     * on a cold cache it falls back to [DeckRepository.listOwned] once, which fetches + caches owned
     * decks, so a session opened without the deck pre-loaded still shows the name.
     */
    private suspend fun resolveDeckTitle(id: String): String {
        deckRepository.getLocal(id)?.title?.takeIf { it.isNotBlank() }?.let { return it }
        if (deckTitles.isEmpty()) {
            deckTitles = runCatching { deckRepository.listOwned() }
                .onFailure { Log.e(TAG, "resolveDeckTitle: listOwned FAILED — ${it.message}", it) }
                .getOrDefault(emptyList())
                .associate { it.id to it.title }
        }
        return deckTitles[id].orEmpty()
    }

    companion object {
        private const val TAG = "Loopky/StudyVM"
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
        val listenEnabled: Boolean = true,
        val speakEnabled: Boolean = true,
        val speakPhase: SpeakPhase = SpeakPhase.Idle,
        val deckId: String = "",
        /** The deck's author — media on a followed deck lives on their homeserver, not yours. */
        val authorPubky: String = "",
        /** Front-side image, shown as a circular avatar on the card back (design `aLoMj`). */
        val frontImageRef: MediaRef.Image? = null,
    ) : StudySessionUiState

    data class Complete(val reviewed: Int) : StudySessionUiState

    data class Error(val reason: ErrorReason) : StudySessionUiState
}

/** Pronunciation-practice sheet state for the current card back. */
sealed interface SpeakPhase {
    data object Idle : SpeakPhase
    data object Listening : SpeakPhase
    data class Correct(val heard: String) : SpeakPhase
    data class Wrong(val heard: String, val expected: String) : SpeakPhase
}

sealed interface StudySessionEffect {
    data class Speak(val text: String) : StudySessionEffect
    data class StartSpeechRecognition(val expected: String) : StudySessionEffect
    data object Close : StudySessionEffect
}
