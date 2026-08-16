package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
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
import kotlin.test.assertNull
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
            cardCount = 2,
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
            cardCount = 1,
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
    fun `orders the cards by study order rather than by id`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            cardCount = 2,
        )
        // Ids deliberately sort the opposite way to the study order.
        cardRepo.seedRemote(testCard("apple", ord = 1000), testCard("zebra", ord = 0))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(listOf("zebra", "apple"), state.cardPreviews.map { it.id })
    }

    @Test
    fun `a deck with no cards is content rather than an error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 0)

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertTrue(state.cardPreviews.isEmpty())
    }

    @Test
    fun `an unreadable card list surfaces as a retryable error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
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

    @Test
    fun `your own deck names you from the session without waiting for a profile fetch`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)

            val vm = viewModel()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals("Tester", state.author.displayName)
            assertEquals(TEST_PUBKY, state.author.pubky)
        }

    @Test
    fun `another author profile fills in both the name and the avatar`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        identityRepo.profiles["friendpk"] =
            PubkyIdentity("friendpk", "Ada Lovelace", avatarUrl = "https://pic", bio = null)

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals("Ada Lovelace", state.author.displayName)
        assertEquals("https://pic", state.author.avatarUrl)
    }

    @Test
    fun `the due count refreshes after this deck is studied`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        val card = testCard("c1", deckId = "deck1")
        srsRepo.due = listOf(card)

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 1, actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueCards)

        // Grading empties the queue the way a finished study session does.
        srsRepo.review(card, SrsGrade.Good)
        srsRepo.due = emptyList()
        advanceUntilIdle()

        assertEquals(expected = 0, actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueCards)
    }

    @Test
    fun `studying another deck leaves this one alone`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))

        val vm = viewModel()
        advanceUntilIdle()
        val fetchesAfterFirstLoad = cardRepo.fetchCount

        srsRepo.review(testCard("c2", deckId = "deck2"), SrsGrade.Good)
        advanceUntilIdle()

        // A review in an unrelated deck must not cost this screen another round of fetches.
        assertEquals(expected = fetchesAfterFirstLoad, actual = cardRepo.fetchCount)
        assertEquals(expected = 1, actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueCards)
    }

    @Test
    fun `an author with no profile keeps the pubky instead of blanking out`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals("friendpk", state.author.pubky)
        assertNull(state.author.displayName)
    }
}
