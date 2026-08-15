package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import kotlinx.serialization.Serializable

/**
 * On-homeserver SRS review state for a single card, stored at
 * `/pub/echo/decks/{deckId}/srs/{cardId}.json` (see [PubkyPaths.srs]).
 *
 * Per the user's decision, SRS is Pubky-backed in v1 ("Pubky is the source of truth"), resolving the
 * CLAUDE.md vs Architecture §8.3 contradiction. [last_grade] is the grade ordinal (Again=0 … Easy=3).
 */
@Serializable
internal data class SrsStateDto(
    val schema_version: Int = SCHEMA_VERSION,
    val card_id: String,
    val due_at: Long,
    val interval_days: Int,
    val ease_factor: Double,
    val repetitions: Int,
    val last_grade: Int? = null,
)

internal fun SrsState.toDto() = SrsStateDto(
    card_id = cardId,
    due_at = dueAt,
    interval_days = intervalDays,
    ease_factor = easeFactor,
    repetitions = repetitions,
    last_grade = lastGrade?.ordinal,
)

internal fun SrsStateDto.toDomain() = SrsState(
    cardId = card_id,
    dueAt = due_at,
    intervalDays = interval_days,
    easeFactor = ease_factor,
    repetitions = repetitions,
    lastGrade = last_grade?.let { SrsGrade.entries.getOrNull(it) },
)
