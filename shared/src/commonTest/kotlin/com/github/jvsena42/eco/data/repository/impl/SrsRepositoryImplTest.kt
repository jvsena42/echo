package com.github.jvsena42.eco.data.repository.impl

import com.github.jvsena42.eco.data.pubky.SrsStateDto
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.SrsGrade
import com.github.jvsena42.eco.domain.model.SrsState
import com.github.jvsena42.eco.testing.CountingRevalidator
import com.github.jvsena42.eco.testing.FakePubkyClient
import com.github.jvsena42.eco.testing.RecordingTagRepository
import com.github.jvsena42.eco.testing.TEST_PUBKY
import com.github.jvsena42.eco.testing.signedInProvider
import com.github.jvsena42.eco.testing.testCard
import com.github.jvsena42.eco.testing.testDeck
import com.github.jvsena42.eco.util.epochMillis
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val index = cards.map { CardIndexEntry(it.id, it.updatedAt) }
        deckRepo.publish(testDeck(id = deckId, cardIndex = index), cards).getOrThrow()
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

        val url = "pubky://$TEST_PUBKY/pub/echo/decks/deck1/srs/c1.json"
        val dto = echoJson.decodeFromString<SrsStateDto>(pubky.store.getValue(url))
        assertEquals("c1", dto.card_id)
        assertEquals(expected = 3, actual = dto.interval_days)
        assertEquals(SrsGrade.Good.ordinal, dto.last_grade)
        assertEquals(state, repo.stateFor("c1"))
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

        val url = "pubky://$TEST_PUBKY/pub/echo/decks/deck1/srs/c1.json"
        assertTrue(url in pubky.store)
        assertEquals(state, repo.stateFor("c1"))
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

        // A fresh repo has a cold cache; reviewing c1 must load the persisted state from the
        // homeserver (repetitions grows from 3, not from a zeroed new-card baseline).
        val coldRepo = SrsRepositoryImpl(pubky, session, revalidator, deckRepo, cardRepo)
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
}
