package com.github.jvsena42.loopky.presentation.home

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.CardIndexEntry
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val identityRepo = FakeIdentityRepository(session = fakeSession(displayName = "Ana"))
    private val deckRepo = FakeDeckRepository()
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

    private fun viewModel() = HomeViewModel(
        identityRepository = identityRepo,
        deckRepository = deckRepo,
        srsRepository = srsRepo,
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
        assertEquals("Ana", state.identity?.displayName)
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

        assertEquals("Ana", assertIs<HomeUiState.Empty>(vm.state.value).identity?.displayName)
    }

    @Test
    fun greetingCarriesThePubkyWhenTheresNoDisplayName() = runTest {
        identityRepo.session = fakeSession(pubky = "abcdefgh", displayName = null)
        val vm = viewModel()

        advanceUntilIdle()

        // Naming the user is the platform layer's job — the state only says who they are.
        val identity = assertIs<HomeUiState.Empty>(vm.state.value).identity
        assertEquals("abcdefgh", identity?.pubky)
        assertNull(identity?.displayName)
    }

    @Test
    fun greetingPicksUpANameEditedAfterSignIn() = runTest {
        identityRepo.session = fakeSession(pubky = "abcdefgh", displayName = null)
        identityRepo.profiles["abcdefgh"] =
            PubkyIdentity("abcdefgh", "Cosmic-Crystal-Panda", avatarUrl = null, bio = null)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(
            "Cosmic-Crystal-Panda",
            assertIs<HomeUiState.Empty>(vm.state.value).identity?.displayName,
        )
    }

    @Test
    fun owningDecksWithNothingDueIsCaughtUpNotEmpty() = runTest {
        // The bug: after finishing a session, Home showed the zero-decks empty state and told a
        // user who owns decks to "create or import a deck".
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardIndex = listOf(CardIndexEntry("c1", 1L)))
        srsRepo.due = emptyList()
        srsRepo.nextDue = 9_999L
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertTrue(state.isCaughtUp)
        assertEquals(9_999L, state.nextDueAtMillis)
    }

    @Test
    fun havingCardsDueIsNotCaughtUp() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardIndex = listOf(CardIndexEntry("c1", 1L)))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertTrue(!state.isCaughtUp)
        assertEquals(null, state.nextDueAtMillis, "next-due lookup should be skipped when cards are due")
    }

    @Test
    fun genericFailureShowsErrorWithoutSigningOut() = runTest {
        deckRepo.listOwnedError = IllegalStateException("electrum hiccup")
        val vm = viewModel()
        val effects = collectEffects(vm)

        advanceUntilIdle()

        val state = assertIs<HomeUiState.Error>(vm.state.value)
        assertEquals(ErrorReason.Unknown, state.reason)
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
        assertEquals(ErrorReason.SessionExpired, state.reason)
        assertEquals(expected = 1, actual = identityRepo.signOutCount)
        assertEquals(listOf<HomeEffect>(HomeEffect.NavigateToOnboarding), effects)
    }

    @Test
    fun reloadsWhenADeckIsPublishedOrDeleted() = runTest {
        // Reproduces the reported bug: publish a deck, come back to this tab, and it still
        // says you have none because the VM only ever loaded once.
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<HomeUiState.Empty>(vm.state.value)

        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardIndex = listOf(CardIndexEntry("c1", 1L)))
        deckRepo.emitChange()
        advanceUntilIdle()

        assertIs<HomeUiState.Content>(vm.state.value)
    }

    @Test
    fun reloadsWhenCardsAreReviewed() = runTest {
        // Reproduces the reported bug: study a deck, come back to Home, and it still shows the
        // due count from before the session because the VM only reloaded on deck changes.
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            cardIndex = listOf(CardIndexEntry("c1", 1L), CardIndexEntry("c2", 1L)),
        )
        val cards = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))
        srsRepo.due = cards
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 2, actual = assertIs<HomeUiState.Content>(vm.state.value).dueToday)

        // Grading empties the queue the way a finished study session does.
        cards.forEach { srsRepo.review(it, SrsGrade.Good) }
        srsRepo.due = emptyList()
        srsRepo.nextDue = 42L
        advanceUntilIdle()

        val state = assertIs<HomeUiState.Content>(vm.state.value)
        assertEquals(expected = 0, actual = state.dueToday)
        assertEquals(expected = 0, actual = state.decks.first { it.id == "deck1" }.dueCount)
        assertTrue(state.isCaughtUp)
        assertEquals(expected = 42L, actual = state.nextDueAtMillis)
    }

    @Test
    fun aBackgroundReloadDoesNotFlashTheLoadingState() = runTest {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardIndex = listOf(CardIndexEntry("c1", 1L)))
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<HomeUiState.Content>(vm.state.value)

        val seen = mutableListOf<HomeUiState>()
        val job = launch { vm.state.collect { seen.add(it) } }
        deckRepo.emitChange()
        advanceUntilIdle()
        job.cancel()

        assertTrue(seen.none { it is HomeUiState.Loading }, "background refresh flashed the loader")
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
