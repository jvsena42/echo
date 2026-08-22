package com.github.jvsena42.loopky.domain.model

import kotlin.math.max

/** Anki's default, and ours: the number of never-seen cards a day of study aims at. */
const val DEFAULT_NEW_CARDS_PER_DAY = 20

private const val MIN_GOAL = 1
private const val MAX_GOAL = 999
private const val MIN_FIRST_INTERVAL_DAYS = 1
private const val MAX_FIRST_INTERVAL_DAYS = 365

/**
 * The user's own scheduling preferences, synced through
 * `com.github.jvsena42.loopky.data.repository.SettingsRepository` so a second device schedules the
 * same way this one does.
 *
 * [newCardsPerDayGoal] is a **goal, not a cap**: reaching it is announced and nothing is withheld
 * afterwards. Nothing in the queue-building path may consult it.
 *
 * The three `first…Days` values are the intervals a brand-new card gets on its very first grade —
 * the numbers the study screen prints on the Hard/Good/Easy buttons. They are deliberately
 * unbounded above (well, [MAX_FIRST_INTERVAL_DAYS]); [maturityThresholdDays] is what keeps a long
 * first interval from turning one grade into a mastered card.
 */
data class StudySettings(
    val newCardsPerDayGoal: Int = DEFAULT_NEW_CARDS_PER_DAY,
    val firstHardDays: Int = 1,
    val firstGoodDays: Int = 3,
    val firstEasyDays: Int = 7,
) {
    /** Clamps every field into a range the scheduler can survive. Applied on read *and* on write. */
    fun sanitized(): StudySettings = StudySettings(
        newCardsPerDayGoal = newCardsPerDayGoal.coerceIn(MIN_GOAL, MAX_GOAL),
        firstHardDays = firstHardDays.coerceIn(MIN_FIRST_INTERVAL_DAYS, MAX_FIRST_INTERVAL_DAYS),
        firstGoodDays = firstGoodDays.coerceIn(MIN_FIRST_INTERVAL_DAYS, MAX_FIRST_INTERVAL_DAYS),
        firstEasyDays = firstEasyDays.coerceIn(MIN_FIRST_INTERVAL_DAYS, MAX_FIRST_INTERVAL_DAYS),
    )

    companion object {
        val Default = StudySettings()
        val GOAL_RANGE = MIN_GOAL..MAX_GOAL
        val FIRST_INTERVAL_RANGE = MIN_FIRST_INTERVAL_DAYS..MAX_FIRST_INTERVAL_DAYS
    }
}

/**
 * Where the maturity line sits for *this* user — SM-2's 21 days, or past their longest first
 * interval, whichever is further out.
 *
 * Without the second half, someone who sets Easy to 30 days would mark a brand-new card 100%
 * mastered with a single grade: a progress bar you move by editing a preference rather than by
 * studying. `maxOf` covers all three because ordering is not enforced, and `+ 1` because a *first*
 * review must never graduate a card on its own.
 */
val StudySettings.maturityThresholdDays: Int
    get() = max(MATURE_INTERVAL_DAYS, maxOf(firstHardDays, firstGoodDays, firstEasyDays) + 1)
