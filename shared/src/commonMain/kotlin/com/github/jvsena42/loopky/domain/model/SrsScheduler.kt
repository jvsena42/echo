package com.github.jvsena42.loopky.domain.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Simplified SM-2 spaced-repetition scheduler. Pure functions over [SrsState] — no clock, no I/O;
 * the caller supplies `now` (see `com.github.jvsena42.loopky.util.epochMillis`). Business logic for
 * grading lives in `SrsRepositoryImpl`, which calls these.
 *
 * First-review intervals match the values shown in the study-session design
 * (`design/main/phone-loopky.pen` → SRSRow): Again `<10m` / Hard `1d` / Good `3d` / Easy `7d`.
 */
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val MINUTES_AGAIN = 10L

internal const val AGAIN_DELAY_MS = MINUTES_AGAIN * MINUTE_MS
internal const val DEFAULT_EASE = 2.5
internal const val MIN_EASE = 1.3

// Ease adjustments per grade.
private const val EASE_PENALTY_AGAIN = 0.20
private const val EASE_PENALTY_HARD = 0.15
private const val EASE_BONUS_EASY = 0.15

// Interval growth multipliers.
private const val HARD_MULTIPLIER = 1.2
private const val EASY_MULTIPLIER = 1.3

// First-review interval (days) for a brand-new card — matches the design's SRSRow labels.
private const val FIRST_HARD_DAYS = 1
private const val FIRST_GOOD_DAYS = 3
private const val FIRST_EASY_DAYS = 7

// formatInterval bucket thresholds (days).
private const val DAYS_IN_WEEK = 7.0
private const val DAYS_IN_MONTH = 30.0
private const val DAYS_IN_YEAR = 365.0
private const val MAX_DAYS_LABEL = 21
private const val MAX_WEEKS_LABEL = 60
private const val MAX_MONTHS_LABEL = 365

/** A card with no recorded state is "new" and counts as due immediately. */
fun SrsState?.isDue(now: Long): Boolean = this == null || dueAt <= now

/**
 * Compute the next [SrsState] after grading. Receiver is the card's current state, or `null` for a
 * card reviewed for the first time. [cardId] is required so a fresh state can be created.
 */
fun SrsState?.review(cardId: String, grade: SrsGrade, now: Long): SrsState {
    val ease = this?.easeFactor ?: DEFAULT_EASE
    val reps = this?.repetitions ?: 0
    val interval = this?.intervalDays ?: 0

    return when (grade) {
        SrsGrade.Again -> SrsState(
            cardId = cardId,
            dueAt = now + AGAIN_DELAY_MS,
            intervalDays = 0,
            easeFactor = max(MIN_EASE, ease - EASE_PENALTY_AGAIN),
            repetitions = 0,
            lastGrade = grade,
        )

        SrsGrade.Hard -> {
            val next = if (reps == 0) FIRST_HARD_DAYS else max(interval + 1, (interval * HARD_MULTIPLIER).roundToInt())
            SrsState(cardId, now + next * DAY_MS, next, max(MIN_EASE, ease - EASE_PENALTY_HARD), reps + 1, grade)
        }

        SrsGrade.Good -> {
            val next = if (reps == 0) FIRST_GOOD_DAYS else max(interval + 1, (interval * ease).roundToInt())
            SrsState(cardId, now + next * DAY_MS, next, ease, reps + 1, grade)
        }

        SrsGrade.Easy -> {
            val next = if (reps == 0) FIRST_EASY_DAYS else max(interval + 1, (interval * ease * EASY_MULTIPLIER).roundToInt())
            SrsState(cardId, now + next * DAY_MS, next, ease + EASE_BONUS_EASY, reps + 1, grade)
        }
    }
}

/**
 * Short, human-readable interval each grade would produce, keyed by grade — used to label the
 * SRSRow buttons live per card (the design's "each shows the next interval").
 */
fun SrsState?.previewIntervals(cardId: String, now: Long): Map<SrsGrade, String> =
    SrsGrade.entries.associateWith { grade ->
        val next = review(cardId, grade, now)
        if (grade == SrsGrade.Again) "<10m" else formatInterval(next.intervalDays)
    }

private fun formatInterval(days: Int): String = when {
    days <= 0 -> "<10m"
    days < MAX_DAYS_LABEL -> "${days}d"
    days < MAX_WEEKS_LABEL -> "${(days / DAYS_IN_WEEK).roundToInt()}w"
    days < MAX_MONTHS_LABEL -> "${(days / DAYS_IN_MONTH).roundToInt()}mo"
    else -> "${(days / DAYS_IN_YEAR).roundToInt()}y"
}
