package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import kotlinx.serialization.Serializable

/**
 * On-homeserver SRS review state for a single card. Stored batched in
 * `/pub/loopky/srs/{authorPubky}/{deckId}/{n}.json` (see [PubkyPaths.srsChunk]) rather than one
 * record per card.
 *
 * SRS is Pubky-backed ("Pubky is the source of truth"). [last_grade] is the grade ordinal
 * (Again=0 … Easy=3).
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

/** One `srs/{author}/{deck}/{n}.json` record: a batch of review states. */
@Serializable
internal data class SrsChunkDto(
    val schema_version: Int = SCHEMA_VERSION,
    val deck_id: String,
    val author_pubky: String,
    val chunk: Int,
    val states: List<SrsStateDto> = emptyList(),
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
