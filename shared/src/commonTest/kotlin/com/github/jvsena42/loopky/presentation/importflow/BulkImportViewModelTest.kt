package com.github.jvsena42.loopky.presentation.importflow

import com.github.jvsena42.loopky.testing.FakeImportRepository
import com.github.jvsena42.loopky.testing.testDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BulkImportViewModelTest {

    private val importRepo = FakeImportRepository(
        draft = testDraft("hola" to "hello", "gracias" to "thank you"),
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

    private fun viewModel() = BulkImportViewModel(importRepository = importRepo)

    // ── suggested title ──────────────────────────────────────────────────

    @Test
    fun theApkgsOwnDeckNameBeatsTheFileName() {
        // "Japanese Core 2000" is what the user calls this deck; the other is what their file
        // manager calls it. The deck name was read from the collection and then discarded.
        val title = viewModel().suggestedTitleFor(
            deckName = "Japanese Core 2000",
            fileName = "japanese_core_2000_step_01.apkg",
        )

        assertEquals(expected = "Japanese Core 2000", actual = title)
    }

    @Test
    fun aFileNameBecomesATitleWorthShowing() {
        val title = viewModel().suggestedTitleFor(deckName = null, fileName = "spanish_basics-v2.txt")

        assertEquals(expected = "spanish basics v2", actual = title)
    }

    @Test
    fun aBlankDeckNameFallsBackToTheFileName() {
        // Anki's collection can carry an empty or whitespace deck name.
        val title = viewModel().suggestedTitleFor(deckName = "   ", fileName = "kanji.apkg")

        assertEquals(expected = "kanji", actual = title)
    }

    @Test
    fun anOverlongNameIsCappedSoTheCommitScreenOpensValid() {
        // Uncapped, the commit screen opened with TitleTooLong already showing.
        val title = viewModel().suggestedTitleFor(deckName = "x".repeat(200), fileName = "deck.txt")

        assertEquals(expected = PublishDeckViewModel.TITLE_MAX_LENGTH, actual = title?.length)
    }

    @Test
    fun aNamelessFileSuggestsNothingRatherThanEmptyText() {
        assertNull(viewModel().suggestedTitleFor(deckName = null, fileName = ""))
    }

    // ── parse ────────────────────────────────────────────────────────────

    // ── error modes ──────────────────────────────────────────────────────

    @Test
    fun aFileThatReadsButHoldsNoCardsSaysSoRatherThanBlamingTheRead() = runTest {
        // These used to collapse into one message string, so someone who picked a photo got a
        // parser complaint and someone whose export was empty got a read error.
        importRepo.draft = null
        val vm = viewModel()

        vm.onFileLoaded(fileName = "empty.txt", text = "")
        runCurrent()

        val state = assertIs<BulkImportUiState.Error>(vm.state.value)
        assertEquals(expected = BulkImportError.NoCardsFound, actual = state.reason)
    }

    @Test
    fun aFailedReadKeepsItsOwnReason() = runTest {
        val vm = viewModel()

        vm.onFileReadFailed(BulkImportError.TooLarge)

        val state = assertIs<BulkImportUiState.Error>(vm.state.value)
        assertEquals(expected = BulkImportError.TooLarge, actual = state.reason)
    }

    @Test
    fun pickingAnotherFileReturnsToThePicker() = runTest {
        val vm = viewModel()
        vm.onFileLoaded(fileName = "animals.txt", text = "dog\tcachorro")
        runCurrent()
        assertIs<BulkImportUiState.Ready>(vm.state.value)

        vm.onPickAnother()

        // Re-picking used to mean cancelling out of the flow entirely.
        assertIs<BulkImportUiState.Idle>(vm.state.value)
    }

    @Test
    fun aParsedFileCarriesItsSuggestedTitleOntoTheDraft() = runTest {
        val vm = viewModel()

        vm.onFileLoaded(fileName = "animals_pt.txt", text = "dog\tcachorro")
        runCurrent()

        assertIs<BulkImportUiState.Ready>(vm.state.value)
        // The commit screen reads it from here, not from a nav argument.
        assertEquals(expected = "animals pt", actual = importRepo.draft?.suggestedTitle)
    }
}
