package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Device-local record of today's study, so "12 reviews, 7 new cards" survives the app being killed
 * mid-session and does not reset every launch.
 *
 * Deliberately not a Pubky record, unlike the settings it is measured against: this is a
 * motivational counter, and reconciling "cards introduced today" across two devices would cost a
 * round trip to answer a question nobody would notice getting slightly wrong.
 *
 * Alongside [PendingReviewStore], and for the same reason — structured state that belongs on the
 * device rather than in the keystore or on the homeserver.
 */
interface StudyProgressStore {
    /**
     * [ownerPubky]'s counters for today, or null before they have studied anything on this device.
     *
     * Scoped by account even though it is one device-wide record: "12 reviews, 7 new" is a claim
     * about a person, and handing a freshly created account the previous user's tally is a small
     * lie the app then congratulates them for. A record belonging to someone else reads as absent.
     */
    suspend fun load(ownerPubky: String): DailyStudyProgress?

    suspend fun save(ownerPubky: String, progress: DailyStudyProgress)
}

internal const val KEY_STUDY_PROGRESS = "study_progress"

/** One day's counters on disk. Mirrors [DailyStudyProgress] so the domain stays serializer-free. */
@Serializable
internal data class StoredStudyProgress(
    /** Whose tally this is. Null on a record written before the counters were account-scoped. */
    val ownerPubky: String? = null,
    val dayIndex: Int,
    val newCards: Int,
    val reviews: Int,
    val goalCelebrated: Boolean = false,
)

/** Shared by both platform implementations so the on-disk shape cannot drift between them. */
private val progressJson = Json { ignoreUnknownKeys = true }

internal fun encodeStudyProgress(
    ownerPubky: String,
    progress: DailyStudyProgress,
): String = progressJson.encodeToString(
    StoredStudyProgress(
        ownerPubky = ownerPubky,
        dayIndex = progress.dayIndex,
        newCards = progress.newCards,
        reviews = progress.reviews,
        goalCelebrated = progress.goalCelebrated,
    ),
)

/**
 * A counter that cannot be read is worth exactly one lost day of motivation — never an error.
 *
 * A record belonging to a different account — or to none, which is how one written before this was
 * scoped decodes — is treated the same way as an unreadable one. Starting the day at zero is the
 * right answer for someone who has not studied today; inheriting a stranger's tally is not.
 */
internal fun decodeStudyProgress(payload: String?, ownerPubky: String): DailyStudyProgress? {
    if (payload.isNullOrBlank()) return null
    return runCatching { progressJson.decodeFromString<StoredStudyProgress>(payload) }
        .getOrNull()
        ?.takeIf { it.ownerPubky == ownerPubky }
        ?.let {
            DailyStudyProgress(
                dayIndex = it.dayIndex,
                newCards = it.newCards,
                reviews = it.reviews,
                goalCelebrated = it.goalCelebrated,
            )
        }
}
