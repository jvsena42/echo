package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private val discoveryRepo = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()
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
            cardCount = 1,
            chunks = listOf(ChunkMeta(n = 0, count = 1, updatedAt = 1_000L)),
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

    private fun viewModel(deckId: String? = "deck1") = DeckEditorViewModel(
        deckId = deckId,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = identityRepo,
        mediaRepository = FakeMediaRepository(),
        discoveryRepository = discoveryRepo,
        appPreferences = preferences,
    )

    @Test
    fun `save preserves card image and audio the editor cannot edit`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        // Adding a card changes the card set, so this takes the full-publish path.
        vm.onAddCard()
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        val saved = cards.first { it.id == "card1" }
        assertEquals(frontImage, saved.front.imageRef, "front image was dropped on save")
        assertEquals(backAudio, saved.back.audioRef, "back audio was dropped on save")
    }

    @Test
    fun `save rebuilds cards from the repository rather than the list this editor loaded`() =
        runTest(mainDispatcher) {
            seedDeckWithMedia()
            val vm = viewModel()
            advanceUntilIdle()

            // What the card editor does on its own screen while this one stays on the back stack.
            val addedImage = frontImage.copy(path = "media/back.jpg", sha256 = "backimagesha")
            cardRepo.seed(
                Card(
                    id = "card1",
                    deckId = "deck1",
                    updatedAt = 2_000L,
                    front = CardSide(text = "hola", imageRef = frontImage),
                    back = CardSide(text = "hello", imageRef = addedImage, audioRef = backAudio),
                ),
            )

            // Adding a card changes the card set, so this takes the full-publish path — the one
            // that rewrites every card record and would destroy the edit above (#80).
            vm.onAddCard()
            vm.onSaveClick()
            advanceUntilIdle()

            val (_, cards) = deckRepo.published.single()
            assertEquals(
                addedImage,
                cards.first { it.id == "card1" }.back.imageRef,
                "an image added in the card editor was overwritten by the deck editor's stale copy",
            )
        }

    @Test
    fun `onResume picks up a card the card editor changed`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("hello", vm.state.value.cards.single().backText)

        cardRepo.seed(
            Card(
                id = "card1",
                deckId = "deck1",
                updatedAt = 2_000L,
                front = CardSide(text = "hola", imageRef = frontImage),
                back = CardSide(text = "hi there", audioRef = backAudio),
            ),
        )
        vm.onResume()
        advanceUntilIdle()

        assertEquals(
            "hi there",
            vm.state.value.cards.single().backText,
            "the list still shows the card as it was before the card editor wrote it",
        )
    }

    @Test
    fun `save preserves the deck cover and card options`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        val deck = deckRepo.decks.getValue("deck1")
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

        assertEquals("Renamed", deckRepo.decks.getValue("deck1").title)
    }

    @Test
    fun `save leaves the sync timestamp alone for untouched cards`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddCard()
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        assertEquals(
            1_000L,
            cards.first { it.id == "card1" }.updatedAt,
            "an unchanged card should keep its updated_at so sync does not re-download it",
        )
    }

    @Test
    fun `moving a card reorders it and the new order is what gets published`() = runTest {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            cardCount = 2,
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
        // Reorder now persists through the cards' `ord`, assigned by publish in list order,
        // rather than through the manifest's card index.
        assertEquals(listOf("card2", "card1"), cards.map { it.id })
        assertEquals(expected = 2, actual = deck.cardCount)
    }

    @Test
    fun `renaming a deck writes only the manifest`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        // Republishing would rewrite every chunk to change one field.
        assertTrue(deckRepo.published.isEmpty(), "a rename republished the whole deck")
        assertEquals("Renamed", deckRepo.decks.getValue("deck1").title)
    }

    @Test
    fun `reserved labels cannot be typed into the tag input`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddTag("loopky-deck")
        vm.onAddTag("spanish")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    @Test
    fun `a label already on the deck is not added twice`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddTag("spanish")
        vm.onAddTag(" SPANish ")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    @Test
    fun `adding a card still republishes the deck`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddCard()
        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(expected = 1, actual = deckRepo.published.size)
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

    // ── share on Pubky (#39) ─────────────────────────────────────────────

    @Test
    fun `creating a deck in the editor offers to announce it`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")

        vm.onSaveClick()
        advanceUntilIdle()

        val prompt = assertNotNull(vm.state.value.sharePrompt)
        assertEquals(DeckAnnouncement.Kind.Created, prompt.kind)
        assertTrue(prompt.preview.contains("Kanji N5"), prompt.preview)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun `editing an existing deck never offers and never posts`() = runTest(mainDispatcher) {
        // save() republishes the whole manifest on every edit, so announcing from the success path
        // unconditionally would post again every time someone fixed a typo.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Kanji N5")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5 revised")

        vm.onSaveClick()
        advanceUntilIdle()

        assertNull(vm.state.value.sharePrompt)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun `accepting the offer posts and then leaves for the new deck`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        // The editor stays put while the offer is up; navigating would take it off screen.
        assertTrue(effects.isEmpty())

        vm.onShareConfirm()
        advanceUntilIdle()
        job.cancel()

        assertEquals(expected = 1, actual = discoveryRepo.announcements.size)
        assertEquals(expected = 2, actual = effects.size)
        assertEquals(DeckEditorEffect.Shared, effects.first())
        assertIs<DeckEditorEffect.SaveSuccess>(effects.last())
    }

    @Test
    fun `declining leaves for the new deck without posting`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        vm.onShareDismiss()
        advanceUntilIdle()
        job.cancel()

        assertTrue(discoveryRepo.announcements.isEmpty())
        assertIs<DeckEditorEffect.SaveSuccess>(effects.single())
    }

    @Test
    fun `with sharing off the save leaves immediately`() = runTest(mainDispatcher) {
        preferences.setShareOnPubky(false)
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        job.cancel()

        assertNull(vm.state.value.sharePrompt)
        assertIs<DeckEditorEffect.SaveSuccess>(effects.single())
    }
}
