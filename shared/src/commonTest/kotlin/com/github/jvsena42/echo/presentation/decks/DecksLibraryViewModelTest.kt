package com.github.jvsena42.echo.presentation.decks

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
}
