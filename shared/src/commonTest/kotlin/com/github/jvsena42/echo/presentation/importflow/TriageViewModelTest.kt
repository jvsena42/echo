package com.github.jvsena42.echo.presentation.importflow

import com.github.jvsena42.echo.domain.model.TriageDecision
import com.github.jvsena42.echo.testing.FakeImportRepository
import com.github.jvsena42.echo.testing.testDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TriageViewModelTest {

    private val importRepo = FakeImportRepository(
        draft = testDraft("hola" to "hello", "gracias" to "thanks", "adios" to "bye"),
    )

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TriageViewModel(importRepository = importRepo)

    @Test
    fun undoStepsBackAndRestoresADiscardedCard() = runTest {
        // Discarding was irreversible on device: the card and any image attached to it in the
        // triage editor were gone with no undo and no way back.
        val vm = viewModel()

        vm.onDiscard()

        assertEquals(1, vm.state.value.currentIndex)
        assertEquals(TriageDecision.Discard, importRepo.decisions()[0])

        vm.onUndo()

        assertEquals(0, vm.state.value.currentIndex)
        assertEquals(TriageDecision.Keep, importRepo.decisions()[0])
        assertEquals(expected = 0, actual = vm.state.value.discardedCount)
    }

    @Test
    fun undoIsUnavailableOnTheFirstCard() = runTest {
        val vm = viewModel()

        assertFalse(vm.state.value.canUndo)

        vm.onKeep()

        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun undoOnTheFirstCardIsANoOp() = runTest {
        val vm = viewModel()

        vm.onUndo()

        assertEquals(0, vm.state.value.currentIndex)
    }

    @Test
    fun theKeptAndDiscardedCountsFollowUndo() = runTest {
        val vm = viewModel()

        vm.onKeep()
        vm.onDiscard()

        assertEquals(expected = 1, actual = vm.state.value.keptCount)
        assertEquals(expected = 1, actual = vm.state.value.discardedCount)

        vm.onUndo()

        assertEquals(expected = 2, actual = vm.state.value.keptCount)
        assertEquals(expected = 0, actual = vm.state.value.discardedCount)
    }
}
