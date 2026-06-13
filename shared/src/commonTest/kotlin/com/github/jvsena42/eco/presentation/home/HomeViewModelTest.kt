package com.github.jvsena42.eco.presentation.home

import com.github.jvsena42.eco.data.pubky.PubkyError
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.testing.FakeDeckRepository
import com.github.jvsena42.eco.testing.FakeIdentityRepository
import com.github.jvsena42.eco.testing.FakeSrsRepository
import com.github.jvsena42.eco.testing.fakeSession
import com.github.jvsena42.eco.testing.testCard
import com.github.jvsena42.eco.testing.testDeck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = fakeSession(displayName = "Ana"))
    private val deckRepo = FakeDeckRepository()
    private val srsRepo = FakeSrsRepository()

    private fun TestScope.viewModel() = HomeViewModel(
        identityRepository = identityRepo,
        deckRepository = deckRepo,
        srsRepository = srsRepo,
        mainScope = this,
    )

    /** Subscribes eagerly so effects emitted by the init-launched load are not dropped. */
    private fun TestScope.collectEffects(vm: HomeViewModel): List<HomeEffect> {
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.collect { effects.add(it) }
        }
        return effects
    }

    @Test
    fun contentLoadAggregatesDecksAndDueCounts() = runTest {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            title = "Spanish",
            cardIndex = listOf(CardIndexEntry("c1", 1L), CardIndexEntry("c2", 1L)),
        )
        deckRepo.decks["deck2"] = testDeck(id = "deck2", title = "Biology")
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals("Ana", state.greetingName)
        assertEquals(expected = 2, actual = state.dueToday)
        assertEquals(expected = 0, actual = state.doneToday)
        assertEquals(expected = 2, actual = state.decks.size)
        val spanish = state.decks.first { it.id == "deck1" }
        assertEquals(expected = 2, actual = spanish.dueCount)
        assertEquals(expected = 2, actual = spanish.cardCount)
        assertEquals('S', spanish.coverInitial)
        assertEquals(expected = 0, actual = state.decks.first { it.id == "deck2" }.dueCount)
    }

    @Test
    fun emptyDeckListShowsEmptyState() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(HomeUiState.Empty("Ana"), vm.state.value)
    }

    @Test
    fun greetingFallsBackToPubkyPrefixWithoutDisplayName() = runTest {
        identityRepo.session = fakeSession(pubky = "abcdefgh", displayName = null)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(HomeUiState.Empty("pk:abcdef"), vm.state.value)
    }

    @Test
    fun genericFailureShowsErrorWithoutSigningOut() = runTest {
        deckRepo.listOwnedError = IllegalStateException("electrum hiccup")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals("electrum hiccup", state.message)
        assertEquals(expected = 0, actual = identityRepo.signOutCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun sessionExpiredFailureSignsOutAndNavigatesToOnboarding() = runTest {
        deckRepo.listOwnedError = PubkyError("session expired")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals("Your session expired. Please sign in again.", state.message)
        assertEquals(expected = 1, actual = identityRepo.signOutCount)
        assertEquals(listOf<HomeEffect>(HomeEffect.NavigateToOnboarding), effects)
    }

    @Test
    fun nonPubkySessionMessageDoesNotTriggerReauth() = runTest {
        // Same message shape, but not a PubkyError — must not be treated as session expiry.
        deckRepo.listOwnedError = IllegalStateException("session expired")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals(expected = 0, actual = identityRepo.signOutCount)
        assertTrue(effects.isEmpty())
    }
}
