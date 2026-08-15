package com.github.jvsena42.loopky.presentation.study

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private fun viewModel(deckId: String? = "deck1") = StudySessionViewModel(
        deckId = deckId,
        srsRepository = srsRepo,
        deckRepository = deckRepo,
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
}
