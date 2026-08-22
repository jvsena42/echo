package com.github.jvsena42.loopky.domain.model

enum class SrsGrade { Again, Hard, Good, Easy }

data class SrsState(
    val cardId: String,
    val dueAt: Long,
    val intervalDays: Int,
    val easeFactor: Double,
    val repetitions: Int,
    val lastGrade: SrsGrade?,
)

/**
 * What a deck has waiting, split the way the scheduler actually sees it.
 *
 * [due] is cards graded before whose next review has come up; [new] is cards never graded at all.
 * They are separate because a single number could not tell "you are behind on 1669 reviews" from
 * "this deck has 1669 cards you have not met" — and it reported the second as the first (#101 §7).
 */
data class DeckCounts(val due: Int = 0, val new: Int = 0) {
    /** Anything to study at all. */
    val total: Int get() = due + new
}

/**
 * One local day of study. Device-local and motivational, not a synced record: merging "cards
 * introduced today" across devices is not worth a round trip.
 *
 * [dayIndex] is a local-day number from `com.github.jvsena42.loopky.util.localDayIndex`.
 */
data class DailyStudyProgress(
    val dayIndex: Int = 0,
    val newCards: Int = 0,
    val reviews: Int = 0,
) {
    /**
     * This progress if the day is still [todayIndex], a zeroed day otherwise.
     *
     * Must be applied on **read** as well as on write: a user who crosses midnight with the app
     * open would otherwise keep yesterday's count all through the new day.
     */
    fun forToday(todayIndex: Int): DailyStudyProgress =
        if (dayIndex == todayIndex) this else DailyStudyProgress(dayIndex = todayIndex)
}

/**
 * A deck's progress toward maturity.
 *
 * [share] is `0f..1f` partial credit (see `masteryShare`); [isComplete] is the exact
 * every-card-is-mature test, which the share cannot answer — summing thirds of 21 days lands on
 * 0.99999994 for a deck that genuinely is finished.
 */
data class DeckMastery(val share: Float, val isComplete: Boolean)
