package com.github.jvsena42.loopky.domain.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fixed-interval spaced-repetition scheduler. Pure functions over [SrsState] — no clock, no I/O;
 * the caller supplies `now` (see `com.github.jvsena42.loopky.util.epochMillis`). Business logic for
 * grading lives in `SrsRepositoryImpl`, which calls these.
 *
 * A grade schedules the card for `now + the days configured for that grade`, **every** review and
 * not only the first: Hard, Good and Easy each mean one number, the one the user set in
 * [StudySettings]. Again is the exception and is not configurable — it is `<10m`, a same-session
 * retry rather than a date.
 *
 * This deliberately replaces SM-2's compounding growth (interval × ease). Under the old rule a
 * card graded Good at a 3-day setting came back in 8 days the second time, 20 the third, and the
 * settings screen printed numbers the buttons then disagreed with from the second review onwards.
 * The trade is real and worth stating: intervals no longer lengthen as a card is learned, so a
 * well-known card keeps coming back on its Easy interval instead of drifting out to months.
 * [SrsState.easeFactor] and [SrsState.repetitions] are still tracked — they record how a card has
 * gone, and growth cannot return without them — but nothing here reads them for an interval.
 */
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val MINUTES_AGAIN = 10L

internal const val AGAIN_DELAY_MS = MINUTES_AGAIN * MINUTE_MS
internal const val DEFAULT_EASE = 2.5
internal const val MIN_EASE = 1.3

// Ease adjustments per grade. Recorded on the card, not read back for scheduling — see the file
// header. They are what a return to compounding growth would need, and dropping them would freeze
// every card at [DEFAULT_EASE] with no way to tell an easy card from a hard one.
private const val EASE_PENALTY_AGAIN = 0.20
private const val EASE_PENALTY_HARD = 0.15
private const val EASE_BONUS_EASY = 0.15

// formatInterval bucket thresholds (days).
private const val DAYS_IN_WEEK = 7.0
private const val DAYS_IN_MONTH = 30.0
private const val DAYS_IN_YEAR = 365.0

/**
 * Days are kept up to 30 rather than switching to weeks at 21. Flipping there meant the SRS row
 * read `Good 19d` / `Easy 3w` — the label stopped printing the number that decides maturity at
 * precisely the boundary it decides (#101 §6). Anki keeps days to 30 for the same reason.
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
 * The due date is `now` plus the days [settings] gives that grade — the card's current interval is
 * not an input, so the number on the button is the number the card gets on its first review and on
 * its fiftieth. Two consequences worth knowing. Changing a setting is **not** retroactive: an
 * already-scheduled card keeps the date it was given and picks the new number up at its next
 * grade. And `Again` is a 10-minute retry that resets `repetitions` and zeroes `intervalDays`,
 * which is what makes a lapsed card count as unlearned again for mastery.
 */
fun SrsState?.review(
    cardId: String,
    grade: SrsGrade,
    now: Long,
    settings: StudySettings = StudySettings.Default,
): SrsState {
    val ease = this?.easeFactor ?: DEFAULT_EASE
    val reps = this?.repetitions ?: 0

    /** A card put [days] out from `now`. The one place a due date is computed from an interval. */
    fun scheduled(days: Int, nextEase: Double) = SrsState(
        cardId = cardId,
        dueAt = now + days * DAY_MS,
        intervalDays = days,
        easeFactor = nextEase,
        repetitions = reps + 1,
        lastGrade = grade,
    )

    return when (grade) {
        SrsGrade.Again -> SrsState(
            cardId = cardId,
            dueAt = now + AGAIN_DELAY_MS,
            intervalDays = 0,
            easeFactor = max(MIN_EASE, ease - EASE_PENALTY_AGAIN),
            repetitions = 0,
            lastGrade = grade,
        )

        SrsGrade.Hard -> scheduled(settings.hardDays, max(MIN_EASE, ease - EASE_PENALTY_HARD))
        SrsGrade.Good -> scheduled(settings.goodDays, ease)
        SrsGrade.Easy -> scheduled(settings.easyDays, ease + EASE_BONUS_EASY)
    }
}

/**
 * Short, human-readable interval each grade would produce, keyed by grade — used to label the
 * SRSRow buttons live per card (the design's "each shows the next interval").
 *
 * [cap] ceilings every grade at that one, for the reverse half of a card being studied both ways:
 * the pair is scheduled from whichever direction went worse, so tapping Easy after a Hard on the
 * way out really does schedule the Hard interval. Grades above the cap therefore show the cap's
 * label, and two buttons reading the same is the honest outcome — a label promising more than its
 * button delivers would not be. Null, the default, leaves every grade speaking for itself.
 */
fun SrsState?.previewIntervals(
    cardId: String,
    now: Long,
    settings: StudySettings = StudySettings.Default,
    cap: SrsGrade? = null,
): Map<SrsGrade, String> =
    SrsGrade.entries.associateWith { grade ->
        val effective = if (cap == null) grade else minOf(grade, cap)
        val next = review(cardId, effective, now, settings)
        if (effective == SrsGrade.Again) "<10m" else formatInterval(next.intervalDays)
    }

/**
 * How far [cardIds] have been carried toward maturity, `0f..1f`.
 *
 * Each card contributes its own share of [thresholdDays] rather than a yes/no, so the number moves
 * on day one. A binary count of mature cards was what left the only progress number in the app
 * frozen at 0% through exactly the window it had to work in (#101 §1), and partial credit still
 * means the same thing at 100%.
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
