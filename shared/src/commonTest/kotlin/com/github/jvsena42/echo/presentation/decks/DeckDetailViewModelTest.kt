package com.github.jvsena42.echo.presentation.decks

import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.testing.FakeCardRepository
import com.github.jvsena42.echo.testing.FakeDeckRepository
import com.github.jvsena42.echo.testing.FakeIdentityRepository
import com.github.jvsena42.echo.testing.FakeMediaRepository
import com.github.jvsena42.echo.testing.FakeSrsRepository
import com.github.jvsena42.echo.testing.TEST_PUBKY
import com.github.jvsena42.echo.testing.testCard
import com.github.jvsena42.echo.testing.testDeck
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

/**
 * Deck detail is the only screen that shows what is *inside* a deck. It used to read the card
 * cache, which is empty until something else has loaded the deck — so the list was blank on
 * every cold open, and permanently blank for a deck you don't own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailViewModelTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val srsRepo = FakeSrsRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(deckId: String = "deck1", authorPubky: String? = null) =
        DeckDetailViewModel(
            deckId = deckId,
            authorPubky = authorPubky,
            deckRepository = deckRepo,
            cardRepository = cardRepo,
            identityRepository = identityRepo,
            srsRepository = srsRepo,
            mediaRepository = FakeMediaRepository(),
        )

    @Test
    fun `shows the cards on a cold cache`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            cardIndex = listOf(CardIndexEntry("c1", 1L), CardIndexEntry("c2", 2L)),
        )
        // Nothing has read this deck yet — the cards exist only on the homeserver.
        cardRepo.seedRemote(testCard("c1"), testCard("c2"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(listOf("c1", "c2"), state.cardPreviews.map { it.id })
    }

    @Test
    fun `shows the cards of a deck you do not own`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            authorPubky = "friendpk",
            cardIndex = listOf(CardIndexEntry("c1", 1L)),
        )
        cardRepo.seedRemote(testCard("c1", front = "el zorro", back = "the fox"))

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(false, state.isOwned)
        assertEquals("el zorro", state.cardPreviews.single().frontText)
        assertEquals("the fox", state.cardPreviews.single().backText)
    }

    @Test
    fun `orders the cards the way the manifest declares`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            cardIndex = listOf(CardIndexEntry("zebra", 1L), CardIndexEntry("apple", 2L)),
        )
        cardRepo.seedRemote(testCard("apple"), testCard("zebra"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(listOf("zebra", "apple"), state.cardPreviews.map { it.id })
    }

    @Test
    fun `a deck with no cards is content rather than an error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardIndex = emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertTrue(state.cardPreviews.isEmpty())
    }

    @Test
    fun `an unreadable card list surfaces as a retryable error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardIndex = listOf(CardIndexEntry("c1", 1L)))
        cardRepo.fetchError = IllegalStateException("homeserver unreachable")

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Error>(vm.state.value)
        assertTrue(state.canRetry)
    }

    @Test
    fun `an owned deck is marked as owned`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(true, assertIs<DeckDetailUiState.Content>(vm.state.value).isOwned)
    }
}
