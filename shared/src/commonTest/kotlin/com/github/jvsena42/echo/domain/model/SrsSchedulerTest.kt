package com.github.jvsena42.echo.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("MagicNumber") // Interval/ease literals are the assertions under test.
class SrsSchedulerTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun state(intervalDays: Int, repetitions: Int, dueAt: Long = now): SrsState =
        SrsState(
            cardId = "c",
            dueAt = dueAt,
            intervalDays = intervalDays,
            easeFactor = 2.5,
            repetitions = repetitions,
            lastGrade = SrsGrade.Good,
        )

    @Test
    fun newCardIsDue() {
        assertTrue((null as SrsState?).isDue(now))
    }

    @Test
    fun futureCardIsNotDue() {
        val s = state(intervalDays = 1, repetitions = 1, dueAt = now + day)
        assertFalse(s.isDue(now))
        assertTrue(s.isDue(now + 2 * day))
    }

    @Test
    fun newCardFirstReviewMatchesDesignIntervals() {
        assertEquals(0, (null as SrsState?).review("c", SrsGrade.Again, now).intervalDays)
        assertEquals(1, (null as SrsState?).review("c", SrsGrade.Hard, now).intervalDays)
        assertEquals(3, (null as SrsState?).review("c", SrsGrade.Good, now).intervalDays)
        assertEquals(7, (null as SrsState?).review("c", SrsGrade.Easy, now).intervalDays)
    }

    @Test
    fun againResetsIntervalAndDropsEase() {
        val mature = state(intervalDays = 30, repetitions = 5)
        val next = mature.review("c", SrsGrade.Again, now)
        assertEquals(0, next.intervalDays)
        assertEquals(0, next.repetitions)
        assertEquals(now + AGAIN_DELAY_MS, next.dueAt)
        assertTrue(next.easeFactor < mature.easeFactor)
    }

    @Test
    fun goodGrowsIntervalByEase() {
        val next = state(intervalDays = 10, repetitions = 3).review("c", SrsGrade.Good, now)
        assertEquals(25, next.intervalDays) // round(10 * 2.5)
        assertEquals(now + 25 * day, next.dueAt)
    }

    @Test
    fun easeNeverDropsBelowFloor() {
        var s: SrsState? = null
        repeat(10) { s = s.review("c", SrsGrade.Again, now) }
        assertTrue(s!!.easeFactor >= MIN_EASE)
    }

    @Test
    fun previewLabelsForNewCard() {
        val labels = (null as SrsState?).previewIntervals("c", now)
        assertEquals("<10m", labels[SrsGrade.Again])
        assertEquals("1d", labels[SrsGrade.Hard])
        assertEquals("3d", labels[SrsGrade.Good])
        assertEquals("7d", labels[SrsGrade.Easy])
    }
}
