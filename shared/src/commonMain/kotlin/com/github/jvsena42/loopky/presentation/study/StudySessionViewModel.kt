package com.github.jvsena42.loopky.presentation.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DEFAULT_NEW_CARDS_PER_DAY
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SpeakMatcher
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
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
    private val settingsRepository: SettingsRepository,
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

    /**
     * Why buffered reviews are not reaching the homeserver, if they are not. Held on the VM rather
     * than only in the state because [emitCurrent] rebuilds the state on every card, and a warning
     * the user has not acted on must not vanish when they grade the next one.
     */
    private var syncError: ErrorReason? = null

    /** id → title, warmed lazily from [DeckRepository.listOwned] so multi-deck sessions can label each card. */
    private var deckTitles: Map<String, String> = emptyMap()
    private var gradeJob: Job? = null

    /**
     * The daily goal has been reached and the user has not waved it away yet.
     *
     * Held on the VM as well as in the state because [emitCurrent] rebuilds the state for every
     * card — the same reason [syncError] is. Announced once per crossing and never again, since a
     * banner that reappeared on every subsequent card would read as nagging for carrying on, which
     * is the exact opposite of what a soft goal is for.
     */
    private var goalReached = false
    private var goalAnnounced = false

    init {
        load()
        // The flush that fails is usually the one started as this screen goes away, so the
        // repository replays its last failure — a collector attaching here still learns about it.
        viewModelScope.launch {
            srsRepository.flushFailures.collect { reason ->
                Log.e(TAG, "flush failed — $reason")
                setSyncError(reason)
            }
        }
    }

    fun onRefresh() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update { StudySessionUiState.Loading }
            deckTitle = deckId?.let { resolveDeckTitle(it) }.orEmpty()
            runSuspendCatching {
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
        val newCardsBefore = srsRepository.dailyProgress.value.newCards
        gradeJob = viewModelScope.launch {
            srsRepository.review(card, grade)
                .onFailure { err ->
                    Log.e(TAG, "grade: FAILED — ${err.message}", err)
                    // The automatic every-FLUSH_EVERY flush comes back through here, so this is
                    // where a full quota first becomes visible mid-session rather than only when
                    // the screen closes. The grade itself is buffered either way; the card still
                    // advances, and the banner says the writing is what stopped (#91).
                    syncError = err.toErrorReason()
                }
            reviewedCount++
            index++
            revealed = false
            speakPhase = SpeakPhase.Idle
            noteGoalCrossing(newCardsBefore)
            emitCurrent()
        }
    }

    /**
     * Raise the banner on the grade that *crosses* the goal, and only that one.
     *
     * The queue is untouched either way — the next card loads exactly as it would have. This says
     * "you have done what you set out to do today"; it does not stop anyone doing more.
     */
    private fun noteGoalCrossing(newCardsBefore: Int) {
        if (goalAnnounced) return
        val goal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal
        val after = srsRepository.dailyProgress.value.newCards
        if (newCardsBefore < goal && after >= goal) {
            goalReached = true
            goalAnnounced = true
        }
    }

    /** The user has read the goal banner. */
    fun onDismissGoalReached() {
        goalReached = false
        _state.update { current ->
            (current as? StudySessionUiState.Reviewing)?.copy(goalReached = false) ?: current
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

    /** Record a sync failure and reflect it into whatever state is on screen right now. */
    private fun setSyncError(reason: ErrorReason) {
        syncError = reason
        _state.update { current ->
            when (current) {
                is StudySessionUiState.Reviewing -> current.copy(syncError = reason)
                is StudySessionUiState.Complete -> current.copy(syncError = reason)
                else -> current
            }
        }
    }

    /** The user has read the sync warning. Clears it from the screen, not from the buffer. */
    fun onDismissSyncError() {
        syncError = null
        _state.update { current ->
            when (current) {
                is StudySessionUiState.Reviewing -> current.copy(syncError = null)
                is StudySessionUiState.Complete -> current.copy(syncError = null)
                else -> current
            }
        }
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
            _state.update { StudySessionUiState.Complete(reviewedCount, syncError) }
            // After the state, not before: the congrats screen must not wait on a lookup, and
            // nextDueAt is cache-only but still suspending.
            viewModelScope.launch {
                val nextDue = runSuspendCatching { srsRepository.nextDueAt() }
                    .onFailure { Log.e(TAG, "nextDueAt: FAILED — ${it.message}", it) }
                    .getOrNull()
                val progress = srsRepository.dailyProgress.value
                _state.update { current ->
                    (current as? StudySessionUiState.Complete)?.copy(
                        nextDueAtMillis = nextDue,
                        newCardsToday = progress.newCards,
                        newCardsGoal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal,
                    ) ?: current
                }
            }
            return
        }
        viewModelScope.launch {
            // The repository owns this: the first-review intervals are a user setting, so labels
            // computed here would need a SettingsRepository of their own and could drift from what
            // grading actually writes.
            val labels = srsRepository.previewIntervals(card)
            val title = deckTitle.ifBlank { resolveDeckTitle(card.deckId) }.ifBlank { card.deckId }
            val deck = deckRepository.getLocal(card.deckId)
            _state.update { StudySessionUiState.Reviewing(
                deckTitle = title,
                goalReached = goalReached,
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
                backImageRef = card.back.imageRef,
                syncError = syncError,
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
            deckTitles = runSuspendCatching { deckRepository.listOwned() }
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
        /**
         * Today's new-card goal has just been met. A congratulation, not a stop sign — the queue
         * behind it is unchanged and the next card is already loaded.
         */
        val goalReached: Boolean = false,
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
        /**
         * Back-side image — the answer itself, when the answer is a picture.
         *
         * Stored, published and editable since cards gained media, but never surfaced here, so a
         * card whose answer is a diagram revealed a blank face. An Anki import puts pictures
         * exactly there (#96), which is what made the gap worth closing.
         */
        val backImageRef: MediaRef.Image? = null,
        /**
         * Set when graded reviews are not reaching the homeserver. Not an [Error]: the session
         * carries on and the reviews are buffered and journalled, so blanking the card the user is
         * mid-way through would cost them more than the warning is worth.
         */
        val syncError: ErrorReason? = null,
    ) : StudySessionUiState

    data class Complete(
        val reviewed: Int,
        val syncError: ErrorReason? = null,
        /**
         * When the next card comes up. "All done! 🎊 / You reviewed 4 cards." said nothing about
         * what happens next, which made an empty queue read as a dead end rather than as earned
         * (#101 §5). Null when nothing is scheduled at all.
         */
        val nextDueAtMillis: Long? = null,
        val newCardsToday: Int = 0,
        val newCardsGoal: Int = DEFAULT_NEW_CARDS_PER_DAY,
    ) : StudySessionUiState

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
