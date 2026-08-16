package com.github.jvsena42.loopky.presentation.importflow

import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeImportRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class PublishDeckViewModelTest {

    private val importRepo = FakeImportRepository(
        draft = testDraft("hola" to "hello", "gracias" to "thank you"),
    )
    private val deckRepo = FakeDeckRepository()
    private val identityRepo = FakeIdentityRepository()
    private val mediaRepo = FakeMediaRepository()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PublishDeckViewModel(
        importRepository = importRepo,
        deckRepository = deckRepo,
        identityRepository = identityRepo,
        mediaRepository = mediaRepo,
    )

    // ── validation ───────────────────────────────────────────────────────

    @Test
    fun blankTitleBlocksPublish() = runTest {
        val vm = viewModel()
        assertEquals(expected = 2, actual = vm.state.value.cardCount)

        vm.onPublishClick()
        runCurrent()

        // Reported on the title field now, not as a generic bottom-of-form error.
        assertEquals(FormError.TitleRequired, vm.state.value.titleError)
        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun overlongTitleBlocksPublish() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("x".repeat(121))
        assertNotNull(vm.state.value.titleError)
        assertTrue(!vm.state.value.canPublish)

        vm.onPublishClick()
        runCurrent()

        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun tappingPublishWithNoTitleReportsWhyRatherThanDoingNothing() = runTest {
        // The button is enabled so validation can speak. It used to be disabled, which made
        // `validateForPublish` dead code — the tap was swallowed with no feedback at all.
        val vm = viewModel()
        assertNull(vm.state.value.titleError)
        assertTrue(!vm.state.value.canPublish)

        vm.onPublishClick()
        runCurrent()

        assertEquals(FormError.TitleRequired, vm.state.value.titleError)
    }

    @Test
    fun overlongDescriptionBlocksPublish() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onDescriptionChanged("y".repeat(501))
        assertNotNull(vm.state.value.descriptionError)

        vm.onPublishClick()
        runCurrent()

        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun missingDraftSurfacesAnError() = runTest {
        importRepo.draft = null
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals("No import data. Please go back and paste again.", vm.state.value.error)
        assertTrue(deckRepo.published.isEmpty())
    }

    // ── publish ──────────────────────────────────────────────────────────

    @Test
    fun successfulPublishEntersTheUndoWindow() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish Basics")
        vm.onAddTag(" SPANish ")

        vm.onPublishClick()
        runCurrent()

        val state = vm.state.value
        assertNotNull(state.publishedDeckId)
        assertEquals(expected = 10, actual = state.undoSecondsRemaining)
        assertTrue(!state.isPublishing)
        assertNull(state.error)

        val (deck, cards) = deckRepo.published.single()
        assertEquals("Spanish Basics", deck.title)
        assertEquals(TEST_PUBKY, deck.authorPubky)
        assertEquals(listOf("spanish"), deck.tags.map { it.value })
        assertEquals(listOf("hola", "gracias"), cards.map { it.front.text })
        assertEquals(listOf("hello", "thank you"), cards.map { it.back.text })
        assertEquals(cards.size, deck.cardCount)
    }

    @Test
    fun publishFailureSurfacesTheError() = runTest {
        deckRepo.publishError = IllegalStateException("homeserver down")
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals("homeserver down", vm.state.value.error)
        assertNull(vm.state.value.publishedDeckId)
        assertTrue(!vm.state.value.isPublishing)
    }

    @Test
    fun undoCountdownTicksDown() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()

        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(expected = 7, actual = vm.state.value.undoSecondsRemaining)
    }

    @Test
    fun undoPublishDeletesTheDeckAndRestoresTheReviewState() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        vm.onUndoPublish()
        runCurrent()

        assertEquals(listOf(deckId), deckRepo.deleted)
        val state = vm.state.value
        assertNull(state.publishedDeckId)
        assertEquals(expected = 0, actual = state.undoSecondsRemaining)
        assertNull(state.error)
        // The draft is preserved so the user can adjust and re-publish.
        assertEquals(expected = 0, actual = importRepo.clearCount)
    }

    @Test
    fun donePublishEmitsPublishedAndClearsTheDraft() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        vm.onDonePublish()
        runCurrent()

        assertEquals(listOf<PublishDeckEffect>(PublishDeckEffect.Published(deckId)), effects)
        assertEquals(expected = 1, actual = importRepo.clearCount)
        assertTrue(deckRepo.deleted.isEmpty())
    }

    @Test
    fun countdownExpiryAutoEmitsPublished() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        advanceTimeBy(11_000L)
        runCurrent()

        val effect = assertIs<PublishDeckEffect.Published>(effects.single())
        assertEquals(deckId, effect.deckId)
        assertEquals(expected = 1, actual = importRepo.clearCount)
        assertEquals(expected = 0, actual = vm.state.value.undoSecondsRemaining)
    }
}
