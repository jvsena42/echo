package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    private val discovery = FakeDiscoveryRepository()
    private val tagRepo = RecordingTagRepository()
    private val identity = FakeIdentityRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DiscoverViewModel(
        discoveryRepository = discovery,
        tagRepository = tagRepo,
        identityRepository = identity,
    )

    private fun seedFeed() {
        discovery.feed = listOf(
            testDeck(id = "spanish", authorPubky = "friend1", tags = listOf(Tag("spanish")), updatedAt = 300L),
            testDeck(id = "biology", authorPubky = "friend2", tags = listOf(Tag("biology")), updatedAt = 200L),
        )
    }

    private fun seedGlobal() {
        discovery.globalDecks = listOf(
            testDeck(id = "chess", authorPubky = "stranger1", tags = listOf(Tag("chess"))),
            testDeck(id = "kanji", authorPubky = "stranger2", tags = listOf(Tag("kanji"))),
        )
    }

    // ── the dead end (#26) ───────────────────────────────────────────────

    @Test
    fun zeroFollowsStillBrowsesTheNetwork() = runTest {
        discovery.feed = emptyList()
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val vm = viewModel()

        advanceUntilIdle()

        // The regression this issue is about: following nobody used to collapse the whole screen
        // to "follow a friend to see their decks here".
        val state = vm.state.value
        assertEquals(listOf("chess", "kanji"), state.browse.items.map { it.id })
        assertEquals(listOf(Tag("chess")), state.topics.items)
        assertTrue(state.following.items.isEmpty())
        assertNull(state.following.error)
    }

    @Test
    fun browseAsksForEveryLoopkyDeckWhenNoTagIsSelected() = runTest {
        seedGlobal()
        viewModel()

        advanceUntilIdle()

        assertEquals(
            listOf(ReservedTags.DECK to DiscoverViewModel.BROWSE_LIMIT),
            discovery.globalRequests,
        )
    }

    @Test
    fun followingFailureLeavesTheOtherStripsIntact() = runTest {
        discovery.feedError = IllegalStateException("something unclassifiable")
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ErrorReason.Unknown, state.following.error)
        // One unreachable strip must not take the screen down with it.
        assertEquals(listOf("chess", "kanji"), state.browse.items.map { it.id })
        assertEquals(listOf(Tag("chess")), state.topics.items)
    }

    @Test
    fun retryingTheFollowedStripDoesNotReBrowse() = runTest {
        discovery.feedError = IllegalStateException("offline")
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()
        val browsesAfterLoad = discovery.globalRequests.size

        discovery.feedError = null
        seedFeed()
        vm.onRetryFollowing()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.following.error)
        assertEquals(listOf("spanish", "biology"), state.following.items.map { it.id })
        assertEquals(browsesAfterLoad, discovery.globalRequests.size)
    }

    // ── topic selection goes to the network ──────────────────────────────

    @Test
    fun selectingATopicBrowsesGloballyForThatTopic() = runTest {
        seedFeed()
        discovery.globalDecks = listOf(
            testDeck(id = "otherspanish", authorPubky = "stranger1", tags = listOf(Tag("spanish"))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        // The old behaviour filtered the cached feed, so a network-wide topic found nothing.
        assertEquals(Tag("spanish") to DiscoverViewModel.BROWSE_LIMIT, discovery.globalRequests.last())
        assertEquals(listOf("otherspanish"), vm.state.value.browse.items.map { it.id })
    }

    @Test
    fun selectingATopicAlsoNarrowsTheFollowedStripLocally() = runTest {
        seedFeed()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        assertEquals(listOf("spanish"), vm.state.value.following.items.map { it.id })
    }

    @Test
    fun selectingTheSameTopicAgainClearsTheFilterAndBrowsesEverything() = runTest {
        seedFeed()
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))
        advanceUntilIdle()

        assertNull(vm.state.value.selectedTag)
        assertEquals(ReservedTags.DECK, discovery.globalRequests.last().first)
        assertEquals(listOf("spanish", "biology"), vm.state.value.following.items.map { it.id })
    }

    // ── strips are independent ───────────────────────────────────────────

    @Test
    fun aSlowBrowseDoesNotHoldUpTopicsOrTheFollowedStrip() = runTest {
        seedFeed()
        seedGlobal()
        tagRepo.deckTags = listOf(Tag("chess"))
        val gate = CompletableDeferred<Unit>()
        discovery.globalGate = gate
        val vm = viewModel()

        advanceUntilIdle()

        val midFlight = vm.state.value
        assertTrue(midFlight.browse.isLoading, "browse should still be in flight")
        assertFalse(midFlight.topics.isLoading, "topics should not wait on browse")
        assertFalse(midFlight.following.isLoading, "following should not wait on browse")
        assertEquals(listOf("spanish", "biology"), midFlight.following.items.map { it.id })

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("chess", "kanji"), vm.state.value.browse.items.map { it.id })
    }

    @Test
    fun aStaleTopicResultIsDiscarded() = runTest {
        discovery.globalDecks = listOf(
            testDeck(id = "chessdeck", authorPubky = "s1", tags = listOf(Tag("chess"))),
            testDeck(id = "kanjideck", authorPubky = "s2", tags = listOf(Tag("kanji"))),
        )
        val vm = viewModel()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        discovery.globalGate = gate
        vm.onTagSelected(Tag("chess"))
        vm.onTagSelected(Tag("kanji"))
        gate.complete(Unit)
        advanceUntilIdle()

        // Cancelling the in-flight job can miss a suspension point, so the selection itself has to
        // be the token — otherwise chess's result lands on top of kanji's.
        assertEquals(Tag("kanji"), vm.state.value.selectedTag)
        assertEquals(listOf("kanjideck"), vm.state.value.browse.items.map { it.id })
    }

    // ── topics ───────────────────────────────────────────────────────────

    @Test
    fun topicsMergeGlobalLabelsWithFeedLabelsWithoutDuplicates() = runTest {
        seedFeed()
        tagRepo.deckTags = listOf(Tag("spanish"), Tag("history"))
        val vm = viewModel()

        advanceUntilIdle()

        // Global topics keep their ranking and lead; "spanish" is in both and appears once.
        assertEquals(
            listOf(Tag("spanish"), Tag("history"), Tag("biology")),
            vm.state.value.topics.items,
        )
    }

    @Test
    fun topicsNeverIncludeReservedLabels() = runTest {
        discovery.feed = listOf(
            testDeck(id = "d1", authorPubky = "friend1", tags = listOf(ReservedTags.DECK, Tag("spanish"))),
        )
        tagRepo.deckTags = listOf(ReservedTags.USER, Tag("history"))
        val vm = viewModel()

        advanceUntilIdle()

        // loopky-* is Loopky's index, not a topic — a chip for it would filter to nothing.
        assertEquals(listOf(Tag("history"), Tag("spanish")), vm.state.value.topics.items)
    }

    // ── tiles ────────────────────────────────────────────────────────────

    @Test
    fun deckCardCarriesAuthorAndEmojiFallback() = runTest {
        seedGlobal()
        val vm = viewModel()

        advanceUntilIdle()

        val card = vm.state.value.browse.items.first()
        // No profile published — the tile carries the bare pubky for the UI to truncate.
        assertEquals("stranger1", card.author.pubky)
        assertNull(card.author.displayName)
        // No cover emoji set — falls back to the title's first character.
        assertEquals("D", card.coverEmoji)
    }

    @Test
    fun authorProfilesResolveIntoBothStripsAfterFirstPaint() = runTest {
        seedFeed()
        discovery.globalDecks = listOf(testDeck(id = "shared", authorPubky = "friend1"))
        identity.profiles["friend1"] = PubkyIdentity("friend1", "Ada Lovelace", avatarUrl = null, bio = null)
        val vm = viewModel()

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Ada Lovelace", state.browse.items.single().author.displayName)
        assertEquals(
            "Ada Lovelace",
            state.following.items.first { it.authorPubky == "friend1" }.author.displayName,
        )
        // friend2 has no profile — it keeps the pubky rather than blanking out.
        assertNull(state.following.items.first { it.authorPubky == "friend2" }.author.displayName)
    }

    // ── people ───────────────────────────────────────────────────────────

    @Test
    fun peopleAreSeededFromWhateverBrowseFound() = runTest {
        seedGlobal()
        val vm = viewModel()

        advanceUntilIdle()

        // The directory is empty here, exactly as it is against the live indexer — the strip is
        // carried entirely by the authors of the decks browse just fetched.
        assertEquals(listOf("stranger1", "stranger2"), vm.state.value.people.items.map { it.identity.pubky })
        assertEquals(listOf(DiscoverViewModel.PEOPLE_LIMIT), discovery.suggestedRequests)
    }

    @Test
    fun peopleDropsAccountsYouAlreadyFollow() = runTest {
        seedGlobal()
        discovery.follows.add("stranger1")
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(listOf("stranger2"), vm.state.value.people.items.map { it.identity.pubky })
    }

    @Test
    fun followingSomeoneFromTheStripIsOptimistic() = runTest {
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onFollowToggle("stranger1")

        // Flipped before the write comes back, so the pill responds to the tap immediately.
        assertTrue(vm.state.value.people.items.first().isFollowing)
        advanceUntilIdle()
        assertTrue(vm.state.value.people.items.first().isFollowing)
        assertFalse(vm.state.value.people.items.first().isFollowPending)
        assertTrue("stranger1" in discovery.follows)
    }

    @Test
    fun aFailedFollowRevertsThePillAndSaysWhy() = runTest {
        seedGlobal()
        discovery.followError = IllegalStateException("offline")
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<DiscoverEffect>()
        val collector = launch { vm.effects.toList(effects) }

        vm.onFollowToggle("stranger1")
        advanceUntilIdle()

        val person = vm.state.value.people.items.first()
        assertFalse(person.isFollowing, "an optimistic follow must not survive a failed write")
        assertFalse(person.isFollowPending)
        assertTrue(effects.any { it is DiscoverEffect.ShowFollowError })
        collector.cancel()
    }

    @Test
    fun refreshClearsIsRefreshingOnceEveryStripSettles() = runTest {
        seedFeed()
        seedGlobal()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.isRefreshing)
        assertNotNull(vm.state.value.browse.items.firstOrNull())
    }
}
