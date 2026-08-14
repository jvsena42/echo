package com.github.jvsena42.echo.presentation.discover

import com.github.jvsena42.echo.domain.model.ErrorReason
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.testing.FakeDiscoveryRepository
import com.github.jvsena42.echo.testing.RecordingTagRepository
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
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    private val discovery = FakeDiscoveryRepository()
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

    private fun viewModel() = DiscoverViewModel(
        discoveryRepository = discovery,
        tagRepository = tagRepo,
    )

    private fun seedFeed() {
        discovery.feed = listOf(
            testDeck(id = "spanish", authorPubky = "friend1", tags = listOf(Tag("spanish")), updatedAt = 300L),
            testDeck(id = "biology", authorPubky = "friend2", tags = listOf(Tag("biology")), updatedAt = 200L),
        )
    }

    @Test
    fun contentMergesFeedTagsWithTrendingMinusDuplicates() = runTest {
        seedFeed()
        tagRepo.trendingTags = listOf(Tag("spanish"), Tag("history"))
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<DiscoverUiState.Content>(vm.state.value)
        assertEquals(listOf("spanish", "biology"), state.decks.map { it.id })
        assertEquals(listOf(Tag("spanish"), Tag("biology")), state.tags)
        // "spanish" is already a feed tag, so only "history" remains trending.
        assertEquals(listOf(Tag("history")), state.trendingTags)
        assertNull(state.selectedTag)
    }

    @Test
    fun selectingATagFiltersTheFeedLocally() = runTest {
        seedFeed()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTagSelected(Tag("spanish"))

        val state = assertIs<DiscoverUiState.Content>(vm.state.value)
        assertEquals(Tag("spanish"), state.selectedTag)
        assertEquals(listOf("spanish"), state.decks.map { it.id })
    }

    @Test
    fun selectingTheSameTagAgainClearsTheFilter() = runTest {
        seedFeed()
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTagSelected(Tag("spanish"))

        vm.onTagSelected(Tag("spanish"))

        val state = assertIs<DiscoverUiState.Content>(vm.state.value)
        assertNull(state.selectedTag)
        assertEquals(listOf("spanish", "biology"), state.decks.map { it.id })
    }

    @Test
    fun emptyFeedShowsEmptyState() = runTest {
        discovery.feed = emptyList()
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(DiscoverUiState.Empty, vm.state.value)
    }

    @Test
    fun feedFailureShowsErrorState() = runTest {
        discovery.feedError = IllegalStateException("something unclassifiable")
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(DiscoverUiState.Error(ErrorReason.Unknown), vm.state.value)
    }

    @Test
    fun deckCardCarriesAuthorLabelAndEmojiFallback() = runTest {
        seedFeed()
        val vm = viewModel()

        advanceUntilIdle()

        val state = assertIs<DiscoverUiState.Content>(vm.state.value)
        val card = state.decks.first()
        assertEquals("@friend", card.authorLabel)
        // No cover emoji set — falls back to the title's first character.
        assertEquals("D", card.coverEmoji)
    }
}
