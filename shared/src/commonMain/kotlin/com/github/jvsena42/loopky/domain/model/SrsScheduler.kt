package com.github.jvsena42.loopky.domain.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Simplified SM-2 spaced-repetition scheduler. Pure functions over [SrsState] — no clock, no I/O;
 * the caller supplies `now` (see `com.github.jvsena42.loopky.util.epochMillis`). Business logic for
 * grading lives in `SrsRepositoryImpl`, which calls these.
 *
 * First-review intervals default to Again `<10m` / Hard `1d` / Good `3d` / Easy `7d`, and are
 * overridable per user through [StudySettings].
 */
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val MINUTES_AGAIN = 10L

internal const val AGAIN_DELAY_MS = MINUTES_AGAIN * MINUTE_MS
internal const val DEFAULT_EASE = 2.5
internal const val MIN_EASE = 1.3

/**
 * SM-2's convention: a card whose interval has reached 21 days is mature.
 *
 * The floor, not the whole rule — a user who lengthens their first intervals pushes the line out
 * with them. See [StudySettings.maturityThresholdDays], which is what callers should ask.
 */
const val MATURE_INTERVAL_DAYS = 21

// Ease adjustments per grade.
private const val EASE_PENALTY_AGAIN = 0.20
private const val EASE_PENALTY_HARD = 0.15
private const val EASE_BONUS_EASY = 0.15

// Interval growth multipliers.
private const val HARD_MULTIPLIER = 1.2
private const val EASY_MULTIPLIER = 1.3

// formatInterval bucket thresholds (days).
private const val DAYS_IN_WEEK = 7.0
private const val DAYS_IN_MONTH = 30.0
private const val DAYS_IN_YEAR = 365.0

/**
 * Days are kept up to 30, past the 21-day maturity line, rather than switching to weeks at exactly
 * 21. Flipping there meant the SRS row read `Good 19d` / `Easy 3w` — the label stopped printing the
 * number that decides maturity at precisely the boundary it decides (#101 §6). Anki keeps days to
 * 30 for the same reason.
 */
private const val MAX_DAYS_LABEL = 30
private const val MAX_WEEKS_LABEL = 60
private const val MAX_MONTHS_LABEL = 365

/**
 * A card that has never been graded.
 *
 * Deliberately *not* the same as [isDue]: nothing about an unseen card is late, and conflating the
 * two made a freshly imported 1669-card deck open on "1669 due" (#101 §7). New cards are still
 * studiable — they are simply counted, headlined and queued separately.
 */
fun SrsState?.isNew(): Boolean = this == null

/** A card that has been graded before and has come up for review again. New cards are not due. */
fun SrsState?.isDue(now: Long): Boolean = this != null && dueAt <= now

/**
 * Compute the next [SrsState] after grading. Receiver is the card's current state, or `null` for a
 * card reviewed for the first time. [cardId] is required so a fresh state can be created.
 *
 * [settings] supplies the first-review intervals only. Growth multipliers, ease penalties and the
 * `Again` delay are fixed, and nothing here rewrites an already-scheduled card — so changing a
 * setting is never retroactive. One consequence worth knowing: `Again` resets `repetitions` to 0,
 * so a lapsed card is treated as new again and picks up the *current* first interval next time.
 */
fun SrsState?.review(
    cardId: String,
    grade: SrsGrade,
    now: Long,
    settings: StudySettings = StudySettings.Default,
): SrsState {
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
            val next = if (reps == 0) {
                settings.firstHardDays
            } else {
                max(interval + 1, (interval * HARD_MULTIPLIER).roundToInt())
            }
            SrsState(cardId, now + next * DAY_MS, next, max(MIN_EASE, ease - EASE_PENALTY_HARD), reps + 1, grade)
        }

        SrsGrade.Good -> {
            val next = if (reps == 0) settings.firstGoodDays else max(interval + 1, (interval * ease).roundToInt())
            SrsState(cardId, now + next * DAY_MS, next, ease, reps + 1, grade)
        }

        SrsGrade.Easy -> {
            val next = if (reps == 0) {
                settings.firstEasyDays
            } else {
                max(interval + 1, (interval * ease * EASY_MULTIPLIER).roundToInt())
            }
            SrsState(cardId, now + next * DAY_MS, next, ease + EASE_BONUS_EASY, reps + 1, grade)
        }
    }
}

/**
 * Short, human-readable interval each grade would produce, keyed by grade — used to label the
 * SRSRow buttons live per card (the design's "each shows the next interval").
 */
fun SrsState?.previewIntervals(
    cardId: String,
    now: Long,
    settings: StudySettings = StudySettings.Default,
): Map<SrsGrade, String> =
    SrsGrade.entries.associateWith { grade ->
        val next = review(cardId, grade, now, settings)
        if (grade == SrsGrade.Again) "<10m" else formatInterval(next.intervalDays)
    }

/**
 * How far [cardIds] have been carried toward maturity, `0f..1f`.
 *
 * Each card contributes its own share of [thresholdDays] rather than a yes/no. A binary count of
 * mature cards cannot move until the eighth day of study — the scheduler caps a new card's first
 * interval well below the threshold, so two sessions ≥8 days apart are the minimum — which left the
 * only progress number in the app frozen at 0% through exactly the window it had to work in
 * (#101 §1). Partial credit moves on day one and still means the same thing at 100%.
 *
 * A card lapsed by `Again` has `intervalDays = 0` and contributes nothing, which is honest.
 *
 * Callers pass [thresholdDays] from [StudySettings.maturityThresholdDays]. Note that summing
 * fractions cannot be compared to `1f` for "fully mastered" — three mature cards land on
 * 0.99999994. Use [isFullyMastered].
 */
fun masteryShare(cardIds: List<String>, states: Map<String, SrsState>, thresholdDays: Int): Float {
    if (cardIds.isEmpty() || thresholdDays <= 0) return 0f
    val total = cardIds.sumOf { id ->
        min(states[id]?.intervalDays ?: 0, thresholdDays).toDouble() / thresholdDays
    }
    return (total / cardIds.size).toFloat()
}

/** Whether every card in [cardIds] has reached [thresholdDays]. The exact test [masteryShare] cannot give. */
fun isFullyMastered(cardIds: List<String>, states: Map<String, SrsState>, thresholdDays: Int): Boolean =
    cardIds.isNotEmpty() && cardIds.all { (states[it]?.intervalDays ?: 0) >= thresholdDays }

private fun formatInterval(days: Int): String = when {
    days <= 0 -> "<10m"
    days < MAX_DAYS_LABEL -> "${days}d"
    days < MAX_WEEKS_LABEL -> "${(days / DAYS_IN_WEEK).roundToInt()}w"
    days < MAX_MONTHS_LABEL -> "${(days / DAYS_IN_MONTH).roundToInt()}mo"
    else -> "${(days / DAYS_IN_YEAR).roundToInt()}y"
}
