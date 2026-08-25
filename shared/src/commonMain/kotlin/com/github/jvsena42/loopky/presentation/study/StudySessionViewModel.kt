package com.github.jvsena42.loopky.presentation.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.AnswerMatcher
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DEFAULT_NEW_CARDS_PER_DAY
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SpeakMatcher
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome
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
 * Drives the spaced-repetition study loop.
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
    private var typePhase: TypePhase = TypePhase.Off
    private var typedAnswer: String = ""

    /**
     * Whether the answer is actually legible right now.
     *
     * Not the same question as [revealed]: on a typing card the flip is never blocked — tapping
     * turns the card as it always has — but the words on the back stay masked until the answer is
     * checked or given up on. Anything that acts on "the side facing the user" has to ask this
     * rather than [revealed], or Listen would read out the very answer the mask is hiding.
     */
    private val answerVisible: Boolean get() = revealed && typePhase !is TypePhase.Answering

    /** What the in-flight pronunciation attempt grades against. See [targetFor]. */
    private var speakTarget: SpeakTarget? = null

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
     * The goal celebration is on screen.
     *
     * Held on the VM as well as in the state because [emitCurrent] rebuilds the state for every
     * card — the same reason [syncError] is. Whether it may be shown *at all* is not this flag's
     * business: that lives in [com.github.jvsena42.loopky.domain.model.DailyStudyProgress], so it
     * survives leaving the screen and resets with the day.
     */
    private var goalReached = false

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
                    resetTyping()
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
            resetTyping()
            celebrateGoalIfOwed()
            emitCurrent()
        }
    }

    /**
     * Show the celebration if today has earned one and not seen it yet.
     *
     * The queue is untouched — the card behind the celebration is already the next one, and
     * "Keep studying" simply dismisses it. This says "you have done what you set out to do today";
     * it does not stop anyone doing more.
     *
     * Marked as shown the moment it goes up, not when it is dismissed: the point of persisting it
     * is that the user sees it once a day, and a process killed while it is on screen has still
     * shown it.
     */
    private suspend fun celebrateGoalIfOwed() {
        val goal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal
        if (!srsRepository.dailyProgress.value.owesGoalCelebration(goal)) return
        // The celebration renders over a card, so there has to be one. Grading the last card of a
        // session straight past the goal goes to the "All done!" screen instead, which says the
        // same thing in its own line — and marking it shown here would spend the day's one
        // celebration on a screen that never appeared.
        if (queue.getOrNull(index) == null) return
        goalReached = true
        goalCelebration = GoalCelebration(
            newCardsToday = srsRepository.dailyProgress.value.newCards,
            goal = goal,
        )
        runSuspendCatching { srsRepository.markGoalCelebrated() }
            .onFailure { Log.e(TAG, "markGoalCelebrated: FAILED — ${it.message}", it) }
    }

    private var goalCelebration: GoalCelebration? = null

    /** "Keep studying" — dismiss the celebration and carry on with the card already underneath. */
    fun onContinueAfterGoal() {
        goalReached = false
        _state.update { current ->
            (current as? StudySessionUiState.Reviewing)?.copy(goalCelebration = null) ?: current
        }
    }

    // ── Type the answer (#115) ───────────────────────────────────────────────

    fun onAnswerChange(text: String) {
        if (typePhase !is TypePhase.Answering) return
        typedAnswer = text
        _state.update { current ->
            (current as? StudySessionUiState.Reviewing)?.copy(typedAnswer = text) ?: current
        }
    }

    /**
     * Compare what was typed against the card's back.
     *
     * Only a **correct** answer opens the card. Anything else says so and leaves you answering,
     * with what you wrote still in the field: handing over the answer the moment you slip turns
     * one typo into a lost card, and a near miss is a hint to fix an accent, not a verdict. The
     * way out of a card you genuinely cannot answer is [onGiveUp], which is always right there.
     *
     * No grade is chosen here either way. The matcher's strictness decides what to *say*, never
     * what to schedule; picking Again/Hard/Good/Easy stays the user's, exactly as after a tap-flip.
     */
    fun onCheckAnswer() {
        // A check landing after the queue advanced would otherwise grade the next card's text.
        if (gradeJob?.isActive == true) return
        if (typePhase !is TypePhase.Answering) return
        val expected = queue.getOrNull(index)?.back?.text
            ?.takeIf { AnswerMatcher.isTypable(it) } ?: return
        val typed = typedAnswer.trim()
        if (typed.isEmpty()) return
        val outcome = AnswerMatcher.judge(typed, expected)
        if (outcome == TypedAnswerOutcome.Correct) {
            typePhase = TypePhase.Correct(typed)
            revealed = true
        } else {
            // `revealed` is deliberately untouched: if the card was already flipped it stays
            // flipped, still showing the input — a miss changes what is said, not what is shown.
            typePhase = TypePhase.Answering(lastMiss = TypeMiss(typed, outcome))
        }
        emitCurrent()
    }

    /**
     * Show the answer and nothing else.
     *
     * Always available while answering, with no confirm step and no penalty: this is the escape
     * hatch that keeps a stuck card from trapping a session. It deliberately does not choose,
     * pre-select or highlight a difficulty — a "you clearly meant Again" nudge would quietly turn
     * an escape into a punishment, and the grade is not this button's to guess.
     */
    fun onGiveUp() {
        if (gradeJob?.isActive == true) return
        if (typePhase !is TypePhase.Answering) return
        typePhase = TypePhase.GaveUp
        revealed = true
        emitCurrent()
    }

    private fun resetTyping() {
        typePhase = TypePhase.Off
        typedAnswer = ""
    }

    /** Start pronunciation practice for the side facing the user (gated on the deck's speak opt-in). */
    fun onSpeakTest() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.speakEnabled) return
        startRecognition(targetFor(s) ?: return)
    }

    fun onSpeechResult(text: String) {
        // Graded against what was captured when listening began, not against whichever side is
        // facing now: the answer arrives asynchronously, and a card flipped in the meantime would
        // otherwise mark a correct utterance wrong against the opposite side's text.
        val target = speakTarget ?: return
        // The target's own language, not the deck's front: it decides which language's number
        // words "10" may be spoken as, and the two sides of a card rarely share one.
        val result = SpeakMatcher.match(text, target.expected, target.languageTag)
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

    /** Another go at the same target — never re-derived, so a retry cannot change what it grades. */
    fun onSpeakRetry() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.speakEnabled) return
        startRecognition(speakTarget ?: targetFor(s) ?: return)
    }

    /**
     * What a pronunciation attempt grades against: the side facing the user, and *that side's*
     * language. Null when the side has no text to say, or when the deck never declared a pair —
     * given no language the recognizer would transcribe with the reader's own locale's model,
     * which is why `speakEnabled` folds in `speechReady`.
     */
    private fun targetFor(s: StudySessionUiState.Reviewing): SpeakTarget? {
        val card = queue.getOrNull(index) ?: return null
        val side = if (answerVisible) card.back else card.front
        val expected = side.text?.takeIf { it.isNotBlank() } ?: return null
        val languageTag = (if (answerVisible) s.backLang else s.frontLang) ?: return null
        return SpeakTarget(expected, languageTag)
    }

    private fun startRecognition(target: SpeakTarget) {
        speakTarget = target
        setSpeakPhase(SpeakPhase.Listening(target.expected))
        viewModelScope.launch {
            _effects.emit(
                StudySessionEffect.StartSpeechRecognition(target.expected, target.languageTag),
            )
        }
    }

    fun onSpeakDismiss() {
        setSpeakPhase(SpeakPhase.Idle)
    }

    private fun setSpeakPhase(phase: SpeakPhase) {
        if (phase is SpeakPhase.Idle) speakTarget = null
        speakPhase = phase
        val s = _state.value
        if (s is StudySessionUiState.Reviewing) {
            _state.update { s.copy(speakPhase = phase) }
        }
    }

    /**
     * Read the side currently facing the user, in *that side's* language — the front and back of a
     * vocabulary card are routinely different ones.
     *
     * Gated here and not only in the UI: like the announce gate, the check belongs on the action,
     * so a deck that never declared its languages cannot be read aloud in the reader's accent.
     *
     * What is read is the phrase, not the card's editorial asides — see [AnswerMatcher.stripParentheticals].
     */
    fun onSpeak() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.listenEnabled) return
        val card = queue.getOrNull(index) ?: return
        // answerVisible, not revealed: on a flipped-but-masked typing card the back is on screen
        // but hidden, and reading it aloud would hand over the answer the mask is withholding.
        // Parenthesized asides are dropped for the same reason the matchers drop them: "(formal)"
        // is a note about the card, and an engine handed it reads the note out as a word.
        val text = (if (answerVisible) card.back.text else card.front.text)
            ?.let(AnswerMatcher::stripParentheticals)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val languageTag = (if (answerVisible) s.backLang else s.frontLang) ?: return
        viewModelScope.launch { _effects.emit(StudySessionEffect.Speak(text, languageTag)) }
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
            typePhase = typePhaseFor(card, deck)
            _state.update { StudySessionUiState.Reviewing(
                deckTitle = title,
                goalCelebration = goalCelebration.takeIf { goalReached },
                position = index + 1,
                total = queue.size,
                frontText = card.front.text.orEmpty(),
                backText = card.back.text.orEmpty(),
                backLabel = card.front.text,
                revealed = revealed,
                intervals = labels,
                listenEnabled = deck?.listenEnabled == true && deck.speechReady,
                speakEnabled = deck?.speakEnabled == true && deck.speechReady,
                speakPhase = speakPhase,
                typePhase = typePhase,
                typedAnswer = typedAnswer,
                frontLang = deck?.frontLang,
                backLang = deck?.backLang,
                deckId = card.deckId,
                authorPubky = deck?.authorPubky.orEmpty(),
                frontImageRef = card.front.imageRef,
                backImageRef = card.back.imageRef,
                syncError = syncError,
            ) }
        }
    }

    /**
     * Where [card] should sit in the typing flow, given what the deck opted into.
     *
     * A card with nothing typable on the back has nothing to type against, and one with no prompt
     * at all has nothing to type *from*. Either way it silently falls back to the ordinary
     * tap-to-reveal — an image-only answer, which Anki imports produce, must never put up an
     * input with nothing to match.
     *
     * The back test is [AnswerMatcher.isTypable], deliberately not `isNotBlank()`. A back of
     * `"—"`, `"..."` or a lone emoji is not blank but normalizes to nothing, so no answer can
     * ever match it — and since a wrong Check no longer reveals, such a card would be a dead end
     * with Give up as its only exit.
     *
     * Otherwise the phase already in progress is kept; [TypePhase.Off] doubles as the fresh-card
     * state, so that is where a new card starts answering.
     */
    private fun typePhaseFor(card: Card, deck: Deck?): TypePhase {
        val eligible = deck?.typeEnabled == true &&
            AnswerMatcher.isTypable(card.back.text.orEmpty()) &&
            (!card.front.text.isNullOrBlank() || card.front.imageRef != null)
        return when {
            !eligible -> TypePhase.Off
            typePhase is TypePhase.Off -> TypePhase.Answering()
            else -> typePhase
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
         * Today's new-card goal has just been met, so the celebration is on screen.
         *
         * A congratulation, not a stop sign: the queue behind it is unchanged and the next card is
         * already loaded, so "Keep studying" costs nothing but a dismissal. Null the rest of the
         * time, which is almost always — it is shown once a day.
         */
        val goalCelebration: GoalCelebration? = null,
        val position: Int,
        val total: Int,
        val frontText: String,
        val backText: String,
        /**
         * The prompt, shown small above the answer on the card back — as the author wrote it.
         * Not uppercased: a deck whose two sides differ only in case would have the back's label
         * spell out its own answer.
         */
        val backLabel: String?,
        val revealed: Boolean,
        val intervals: Map<SrsGrade, String>,
        /**
         * Both already fold in the deck's `speechReady`: with no declared language pair the OS
         * engines fall back to the reader's locale, so the features are not offered at all.
         */
        val listenEnabled: Boolean = false,
        val speakEnabled: Boolean = false,
        val speakPhase: SpeakPhase = SpeakPhase.Idle,
        /**
         * Where this card is in the type-the-answer flow, or [TypePhase.Off] when the deck has
         * not opted in — or when this particular card cannot be typed. See [answerHidden].
         */
        val typePhase: TypePhase = TypePhase.Off,
        /** What is in the input, held here so it survives the state rebuild every reveal causes. */
        val typedAnswer: String = "",
        /** BCP-47 tags for the two sides, non-null whenever [listenEnabled]/[speakEnabled] are. */
        val frontLang: String? = null,
        val backLang: String? = null,
        val deckId: String = "",
        /** The deck's author — media on a followed deck lives on their homeserver, not yours. */
        val authorPubky: String = "",
        /** Front-side image, shown as a circular avatar on the card back. */
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
    ) : StudySessionUiState {
        /**
         * The card may be flipped, but the answer on it is masked.
         *
         * The flip itself is never blocked by typing — tapping turns the card as it always has.
         * What typing withholds is the word, not the gesture.
         */
        val answerHidden: Boolean get() = typePhase is TypePhase.Answering

        /**
         * Whether the four SRS buttons belong on screen.
         *
         * [revealed] alone is not enough once the answer can be revealed-but-masked: grading a
         * card you have not been shown the answer to is not a judgement about anything.
         */
        val gradesAvailable: Boolean get() = revealed && !answerHidden
    }

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

/** What the goal celebration says. Its own type so the screen needs no arithmetic. */
data class GoalCelebration(val newCardsToday: Int, val goal: Int)

/** Pronunciation-practice sheet state for the current card back. */
/** The text and language one pronunciation attempt is measured against. */
private data class SpeakTarget(val expected: String, val languageTag: String)

sealed interface SpeakPhase {
    data object Idle : SpeakPhase

    /**
     * [expected] is the captured target, carried on the phase so the sheet prompts with the very
     * text [onSpeechResult] grades against — reading it off the state's front/back would let the
     * prompt and the grading disagree about which side the attempt is for.
     */
    data class Listening(val expected: String) : SpeakPhase

    data class Correct(val heard: String) : SpeakPhase
    data class Wrong(val heard: String, val expected: String) : SpeakPhase
}

/**
 * Where the current card sits in the type-the-answer flow (#115).
 *
 * The flip is orthogonal to all of this: a card can be turned face-up in any phase. What the
 * phase decides is whether the answer on that face is legible.
 */
sealed interface TypePhase {
    /** The deck has not opted in, or this card cannot be typed. Tap-to-reveal, exactly as before. */
    data object Off : TypePhase

    /**
     * Input on screen, answer hidden. The only phase in which Give up is offered — and the phase
     * a rejected attempt stays in, which is why [lastMiss] lives here rather than on a phase of
     * its own. Null until something has been submitted and turned down.
     */
    data class Answering(val lastMiss: TypeMiss? = null) : TypePhase

    /**
     * Typed correctly, so the back is now on show. The only Check outcome that opens the card —
     * and it still picks no SRS grade, so the matcher's strictness cannot reach a card's `dueAt`.
     */
    data class Correct(val typed: String) : TypePhase

    /**
     * The user asked for the answer. Carries nothing on purpose: giving up reveals the back and
     * says not one word more about how the card should be graded.
     */
    data object GaveUp : TypePhase
}

/**
 * An attempt that was turned down, kept so the screen can say how near it came while the card
 * stays shut. [outcome] is never [TypedAnswerOutcome.Correct] — that one opens the card instead.
 */
data class TypeMiss(val typed: String, val outcome: TypedAnswerOutcome)

sealed interface StudySessionEffect {
    /** [languageTag] is BCP-47; without it the engine reads the card in the reader's own locale. */
    data class Speak(val text: String, val languageTag: String) : StudySessionEffect
    data class StartSpeechRecognition(
        val expected: String,
        val languageTag: String,
    ) : StudySessionEffect
    data object Close : StudySessionEffect
}
