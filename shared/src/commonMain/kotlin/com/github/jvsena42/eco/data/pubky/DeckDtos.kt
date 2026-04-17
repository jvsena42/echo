package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.CardIndexEntry
import com.github.jvsena42.eco.domain.model.CardSide
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.MediaRef
import com.github.jvsena42.eco.domain.model.Tag
import kotlinx.serialization.Serializable

internal const val SCHEMA_VERSION = 1

@Serializable
internal data class ManifestDto(
    val schema_version: Int = SCHEMA_VERSION,
    val deck_id: String,
    val author_pubky: String,
    val title: String,
    val description: String? = null,
    val cover_emoji: String? = null,
    val cover_image_ref: MediaRefDto? = null,
    val tags: List<String> = emptyList(),
    val created_at: Long,
    val updated_at: Long,
    val cards: List<CardIndexDto> = emptyList(),
)

@Serializable
internal data class CardIndexDto(
    val id: String,
    val updated_at: Long,
)

@Serializable
internal data class CardDto(
    val schema_version: Int = SCHEMA_VERSION,
    val id: String,
    val deck_id: String,
    val updated_at: Long,
    val front: CardSideDto,
    val back: CardSideDto,
)

@Serializable
internal data class CardSideDto(
    val text: String? = null,
    val image_ref: MediaRefDto? = null,
    val audio_ref: MediaRefDto? = null,
)

@Serializable
internal data class MediaRefDto(
    val path: String,
    val mime: String,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val duration_ms: Long? = null,
)

// --- Mapping ------------------------------------------------------------------

internal fun Deck.toDto() = ManifestDto(
    deck_id = id,
    author_pubky = authorPubky,
    title = title,
    description = description,
    cover_emoji = coverEmoji,
    cover_image_ref = coverImageRef?.toDto(),
    tags = tags.map { it.value },
    created_at = createdAt,
    updated_at = updatedAt,
    cards = cardIndex.map { CardIndexDto(it.id, it.updatedAt) },
)

internal fun ManifestDto.toDomain() = Deck(
    id = deck_id,
    authorPubky = author_pubky,
    title = title,
    description = description,
    coverEmoji = cover_emoji,
    coverImageRef = cover_image_ref?.toImageDomain(),
    tags = tags.map { Tag(it) },
    createdAt = created_at,
    updatedAt = updated_at,
    cardIndex = cards.map { CardIndexEntry(it.id, it.updated_at) },
)

internal fun Card.toDto() = CardDto(
    id = id,
    deck_id = deckId,
    updated_at = updatedAt,
    front = front.toDto(),
    back = back.toDto(),
)

internal fun CardDto.toDomain() = Card(
    id = id,
    deckId = deck_id,
    updatedAt = updated_at,
    front = front.toDomain(),
    back = back.toDomain(),
)

internal fun CardSide.toDto() = CardSideDto(
    text = text,
    image_ref = imageRef?.toDto(),
    audio_ref = audioRef?.toDto(),
)

internal fun CardSideDto.toDomain() = CardSide(
    text = text,
    imageRef = image_ref?.toImageDomain(),
    audioRef = audio_ref?.toAudioDomain(),
)

internal fun MediaRef.Image.toDto() = MediaRefDto(
    path = path,
    mime = mime,
    sha256 = sha256,
    width = width,
    height = height,
)

internal fun MediaRef.Audio.toDto() = MediaRefDto(
    path = path,
    mime = mime,
    sha256 = sha256,
    duration_ms = durationMs,
)

internal fun MediaRefDto.toImageDomain() = MediaRef.Image(
    path = path,
    mime = mime,
    sha256 = sha256,
    width = width,
    height = height,
)

internal fun MediaRefDto.toAudioDomain() = MediaRef.Audio(
    path = path,
    mime = mime,
    sha256 = sha256,
    durationMs = duration_ms,
)
