package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCard
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
    private val tagRepo = RecordingTagRepository()

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
            tagRepository = tagRepo,
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

    // ── follow deck (#33) ────────────────────────────────────────────────

    @Test
    fun `following a foreign deck flips the pill and writes the subscription`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()
            assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)

            vm.onToggleFollow()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertTrue(state.isFollowing)
            assertEquals(false, state.isFollowPending)
            assertTrue("deck1" in deckRepo.followedDecks)
        }

    @Test
    fun `a failed follow reverts the pill and reports without losing the deck`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            deckRepo.followError = IllegalStateException("homeserver unreachable")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onToggleFollow()
            advanceUntilIdle()

            // The deck is fine; only the write failed, so the page must survive it.
            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(false, state.isFollowing)
            assertEquals(false, state.isFollowPending)
            assertNotNull(state.errorReason)
        }

    @Test
    fun `unfollowing an already-followed deck drops the subscription`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk")
        deckRepo.decks["deck1"] = deck
        deckRepo.followedDecks["deck1"] = deck
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()
        assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)

        vm.onToggleFollow()
        advanceUntilIdle()

        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)
        assertTrue("deck1" !in deckRepo.followedDecks)
    }

    @Test
    fun `you cannot follow your own deck`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertTrue(deckRepo.followedDecks.isEmpty())
        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)
    }

    @Test
    fun `opening a followed deck marks it seen so the library stops flagging it`() =
        runTest(mainDispatcher) {
            val deck = testDeck(id = "deck1", authorPubky = "friendpk")
            deckRepo.decks["deck1"] = deck
            deckRepo.followedDecks["deck1"] = deck
            deckRepo.updatedDecks.add("deck1")

            viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            assertEquals(listOf("deck1"), deckRepo.seen)
        }

    @Test
    fun `a deck you merely browse is not marked seen`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")

        viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        assertTrue(deckRepo.seen.isEmpty())
    }

    // ── clone deck (#33) ─────────────────────────────────────────────────

    @Test
    fun `cloning confirms first then navigates to the copy`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", cardCount = 40)
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        vm.onCloneClick()
        advanceUntilIdle()
        // A clone is N+1 writes, so it asks before spending them.
        assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
        assertTrue(deckRepo.cloned.isEmpty())

        vm.onConfirmClone()
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("deck1"), deckRepo.cloned.map { it.id })
        // The copy is what the user now owns; leaving them on the source looks like nothing happened.
        assertEquals<List<DeckDetailEffect>>(
            listOf(DeckDetailEffect.Cloned("clone-of-deck1")),
            effects,
        )
    }

    @Test
    fun `dismissing the clone dialog spends nothing`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onCloneClick()
        vm.onDismissClone()
        advanceUntilIdle()

        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).showCloneConfirm)
        assertTrue(deckRepo.cloned.isEmpty())
    }

    @Test
    fun `a failed clone reports and clears the spinner`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        deckRepo.cloneError = IllegalStateException("homeserver unreachable")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onCloneClick()
        vm.onConfirmClone()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(false, state.isCloning)
        assertNotNull(state.errorReason)

        vm.onDismissError()
        assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).errorReason)
    }

    @Test
    fun `a clone credits the deck it came from`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY).copy(
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = "pubky://friendpk/pub/loopky/decks/orig/manifest.json",
            ),
        )
        identityRepo.profiles["friendpk"] =
            PubkyIdentity("friendpk", displayName = "Mei", avatarUrl = null, bio = null)

        val vm = viewModel()
        advanceUntilIdle()

        // Attribution has to reach the screen, not just sit in the manifest's `source` block.
        assertEquals("Mei", assertIs<DeckDetailUiState.Content>(vm.state.value).clonedFrom?.displayName)
    }

    @Test
    fun `follower and clone counts come from the reserved labels`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk")
        deckRepo.decks["deck1"] = deck
        tagRepo.counts = mapOf(
            deck.pubkyUri to mapOf(ReservedTags.FOLLOWED to 12, ReservedTags.CLONED to 3),
        )

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(expected = 12, actual = state.followerCount)
        assertEquals(expected = 3, actual = state.clonedCount)
    }

    @Test
    fun `an unreachable indexer leaves the counts at zero rather than failing the screen`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            tagRepo.failWith = IllegalStateException("indexer unreachable")

            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(expected = 0, actual = state.followerCount)
            assertEquals(expected = 0, actual = state.clonedCount)
        }
}
