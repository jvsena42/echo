package com.github.jvsena42.echo.presentation.decks

import com.github.jvsena42.echo.domain.model.Card
import com.github.jvsena42.echo.domain.model.CardIndexEntry
import com.github.jvsena42.echo.domain.model.CardSide
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.testing.FakeCardRepository
import com.github.jvsena42.echo.testing.FakeDeckRepository
import com.github.jvsena42.echo.testing.FakeIdentityRepository
import com.github.jvsena42.echo.testing.TEST_PUBKY
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
import kotlin.test.assertTrue

/**
 * Saving the deck editor republishes the whole manifest and every card record, so anything the
 * editor does not expose has to survive the round-trip. It previously did not: card media and
 * the deck cover were wiped off the homeserver on every save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckEditorViewModelTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()

    private val mainDispatcher = StandardTestDispatcher()

    private val coverImage = MediaRef.Image(
        path = "media/cover.jpg",
        mime = "image/jpeg",
        sha256 = "coversha",
        width = 800,
        height = 600,
    )
    private val frontImage = MediaRef.Image(
        path = "media/front.jpg",
        mime = "image/jpeg",
        sha256 = "frontsha",
        width = 400,
        height = 300,
    )
    private val backAudio = MediaRef.Audio(
        path = "media/back.m4a",
        mime = "audio/mp4",
        sha256 = "backsha",
        durationMs = 1_200L,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedDeckWithMedia() {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            authorPubky = TEST_PUBKY,
            cardIndex = listOf(CardIndexEntry("card1", 1_000L)),
        ).copy(
            coverImageRef = coverImage,
            listenEnabled = false,
            speakEnabled = false,
        )
        cardRepo.seed(
            Card(
                id = "card1",
                deckId = "deck1",
                updatedAt = 1_000L,
                front = CardSide(text = "hola", imageRef = frontImage),
                back = CardSide(text = "hello", audioRef = backAudio),
            ),
        )
    }

    private fun viewModel() = DeckEditorViewModel(
        deckId = "deck1",
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = identityRepo,
    )

    @Test
    fun `save preserves card image and audio the editor cannot edit`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        val saved = cards.single()
        assertEquals(frontImage, saved.front.imageRef, "front image was dropped on save")
        assertEquals(backAudio, saved.back.audioRef, "back audio was dropped on save")
    }

    @Test
    fun `save preserves the deck cover and card options`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        val (deck, _) = deckRepo.published.single()
        assertEquals(coverImage, deck.coverImageRef, "deck cover was destroyed on save")
        assertEquals(false, deck.listenEnabled, "listenEnabled was reset to its default")
        assertEquals(false, deck.speakEnabled, "speakEnabled was reset to its default")
    }

    @Test
    fun `save applies edited text`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        val (deck, _) = deckRepo.published.single()
        assertEquals("Renamed", deck.title)
    }

    @Test
    fun `save leaves the sync timestamp alone for untouched cards`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        assertEquals(
            1_000L,
            cards.single().updatedAt,
            "an unchanged card should keep its updated_at so sync does not re-download it",
        )
    }

    @Test
    fun `moving a card reorders it and the new order is what gets published`() = runTest {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            cardIndex = listOf(CardIndexEntry("card1", 1L), CardIndexEntry("card2", 2L)),
        )
        cardRepo.seed(
            Card("card1", "deck1", 1L, CardSide(text = "first"), CardSide(text = "1")),
            Card("card2", "deck1", 2L, CardSide(text = "second"), CardSide(text = "2")),
        )
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("card1", "card2"), vm.state.value.cards.map { it.id })

        vm.onMoveCard(from = 0, to = 1)
        vm.onSaveClick()
        advanceUntilIdle()

        val (deck, cards) = deckRepo.published.single()
        assertEquals(listOf("card2", "card1"), cards.map { it.id })
        assertEquals(listOf("card2", "card1"), deck.cardIndex.map { it.id })
    }

    @Test
    fun `moving a card out of bounds is ignored`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMoveCard(from = 0, to = 5)

        assertEquals(listOf("card1"), vm.state.value.cards.map { it.id })
    }

    @Test
    fun `a card added in this session is published with its typed text`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddCard()
        val newCardId = vm.state.value.cards.last().id
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        assertTrue(cards.any { it.id == newCardId }, "the new card was not published")
    }
}
