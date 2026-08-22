package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeSettingsRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudySessionViewModelTest {

    private val srsRepo = FakeSrsRepository()
    private val deckRepo = FakeDeckRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val settingsRepo = FakeSettingsRepository()

    private fun viewModel(deckId: String? = "deck1") = StudySessionViewModel(
        deckId = deckId,
        srsRepository = srsRepo,
        deckRepository = deckRepo,
        settingsRepository = settingsRepo,
    )

    private suspend fun seedDeck() {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = listOf(
            testCard("c1", front = "hola", back = "hello"),
            testCard("c2", front = "gracias", back = "thank you"),
        )
    }

    @Test
    fun loadBuildsTheDueQueueAndShowsTheFirstCardFaceDown() = runTest {
        seedDeck()
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals("Spanish", state.deckTitle)
        assertEquals(expected = 1, actual = state.position)
        assertEquals(expected = 2, actual = state.total)
        assertEquals("hola", state.frontText)
        assertEquals("hello", state.backText)
        assertTrue(!state.revealed)
        assertEquals(SrsGrade.entries.toSet(), state.intervals.keys)
    }

    @Test
    fun revealFlipsTheCurrentCard() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onReveal()
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(state.revealed)
        assertEquals(expected = 1, actual = state.position)
    }

    @Test
    fun gradingAdvancesToTheNextCardFaceDown() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onReveal()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.position)
        assertEquals("gracias", state.frontText)
        assertTrue(!state.revealed)
        assertEquals(listOf("c1" to SrsGrade.Good), srsRepo.reviews.map { it.first.id to it.second })
    }

    @Test
    fun gradingTheLastCardCompletesTheSession() = runTest {
        seedDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Again)
        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertEquals(expected = 2, actual = state.reviewed)
        assertEquals(listOf(SrsGrade.Good, SrsGrade.Again), srsRepo.reviews.map { it.second })
    }

    @Test
    fun emptyQueueShowsAllCaughtUp() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = emptyList()
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(StudySessionUiState.Empty("Spanish"), vm.state.value)
    }

    @Test
    fun nullDeckIdStudiesEveryDueCard() = runTest {
        srsRepo.due = listOf(
            testCard("c1", deckId = "deck1"),
            testCard("c9", deckId = "deck2"),
        )
        val vm = viewModel(deckId = null)

        advanceUntilIdle()

        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(expected = 2, actual = state.total)
    }

    // ── sync failures (#91) ──────────────────────────────────────────────

    @Test
    fun aFailedBackgroundFlushWarnsWithoutEndingTheSession() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()

        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        // A warning over the card, not an Error state: the reviews are buffered and journalled, so
        // blanking the card the user is mid-way through would cost them more than the warning.
        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(ErrorReason.StorageFull, state.syncError)
    }

    @Test
    fun theSyncWarningOutlivesTheNextCard() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()
        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        vm.onGrade(SrsGrade.Good)
        runCurrent()

        // emitCurrent rebuilds the state on every card, and a warning the user has not acted on
        // must not vanish because they graded the next one.
        val state = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertEquals(ErrorReason.StorageFull, state.syncError)
    }

    @Test
    fun dismissingTheSyncWarningClearsIt() = runTest {
        seedDeck()
        val vm = viewModel()
        runCurrent()
        srsRepo.emitFlushFailure(ErrorReason.StorageFull)
        runCurrent()

        vm.onDismissSyncError()
        runCurrent()

        assertNull(assertIs<StudySessionUiState.Reviewing>(vm.state.value).syncError)
    }

    @Test
    fun reachingTheDailyGoalIsAnnouncedOnceAndStopsNothing() = runTest(mainDispatcher) {
        // The whole point of a soft goal: you are told, and the next card is already there.
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 2))
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        // More cards than the goal, so "keep going" is something the queue can actually offer.
        srsRepo.due = (1..6).map { testCard("c$it", front = "front $it", back = "back $it") }
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertFalse(
            assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalReached,
            "announced before the goal was reached",
        )

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        val reached = assertIs<StudySessionUiState.Reviewing>(vm.state.value)
        assertTrue(reached.goalReached, "the crossing was not announced")
        assertTrue(reached.total > reached.position - 1, "the queue was cut short at the goal")

        // It stays up across the next card rather than flashing past — grading is fast, and a
        // banner that vanished on the following grade could be missed entirely.
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertTrue(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalReached)

        // Once waved away it does not come back, which is what stops it nagging for carrying on.
        vm.onDismissGoalReached()
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertFalse(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalReached)
    }

    @Test
    fun theGoalBannerCanBeDismissed() = runTest(mainDispatcher) {
        settingsRepo.setStudySettings(StudySettings(newCardsPerDayGoal = 1))
        seedDeck()

        val vm = viewModel()
        advanceUntilIdle()
        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()
        assertTrue(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalReached)

        vm.onDismissGoalReached()
        advanceUntilIdle()

        assertFalse(assertIs<StudySessionUiState.Reviewing>(vm.state.value).goalReached)
    }

    @Test
    fun theCongratsScreenSaysWhenTheNextReviewLands() = runTest(mainDispatcher) {
        // "All done! 🎊 / You reviewed 4 cards." said nothing about what happens next (#101 §5).
        srsRepo.nextDue = 1_234L
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Spanish")
        srsRepo.due = listOf(testCard("c1", front = "hola", back = "hello"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onGrade(SrsGrade.Good)
        advanceUntilIdle()

        val complete = assertIs<StudySessionUiState.Complete>(vm.state.value)
        assertEquals(expected = 1_234L, actual = complete.nextDueAtMillis)
    }
}
