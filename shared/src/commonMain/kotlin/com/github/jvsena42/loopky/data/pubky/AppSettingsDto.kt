package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.StudySettings
import kotlinx.serialization.Serializable

/**
 * The on-homeserver `settings.json` record (see [PubkyPaths.settings]).
 *
 * Written whole, like a deck manifest, so every update is a read-modify-write — which is why it is
 * modelled as one record with a `study` section rather than a bag of fields: a second section
 * added later must not be dropped by a client that only knows about this one.
 */
@Serializable
internal data class AppSettingsDto(
    val schema_version: Int = SCHEMA_VERSION,
    val study: StudySettingsDto = StudySettingsDto(),
    val updated_at: Long = 0,
)

@Serializable
internal data class StudySettingsDto(
    val new_cards_per_day: Int = StudySettings.Default.newCardsPerDayGoal,
    val first_hard_days: Int = StudySettings.Default.firstHardDays,
    val first_good_days: Int = StudySettings.Default.firstGoodDays,
    val first_easy_days: Int = StudySettings.Default.firstEasyDays,
)

internal fun StudySettings.toDto() = StudySettingsDto(
    new_cards_per_day = newCardsPerDayGoal,
    first_hard_days = firstHardDays,
    first_good_days = firstGoodDays,
    first_easy_days = firstEasyDays,
)

/** Sanitized on the way in: a record written by a future client must not break the scheduler. */
internal fun StudySettingsDto.toDomain() = StudySettings(
    newCardsPerDayGoal = new_cards_per_day,
    firstHardDays = first_hard_days,
    firstGoodDays = first_good_days,
    firstEasyDays = first_easy_days,
).sanitized()
