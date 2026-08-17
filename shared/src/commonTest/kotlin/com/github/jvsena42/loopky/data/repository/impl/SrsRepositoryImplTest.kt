package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.SrsChunkDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import com.github.jvsena42.loopky.testing.testDeckWithCards
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SrsRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
    )
    private val repo = SrsRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
    )

    private val dayMs = 86_400_000L

    private suspend fun publishDeck(deckId: String, vararg cardIds: String) {
        val cards = cardIds.map { testCard(it, deckId = deckId) }
        deckRepo.publish(testDeck(id = deckId), cards).getOrThrow()
    }

    // ── review / upsert ──────────────────────────────────────────────────

    @Test
    fun reviewGradesANewCardViaTheSchedulerAndPersistsIt() = runTest {
        publishDeck("deck1", "c1")
        val before = epochMillis()

        val state = repo.review(testCard("c1"), SrsGrade.Good).getOrThrow()

        assertEquals(expected = 3, actual = state.intervalDays)
        assertEquals(expected = 1, actual = state.repetitions)
        assertEquals(SrsGrade.Good, state.lastGrade)
        assertTrue(state.dueAt >= before + 3 * dayMs)

        // Buffered, not written yet — a review is one of many in a session.
        assertTrue(pubky.puts.none { it.first.contains("/srs/") }, "review wrote through immediately")
        assertEquals(state, repo.stateFor("c1"))

        repo.flush().getOrThrow()

        val url = "pubky://$TEST_PUBKY/pub/loopky/srs/$TEST_PUBKY/deck1/0.json"
        val chunk = loopkyJson.decodeFromString<SrsChunkDto>(pubky.store.getValue(url))
        val dto = chunk.states.single()
        assertEquals("c1", dto.card_id)
        assertEquals(expected = 3, actual = dto.interval_days)
        assertEquals(SrsGrade.Good.ordinal, dto.last_grade)
    }

    @Test
    fun secondReviewGrowsTheIntervalFromTheCachedState() = runTest {
        publishDeck("deck1", "c1")
        repo.review(testCard("c1"), SrsGrade.Good).getOrThrow()

        val state = repo.review(testCard("c1"), SrsGrade.Easy).getOrThrow()

        // interval 3, ease 2.5, easy bonus 1.3 → round(9.75) = 10 days.
        assertEquals(expected = 10, actual = state.intervalDays)
        assertEquals(expected = 2, actual = state.repetitions)
        assertEquals(SrsGrade.Easy, state.lastGrade)
    }

    @Test
    fun reviewSignalsAChangeSoDueCountsCanReload() = runTest {
        publishDeck("deck1", "c1")
        val changes = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.changes.collect { changes.add(it) }
        }

        repo.review(testCard("c1"), SrsGrade.Good).getOrThrow()
        advanceUntilIdle()
        collector.cancel()

        // The deck id lets deck-scoped screens ignore reviews from other decks.
        assertEquals(listOf("deck1"), changes)
    }

    @Test
    fun upsertRoundTripsThroughTheHomeserverRecord() = runTest {
        val state = SrsState(
            cardId = "c1",
            dueAt = 123_456L,
            intervalDays = 5,
            easeFactor = 2.2,
            repetitions = 3,
            lastGrade = SrsGrade.Hard,
        )

        repo.upsert("deck1", state).getOrThrow()
        repo.flush().getOrThrow()

        // The exact chunk is an implementation detail — with no deck loaded the card has no known
        // position, so it lands in a fallback bucket. What matters is that it is under this
        // session's author-scoped root, and that a cold read discovers it by listing.
        val root = "pubky://$TEST_PUBKY/pub/loopky/srs/$TEST_PUBKY/deck1/"
        assertTrue(
            pubky.store.keys.any { it.startsWith(root) },
            "state did not reach the homeserver: ${pubky.store.keys}",
        )
        assertEquals(state, repo.stateFor("c1"))
    }

    @Test
    fun aWholeSessionOfReviewsCostsOneChunkWrite() = runTest {
        publishDeck("deck1", *(1..30).map { "c$it" }.toTypedArray())
        repo.dueForDeck("deck1")
        pubky.puts.clear()

        (1..30).forEach { repo.review(testCard("c$it", deckId = "deck1"), SrsGrade.Good).getOrThrow() }
        repo.flush().getOrThrow()

        // One record per review would be 30 writes. The periodic flush may add one more.
        val srsWrites = pubky.puts.count { it.first.contains("/srs/") }
        assertTrue(srsWrites <= 2, "30 reviews cost $srsWrites writes")
    }

    @Test
    fun srsPathIsScopedToTheDeckAuthorNotJustTheDeckId() = runTest {
        // Two authors can publish decks that happen to share an id; your review state for each
        // must not collide in your own srs/ tree (#33 blocker 4).
        publishDeck("shared-id", "c1")
        repo.review(testCard("c1", deckId = "shared-id"), SrsGrade.Good).getOrThrow()
        repo.flush().getOrThrow()

        assertTrue(
            pubky.store.keys.any { it == "pubky://$TEST_PUBKY/pub/loopky/srs/$TEST_PUBKY/shared-id/0.json" },
            "expected an author-scoped srs path, got ${pubky.store.keys.filter { "/srs/" in it }}",
        )
    }

    @Test
    fun coldRepoReadsPersistedStateBackFromTheRecord() = runTest {
        val state = SrsState(
            cardId = "c1",
            dueAt = 123_456L,
            intervalDays = 5,
            easeFactor = 2.2,
            repetitions = 3,
            lastGrade = SrsGrade.Hard,
        )
        repo.upsert("deck1", state).getOrThrow()
        repo.flush().getOrThrow()

        // A fresh repo has a cold cache; building the due queue must load the persisted state
        // from the homeserver (repetitions grows from 3, not from a zeroed new-card baseline).
        val coldRepo = SrsRepositoryImpl(pubky, session, revalidator, deckRepo, cardRepo)
        coldRepo.dueForDeck("deck1")
        val next = coldRepo.review(testCard("c1"), SrsGrade.Good).getOrThrow()
        assertEquals(4, next.repetitions)
    }

    // ── due queue ────────────────────────────────────────────────────────

    @Test
    fun dueForDeckTreatsNewCardsAsDue() = runTest {
        publishDeck("deck1", "c1", "c2")

        assertEquals(listOf("c1", "c2"), repo.dueForDeck("deck1").map { it.id })
    }

    @Test
    fun dueForDeckExcludesCardsScheduledInTheFuture() = runTest {
        publishDeck("deck1", "c1", "c2")
        val future = SrsState(
            cardId = "c2",
            dueAt = epochMillis() + dayMs,
            intervalDays = 1,
            easeFactor = 2.5,
            repetitions = 1,
            lastGrade = SrsGrade.Good,
        )
        repo.upsert("deck1", future).getOrThrow()

        assertEquals(listOf("c1"), repo.dueForDeck("deck1").map { it.id })
    }

    @Test
    fun dueForDeckIncludesCardsWhoseDueDateHasPassed() = runTest {
        publishDeck("deck1", "c1")
        val overdue = SrsState(
            cardId = "c1",
            dueAt = epochMillis() - dayMs,
            intervalDays = 1,
            easeFactor = 2.5,
            repetitions = 1,
            lastGrade = SrsGrade.Good,
        )
        repo.upsert("deck1", overdue).getOrThrow()

        assertEquals(listOf("c1"), repo.dueForDeck("deck1").map { it.id })
    }

    @Test
    fun reviewingACardRemovesItFromTheDueQueue() = runTest {
        publishDeck("deck1", "c1")
        assertEquals(listOf("c1"), repo.dueForDeck("deck1").map { it.id })

        repo.review(testCard("c1"), SrsGrade.Again).getOrThrow()

        // Again reschedules ten minutes out — no longer due right now.
        assertEquals(emptyList(), repo.dueForDeck("deck1").map { it.id })
    }

    @Test
    fun dueTodayAggregatesAcrossOwnedDecks() = runTest {
        publishDeck("deck1", "c1")
        publishDeck("deck2", "c9")

        assertEquals(setOf("c1", "c9"), repo.dueToday().map { it.id }.toSet())
    }

    @Test
    fun dueTodayIncludesFollowedDecks() = runTest {
        publishDeck("mine", "c1")
        val theirs = putRemoteDeck("friendpk", "theirs", listOf(testCard("t1", deckId = "theirs")))
        deckRepo.followDeck(theirs).getOrThrow()

        // A followed deck is studiable; owned-decks-only meant its cards could never come up.
        assertEquals(setOf("c1", "t1"), repo.dueToday().map { it.id }.toSet())
    }

    @Test
    fun reviewingAFollowedDeckWritesSrsStateUnderYourOwnPubky() = runTest {
        val theirs = putRemoteDeck("friendpk", "theirs", listOf(testCard("t1", deckId = "theirs")))
        deckRepo.followDeck(theirs).getOrThrow()
        val card = repo.dueForDeck("theirs").single()

        repo.review(card, SrsGrade.Good).getOrThrow()
        repo.flush().getOrThrow()

        // Your review state, on your homeserver, keyed by *their* pubky — never a write to a deck
        // you cannot write.
        assertTrue(
            pubky.puts.any { it.first.startsWith("pubky://$TEST_PUBKY/pub/loopky/srs/friendpk/theirs/") },
            "no SRS write under the follower's pubky: ${pubky.puts.map { it.first }}",
        )
        assertTrue(
            pubky.puts.none { it.first.startsWith("pubky://friendpk/") },
            "wrote to the author's homeserver: ${pubky.puts.map { it.first }}",
        )
    }

    @Test
    fun dueTodayStillWorksWhenFollowedDecksAreUnreachable() = runTest {
        publishDeck("mine", "c1")
        val theirs = putRemoteDeck("friendpk", "theirs", listOf(testCard("t1", deckId = "theirs")))
        deckRepo.followDeck(theirs).getOrThrow()
        // The author's homeserver went away after the subscription was cached.
        pubky.store.remove("pubky://friendpk/pub/loopky/decks/theirs/manifest.json")

        assertEquals(setOf("c1"), repo.dueToday().map { it.id }.toSet())
    }

    /** A whole deck — manifest plus chunk records — on someone else's homeserver. */
    private fun putRemoteDeck(author: String, deckId: String, cards: List<Card>): Deck {
        val deck = testDeckWithCards(cards, id = deckId, authorPubky = author)
        val root = "pubky://$author/pub/loopky/decks/$deckId"
        pubky.store["$root/manifest.json"] = loopkyJson.encodeToString(deck.toDto())
        cards.chunked(CHUNK_SIZE).forEachIndexed { n, batch ->
            pubky.store["$root/cards/$n.json"] = loopkyJson.encodeToString(
                CardChunkDto(deck_id = deckId, chunk = n, cards = batch.map { it.toDto() }),
            )
        }
        return deck
    }
}
