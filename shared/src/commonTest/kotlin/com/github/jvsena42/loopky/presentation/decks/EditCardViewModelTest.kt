package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeckWithCards
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That a card the repository would refuse is refused *here*, in words.
 *
 * `DeckRepository.upsertCard` requires both sides to hold something, and the ViewModel only ever
 * checked whether *both* were blank. Clearing just the front and saving therefore reached the
 * repository's `require`, whose message names the card by its internal id — and that message went
 * straight to the screen as "Card hqcilpeg96jf has an empty side".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditCardViewModelTest {

    private val cardRepo = FakeCardRepository()
    private val deckRepo = FakeDeckRepository()
    private val mediaRepo = FakeMediaRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        deckRepo.decks["deck1"] = testDeckWithCards(listOf(testCard("c1")), id = "deck1")
        cardRepo.seed(testCard("c1", deckId = "deck1"))
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(cardId: String = "c1") = EditCardViewModel(
        deckId = "deck1",
        providedCardId = cardId,
        cardRepository = cardRepo,
        deckRepository = deckRepo,
        mediaRepository = mediaRepo,
    )

    @Test
    fun aBlankFrontIsRefusedInWordsRatherThanByTheRepository() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFrontTextChanged("")
        vm.onBackTextChanged("still has an answer")

        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(expected = FormError.CardSideRequired, actual = vm.state.value.frontError)
        assertNull(vm.state.value.backError)
        // The repository is never reached, so its `require` message cannot surface.
        assertNull(vm.state.value.error)
        assertTrue(deckRepo.upsertedCards.isEmpty())
    }

    @Test
    fun aBlankBackIsRefusedTheSameWay() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFrontTextChanged("still has a prompt")
        vm.onBackTextChanged("   ")

        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(expected = FormError.CardSideRequired, actual = vm.state.value.backError)
        assertNull(vm.state.value.frontError)
        assertTrue(deckRepo.upsertedCards.isEmpty())
    }

    @Test
    fun anEmptyCardNamesBothSidesRatherThanTheCard() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFrontTextChanged("")
        vm.onBackTextChanged("")

        vm.onSaveClick()
        advanceUntilIdle()

        // Pointing at the two fields beats one message under the form saying "add content".
        assertEquals(expected = FormError.CardSideRequired, actual = vm.state.value.frontError)
        assertEquals(expected = FormError.CardSideRequired, actual = vm.state.value.backError)
    }

    @Test
    fun aSideCarriesItsWeightWithAPictureAndNoWords() = runTest {
        // Anki's `Basic` note routinely puts nothing but an `<img>` in a field, so a wordless
        // side is ordinary content rather than a missing one.
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFrontTextChanged("Alkane")
        vm.onBackTextChanged("")
        vm.onBackImageGallerySelected(bytes = byteArrayOf(1, 2, 3), mime = "image/png")

        vm.onSaveClick()
        advanceUntilIdle()

        assertNull(vm.state.value.backError)
        assertTrue(deckRepo.upsertedCards.isNotEmpty())
    }
}
