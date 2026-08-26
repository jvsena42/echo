package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCoverImage
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

    private fun viewModel(
        target: String = stranger,
        environment: PubkyEnvironment = PubkyEnvironment.Production,
    ) = FriendProfileViewModel(
        targetPubky = target,
        identityRepository = identity,
        discoveryRepository = discovery,
        deckRepository = decks,
        pubkyEnvironment = environment,
    )

    private fun givenStrangerHasDecks(vararg cardCounts: Int) {
        cardCounts.forEachIndexed { index, count ->
            val deck = testDeck(id = "d$index", authorPubky = stranger, cardCount = count)
            decks.decks[deck.id] = deck
        }
    }

    @Test
    fun theirProfileLinkGoesToTheEnvironmentTheBuildSignedInAgainst() = runTest {
        // Same rule as the owner's own profile: a staging account does not exist on production,
        // so the host has to follow the build (#42). It links to the person on screen, never to
        // whoever happens to be signed in on the web.
        val expected = mapOf(
            PubkyEnvironment.Staging to "https://staging.pubky.app/profile/$stranger",
            PubkyEnvironment.Production to "https://pubky.app/profile/$stranger",
        )
        expected.forEach { (environment, url) ->
            val vm = viewModel(environment = environment)
            advanceUntilIdle()

            val effects = mutableListOf<FriendProfileEffect>()
            val job = launch { vm.effects.toList(effects) }
            vm.onOpenOnPubkyApp()
            advanceUntilIdle()
            job.cancel()

            assertEquals(url, effects.filterIsInstance<FriendProfileEffect.OpenUrl>().single().url)
        }
    }

    @Test
    fun countsTheirFollowGraphSoTheirPeopleAreReachable() = runTest {
        val grace = PubkyIdentity("gracepk", "Grace Hopper", null, null)
        discovery.followingByUser = mapOf(stranger to listOf(grace))
        discovery.followersByUser = mapOf(stranger to listOf(grace, PubkyIdentity("adapk", null, null, null)))
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(1, vm.state.value.followingCount)
        assertEquals(2, vm.state.value.followerCount)
    }

    @Test
    fun aFollowGraphThatCannotBeReadStaysNullRatherThanReadingAsZero() = runTest {
        discovery.followListError = IllegalStateException("indexer down")
        val vm = viewModel()

        advanceUntilIdle()

        assertNull(vm.state.value.followingCount)
        assertNull(vm.state.value.followerCount)
    }

    @Test
    fun sharingHandsOutAnAddressRatherThanABareKey() = runTest {
        identity.profiles[stranger] = PubkyIdentity(stranger, "Grace Hopper", null, null)
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<FriendProfileEffect>()
        val collector = launch { vm.effects.toList(effects) }
        vm.onShareClick()
        advanceUntilIdle()
        collector.cancel()

        val shared = effects.filterIsInstance<FriendProfileEffect.ShareProfile>().single()
        assertEquals("pubky://$stranger", shared.uri)
        assertEquals("Grace Hopper", shared.identity.displayName)
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

    @Test
    fun deckCardsCarryTheAuthorAndCoverSoTheGridCanRenderIt() = runTest {
        val cover = testCoverImage()
        decks.decks["d0"] = testDeck(id = "d0", authorPubky = stranger, coverImageRef = cover)
        val vm = viewModel()

        advanceUntilIdle()

        // The blob lives on *their* homeserver, so the tile needs the author as well as the ref.
        val card = vm.state.value.decks.single()
        assertEquals(cover, card.coverImage)
        assertEquals(stranger, card.authorPubky)
    }
    // ── Browsing without an account (#150) ───────────────────────────────────

    /**
     * A public profile and the decks on it are public records: the screen reads in full with no
     * session, and only Follow is out of reach.
     */
    @Test
    fun `renders with nobody signed in and gates only follow`() = runTest(mainDispatcher) {
        identity.session = null
        decks.decks["d1"] = testDeck(id = "d1", authorPubky = stranger)

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.isSignedIn)
        assertFalse(vm.state.value.isSelf)
        assertEquals(1, vm.state.value.decks.size)

        vm.onToggleFollow()
        advanceUntilIdle()

        // The optimistic flip must not have run: nothing was written, so nothing changed.
        assertEquals(SignInReason.FollowPerson, vm.state.value.signInPrompt)
        assertFalse(vm.state.value.isFollowing)
        assertTrue(discovery.follows.isEmpty())

        vm.onDismissSignInPrompt()
        assertNull(vm.state.value.signInPrompt)
    }
}
