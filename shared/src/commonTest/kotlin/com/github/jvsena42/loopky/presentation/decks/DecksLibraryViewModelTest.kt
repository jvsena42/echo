package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCoverImage
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
import kotlin.test.assertTrue

/**
 * The library used to label every tile "@you" — a third spelling of the same user, built as a
 * string inside the ViewModel. Tiles now carry the identity and let the platform name it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecksLibraryViewModelTest {

    private val identityRepo = FakeIdentityRepository()
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

    private fun viewModel() = DecksLibraryViewModel(
        deckRepository = deckRepo,
        identityRepository = identityRepo,
    )

    @Test
    fun `tiles carry the signed-in identity rather than a formatted label`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY)

        val vm = viewModel()
        advanceUntilIdle()

        val tile = assertIs<DecksLibraryUiState.Content>(vm.state.value).decks.single()
        assertEquals("Tester", tile.author.displayName)
        assertEquals(TEST_PUBKY, tile.author.pubky)
        assertTrue(tile.isOwned)
    }

    @Test
    fun `a deck by someone else is not marked as yours`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = "friendpk")

        val vm = viewModel()
        advanceUntilIdle()

        val tile = assertIs<DecksLibraryUiState.Content>(vm.state.value).decks.single()
        assertEquals("friendpk", tile.author.pubky)
        assertEquals(false, tile.isOwned)
    }

    // ── followed decks (#33) ─────────────────────────────────────────────

    @Test
    fun `a followed deck shows in the library alongside your own`() = runTest(mainDispatcher) {
        deckRepo.decks["mine"] = testDeck(id = "mine", authorPubky = TEST_PUBKY)
        deckRepo.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk")

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DecksLibraryUiState.Content>(vm.state.value)
        assertEquals(expected = 2, actual = state.deckCount)
        val byId = state.decks.associateBy { it.id }
        assertEquals(DeckRelation.Owned, byId.getValue("mine").relation)
        // Followed, not owned: read-only, and it receives the author's updates.
        assertEquals(DeckRelation.Followed, byId.getValue("theirs").relation)
        assertEquals(false, byId.getValue("theirs").isOwned)
    }

    @Test
    fun `a clone is yours but labelled as a copy`() = runTest(mainDispatcher) {
        deckRepo.decks["fork"] = testDeck(id = "fork", authorPubky = TEST_PUBKY).copy(
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = "pubky://friendpk/pub/loopky/decks/orig/manifest.json",
            ),
        )

        val vm = viewModel()
        advanceUntilIdle()

        val tile = assertIs<DecksLibraryUiState.Content>(vm.state.value).decks.single()
        assertEquals(DeckRelation.Cloned, tile.relation)
        // A clone is editable — the whole point of cloning rather than following.
        assertTrue(tile.isOwned)
    }

    @Test
    fun `a followed deck the author changed is flagged as updated`() = runTest(mainDispatcher) {
        deckRepo.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk")
        deckRepo.updatedDecks.add("theirs")

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(assertIs<DecksLibraryUiState.Content>(vm.state.value).decks.single().hasUpdate)
    }

    @Test
    fun `unreachable followed decks do not fail a library that has your own`() = runTest(mainDispatcher) {
        deckRepo.decks["mine"] = testDeck(id = "mine", authorPubky = TEST_PUBKY)
        // Someone else's homeserver is down; yours is not.
        deckRepo.listFollowedError = IllegalStateException("homeserver unreachable")

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DecksLibraryUiState.Content>(vm.state.value)
        assertEquals(listOf("mine"), state.decks.map { it.id })
    }

    @Test
    fun `clicking a followed deck carries its author so a cold cache can resolve it`() =
        runTest(mainDispatcher) {
            deckRepo.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk")
            val vm = viewModel()
            advanceUntilIdle()

            val effects = mutableListOf<DecksLibraryEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }
            advanceUntilIdle()

            vm.onDeckClick("theirs")
            advanceUntilIdle()
            job.cancel()

            // Without the author, deck detail cannot fetch a manifest that lives elsewhere.
            assertEquals<List<DecksLibraryEffect>>(
                listOf(DecksLibraryEffect.NavigateDeckDetail("theirs", "friendpk")),
                effects,
            )
        }

    @Test
    fun `tiles carry the deck's cover image so the grid can render it`() = runTest(mainDispatcher) {
        val cover = testCoverImage()
        deckRepo.decks["deck1"] = testDeck(id = "deck1", coverImageRef = cover)

        val vm = viewModel()
        advanceUntilIdle()

        // Dropping the ref here is what made a deck with a cover look coverless outside its
        // detail screen — the tile only ever had the emoji to fall back on.
        val tile = assertIs<DecksLibraryUiState.Content>(vm.state.value).decks.single()
        assertEquals(cover, tile.coverImage)
    }
}
