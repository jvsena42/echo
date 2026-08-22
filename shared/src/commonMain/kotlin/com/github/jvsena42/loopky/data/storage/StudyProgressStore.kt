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
    /** Null before anything has been studied on this device. */
    suspend fun load(): DailyStudyProgress?

    suspend fun save(progress: DailyStudyProgress)
}

internal const val KEY_STUDY_PROGRESS = "study_progress"

/** One day's counters on disk. Mirrors [DailyStudyProgress] so the domain stays serializer-free. */
@Serializable
internal data class StoredStudyProgress(
    val dayIndex: Int,
    val newCards: Int,
    val reviews: Int,
    val goalCelebrated: Boolean = false,
)

/** Shared by both platform implementations so the on-disk shape cannot drift between them. */
private val progressJson = Json { ignoreUnknownKeys = true }

internal fun encodeStudyProgress(progress: DailyStudyProgress): String = progressJson.encodeToString(
    StoredStudyProgress(
        dayIndex = progress.dayIndex,
        newCards = progress.newCards,
        reviews = progress.reviews,
        goalCelebrated = progress.goalCelebrated,
    ),
)

/** A counter that cannot be read is worth exactly one lost day of motivation — never an error. */
internal fun decodeStudyProgress(payload: String?): DailyStudyProgress? {
    if (payload.isNullOrBlank()) return null
    return runCatching { progressJson.decodeFromString<StoredStudyProgress>(payload) }
        .getOrNull()
        ?.let {
            DailyStudyProgress(
                dayIndex = it.dayIndex,
                newCards = it.newCards,
                reviews = it.reviews,
                goalCelebrated = it.goalCelebrated,
            )
        }
}
