package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val identity = FakeIdentityRepository()
    private val decks = FakeDeckRepository()
    private val srs = FakeSrsRepository()
    private val discovery = FakeDiscoveryRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProfileViewModel(
        identityRepository = identity,
        deckRepository = decks,
        srsRepository = srs,
        discoveryRepository = discovery,
    )

    private val friend = PubkyIdentity("friendpk", "Grace Hopper", null, null)

    @Test
    fun sharingHandsOutAnAddressRatherThanABareKey() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<ProfileEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.onShareClick()
        advanceUntilIdle()
        job.cancel()

        val shared = effects.filterIsInstance<ProfileEffect.ShareProfile>().single()
        assertEquals("pubky://$TEST_PUBKY", shared.uri)
        // Named, so a recipient knows whose profile they are about to open.
        assertEquals("Ada", shared.identity.displayName)
    }

    @Test
    fun theWholeIdentityReachesTheStateSoTheAvatarCanBeDrawn() = runTest {
        // The screen used to keep only an initial, which is why the signed-in user was the one
        // person in the app whose picture never rendered.
        val picture = "pubky://$TEST_PUBKY/pub/pubky.app/files/0035JHD6154X0"
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", picture, "Bio")
        val vm = viewModel()

        advanceUntilIdle()

        val shown = vm.state.value.identity
        assertEquals("Ada", shown?.displayName)
        assertEquals(picture, shown?.avatarUrl)
        assertEquals("Bio", shown?.bio)
    }

    @Test
    fun countsAreOfLoopkyAccountsOnly() = runTest {
        discovery.followingByUser = mapOf(TEST_PUBKY to listOf(friend))
        discovery.followersByUser = mapOf(TEST_PUBKY to emptyList())
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(1, vm.state.value.followingCount)
        assertEquals(0, vm.state.value.followerCount)
    }

    @Test
    fun theProfileRendersBeforeTheFollowCountsResolve() = runTest {
        // Each candidate costs an indexer round-trip, so the counts must not gate the screen.
        discovery.followListGate = CompletableDeferred()
        decks.decks["d1"] = testDeck(id = "d1", cardCount = 12)
        val vm = viewModel()

        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertEquals(1, vm.state.value.deckCount)
        assertNull(vm.state.value.followingCount)
    }

    @Test
    fun aFailedCountLeavesTheStatBlankRatherThanBreakingTheScreen() = runTest {
        discovery.followListError = IllegalStateException("indexer down")
        val vm = viewModel()

        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.followingCount)
        assertNull(vm.state.value.followerCount)
    }
}
