package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FriendProfileViewModelTest {

    private val identity = FakeIdentityRepository()
    private val discovery = FakeDiscoveryRepository()
    private val decks = FakeDeckRepository()
    private val mainDispatcher = StandardTestDispatcher()

    private val stranger = "strangerpk"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(target: String = stranger) = FriendProfileViewModel(
        targetPubky = target,
        identityRepository = identity,
        discoveryRepository = discovery,
        deckRepository = decks,
    )

    private fun givenStrangerHasDecks(vararg cardCounts: Int) {
        cardCounts.forEachIndexed { index, count ->
            val deck = testDeck(id = "d$index", authorPubky = stranger, cardCount = count)
            decks.decks[deck.id] = deck
        }
    }

    @Test
    fun countsTheirDecksAndCardsForTheStatsCard() = runTest {
        givenStrangerHasDecks(18, 30)
        identity.profiles[stranger] = PubkyIdentity(stranger, "Grace Hopper", null, null)
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(2, vm.state.value.deckCount)
        assertEquals(48, vm.state.value.cardCount)
    }

    @Test
    fun countsAreZeroWhenTheyHavePublishedNothing() = runTest {
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(0, vm.state.value.deckCount)
        assertEquals(0, vm.state.value.cardCount)
        assertTrue(vm.state.value.decks.isEmpty())
    }

    @Test
    fun keepsTheProfileOnScreenWhileARefreshRuns() = runTest {
        givenStrangerHasDecks(5)
        val vm = viewModel()
        advanceUntilIdle()

        val seen = mutableListOf<FriendProfileUiState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.toList(seen) }
        vm.onRefresh()
        advanceUntilIdle()
        collector.cancel()

        // isRefreshing, never isLoading: the latter swaps the whole screen for a spinner, which
        // reads as the profile having been thrown away rather than reloaded.
        assertTrue(seen.any { it.isRefreshing })
        assertTrue(seen.none { it.isLoading })
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test
    fun revertsTheOptimisticFollowAndSurfacesTheError() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        discovery.followError = IllegalStateException("homeserver down")

        vm.onToggleFollow()
        advanceUntilIdle()

        assertFalse(vm.state.value.isFollowing)
        assertNotNull(vm.state.value.errorReason)
    }

    @Test
    fun clearsAStaleErrorWhenTheNextFollowSucceeds() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        discovery.followError = IllegalStateException("homeserver down")
        vm.onToggleFollow()
        advanceUntilIdle()
        assertNotNull(vm.state.value.errorReason)

        discovery.followError = null
        vm.onToggleFollow()
        advanceUntilIdle()

        assertTrue(vm.state.value.isFollowing)
        assertNull(vm.state.value.errorReason)
    }

    @Test
    fun marksYourOwnPubkyAsSelfSoNoFollowButtonIsOffered() = runTest {
        val vm = viewModel(target = TEST_PUBKY)

        advanceUntilIdle()

        assertTrue(vm.state.value.isSelf)
    }
}
