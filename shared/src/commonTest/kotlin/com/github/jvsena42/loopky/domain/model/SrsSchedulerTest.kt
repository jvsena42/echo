package com.github.jvsena42.loopky.domain.model

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
    fun newCardIsNewAndNotDue() {
        // Nothing about a card you have never met is late. Counting it as due is what made a fresh
        // import open on "1669 due" (#101 §7).
        assertTrue((null as SrsState?).isNew())
        assertFalse((null as SrsState?).isDue(now))
    }

    @Test
    fun aGradedCardIsNoLongerNew() {
        assertFalse(state(intervalDays = 1, repetitions = 1).isNew())
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
    fun firstReviewUsesTheUsersOwnIntervals() {
        val settings = StudySettings(firstHardDays = 2, firstGoodDays = 5, firstEasyDays = 30)
        assertEquals(2, (null as SrsState?).review("c", SrsGrade.Hard, now, settings).intervalDays)
        assertEquals(5, (null as SrsState?).review("c", SrsGrade.Good, now, settings).intervalDays)
        assertEquals(30, (null as SrsState?).review("c", SrsGrade.Easy, now, settings).intervalDays)
        assertEquals(now + 30 * day, (null as SrsState?).review("c", SrsGrade.Easy, now, settings).dueAt)
    }

    @Test
    fun customIntervalsDoNotReachAlreadyScheduledCards() {
        // Only the reps == 0 branches read the settings, so nothing already scheduled moves.
        val settings = StudySettings(firstGoodDays = 90)
        val next = state(intervalDays = 10, repetitions = 3).review("c", SrsGrade.Good, now, settings)
        assertEquals(25, next.intervalDays)
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

    @Test
    fun previewLabelsFollowTheUsersIntervals() {
        val labels = (null as SrsState?).previewIntervals("c", now, StudySettings(firstEasyDays = 21))
        assertEquals("21d", labels[SrsGrade.Easy])
    }

    @Test
    fun intervalLabelsStayInDaysAcrossTheMaturityLine() {
        // 21d used to render "3w", hiding the number the mastery threshold is built on (#101 §6).
        assertEquals("21d", (null as SrsState?).previewIntervals("c", now, StudySettings(firstGoodDays = 21))[SrsGrade.Good])
        assertEquals("29d", (null as SrsState?).previewIntervals("c", now, StudySettings(firstGoodDays = 29))[SrsGrade.Good])
        assertEquals("4w", (null as SrsState?).previewIntervals("c", now, StudySettings(firstGoodDays = 30))[SrsGrade.Good])
    }

    // --- mastery ---------------------------------------------------------------------------

    private fun states(vararg intervals: Pair<String, Int>): Map<String, SrsState> =
        intervals.associate { (id, days) -> id to state(intervalDays = days, repetitions = 1).copy(cardId = id) }

    @Test
    fun masteryMovesOnTheFirstSession() {
        // The exact case from #101's table, row 4: four cards graded Easy, previously 0%.
        val ids = listOf("a", "b", "c", "d")
        val share = masteryShare(ids, states("a" to 7, "b" to 7, "c" to 7, "d" to 7), MATURE_INTERVAL_DAYS)
        assertEquals(expected = 7f / 21f, actual = share, absoluteTolerance = 0.0001f)
    }

    @Test
    fun unseenAndLapsedCardsContributeNothing() {
        val ids = listOf("a", "b")
        assertEquals(0f, masteryShare(ids, emptyMap(), MATURE_INTERVAL_DAYS))
        assertEquals(0f, masteryShare(ids, states("a" to 0, "b" to 0), MATURE_INTERVAL_DAYS))
    }

    @Test
    fun aCardPastTheThresholdIsCappedAtItsOwnShare() {
        val ids = listOf("a", "b")
        // 'a' at 100 days must not carry 'b' past the pair's fair half.
        assertEquals(0.5f, masteryShare(ids, states("a" to 100, "b" to 0), MATURE_INTERVAL_DAYS))
    }

    @Test
    fun masteryEmptyDeckIsZero() {
        assertEquals(0f, masteryShare(emptyList(), emptyMap(), MATURE_INTERVAL_DAYS))
    }

    @Test
    fun fullMasteryIsAnExactTestNotAFloatComparison() {
        val ids = listOf("a", "b", "c")
        val mature = states("a" to 21, "b" to 21, "c" to 21)
        // Summing thirds lands just under 1f, which is why isFullyMastered exists.
        assertTrue(masteryShare(ids, mature, MATURE_INTERVAL_DAYS) <= 1f)
        assertTrue(isFullyMastered(ids, mature, MATURE_INTERVAL_DAYS))
        assertFalse(isFullyMastered(ids, states("a" to 21, "b" to 21, "c" to 20), MATURE_INTERVAL_DAYS))
    }

    @Test
    fun anEmptyDeckIsNeverFullyMastered() {
        assertFalse(isFullyMastered(emptyList(), emptyMap(), MATURE_INTERVAL_DAYS))
    }

    @Test
    fun aLongerFirstIntervalRaisesTheBarInsteadOfGamingIt() {
        // Easy = 30 would otherwise mark a brand-new card mastered with one grade.
        val settings = StudySettings(firstEasyDays = 30)
        assertEquals(31, settings.maturityThresholdDays)
        val graded = states("a" to 30)
        assertFalse(isFullyMastered(listOf("a"), graded, settings.maturityThresholdDays))
        assertTrue(masteryShare(listOf("a"), graded, settings.maturityThresholdDays) < 1f)
    }

    @Test
    fun theThresholdNeverDropsBelowSmTwosTwentyOneDays() {
        assertEquals(MATURE_INTERVAL_DAYS, StudySettings.Default.maturityThresholdDays)
        assertEquals(MATURE_INTERVAL_DAYS, StudySettings(firstEasyDays = 2).maturityThresholdDays)
    }

    @Test
    fun theThresholdFollowsWhicheverFirstIntervalIsLongest() {
        // Ordering is not enforced, so Hard may legitimately be the longest of the three.
        assertEquals(41, StudySettings(firstHardDays = 40, firstGoodDays = 3, firstEasyDays = 7).maturityThresholdDays)
    }

    // --- settings --------------------------------------------------------------------------

    @Test
    fun sanitizedClampsOutOfRangeValues() {
        val clamped = StudySettings(newCardsPerDayGoal = 0, firstHardDays = 0, firstGoodDays = 9_999).sanitized()
        assertEquals(1, clamped.newCardsPerDayGoal)
        assertEquals(1, clamped.firstHardDays)
        assertEquals(365, clamped.firstGoodDays)
    }

    @Test
    fun sanitizedLeavesAValidSettingAlone() {
        val settings = StudySettings(newCardsPerDayGoal = 50, firstHardDays = 2, firstGoodDays = 4, firstEasyDays = 10)
        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun theDefaultGoalIsTwenty() {
        assertEquals(20, StudySettings.Default.newCardsPerDayGoal)
    }

    // --- daily progress --------------------------------------------------------------------

    @Test
    fun progressSurvivesWithinTheSameDay() {
        val p = DailyStudyProgress(dayIndex = 100, newCards = 3, reviews = 12)
        assertEquals(p, p.forToday(100))
    }

    @Test
    fun progressZeroesWhenTheDayTurns() {
        val p = DailyStudyProgress(dayIndex = 100, newCards = 3, reviews = 12)
        assertEquals(DailyStudyProgress(dayIndex = 101), p.forToday(101))
    }

    // --- capped interval labels (the reverse half of a paired card) ------------------------

    @Test
    fun anUncappedPreviewIsWhatItAlwaysWas() {
        val base = state(intervalDays = 10, repetitions = 4)

        assertEquals(
            base.previewIntervals("c1", now),
            base.previewIntervals("c1", now, cap = null),
        )
    }

    @Test
    fun aCappedPreviewShowsWhatTheButtonWillActuallySchedule() {
        // The pair lands on its weaker direction, so on the reverse of a card already graded Hard,
        // tapping Easy schedules the Hard interval. Two buttons reading the same is the honest
        // outcome; a label promising more than its button delivers would not be.
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Hard)
        val hard = base.previewIntervals("c1", now)[SrsGrade.Hard]

        assertEquals(hard, capped[SrsGrade.Hard])
        assertEquals(hard, capped[SrsGrade.Good])
        assertEquals(hard, capped[SrsGrade.Easy])
    }

    @Test
    fun aCappedPreviewLeavesGradesBelowTheCapSpeakingForThemselves() {
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Good)

        assertEquals("<10m", capped[SrsGrade.Again])
        assertEquals(base.previewIntervals("c1", now)[SrsGrade.Hard], capped[SrsGrade.Hard])
    }

    @Test
    fun cappingAtAgainMakesEveryButtonSayTenMinutes() {
        val base = state(intervalDays = 10, repetitions = 4)
        val capped = base.previewIntervals("c1", now, cap = SrsGrade.Again)

        assertTrue(capped.values.all { it == "<10m" }, "got $capped")
    }
}
