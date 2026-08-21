package com.github.jvsena42.loopky.data.anki

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** One row of Anki's `notes` table, before any of it is interpreted. */
internal data class RawNote(val fields: List<String>, val tags: String)

/** Everything read straight out of a collection, before field mapping decides what to keep. */
internal data class RawCollection(
    val rows: List<RawNote>,
    val deckName: String?,
    val deckDescription: String?,
    val fieldNamesByOrd: List<String>,
    /** Held notes, but every one of them was Anki's compatibility placeholder. */
    val stubOnly: Boolean,
) {
    /** Field names padded to [count], so the picker can always label every column it offers. */
    fun fieldNames(count: Int): List<String> =
        (0 until count).map { fieldNamesByOrd.getOrNull(it) ?: "Field ${it + 1}" }
}

internal fun SQLiteDatabase.readRawNotes(): RawCollection {
    val rows = mutableListOf<RawNote>()
    var stubOnly = true
    // `flds` holds the note's fields joined by 0x1F. One row per note, so a 20k-card deck is one
    // cursor walk rather than 20k reads.
    rawQuery("SELECT flds, tags FROM notes", null).use { cursor ->
        while (cursor.moveToNext()) {
            val flds = cursor.getString(0) ?: continue
            if (!isLegacyStubNote(flds)) stubOnly = false
            rows += RawNote(
                fields = flds.split(ANKI_FIELD_SEPARATOR),
                tags = cursor.getString(1).orEmpty(),
            )
        }
    }
    val deck = readDeck()
    return RawCollection(
        rows = rows,
        deckName = deck?.name,
        deckDescription = deck?.description?.let(::readableDescription),
        fieldNamesByOrd = readFieldNames(),
        stubOnly = rows.isNotEmpty() && stubOnly,
    )
}

private fun readableDescription(raw: String): String? =
    parseAnkiField(raw).text.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION_CHARS)

private data class AnkiDeck(val name: String?, val description: String?)

/**
 * Name and description of the deck, used only to prefill the commit screen.
 *
 * Two schemas, and the description hides in a different place in each. Modern collections keep one
 * row per deck in a `decks` table, with the description inside a protobuf `kind` blob; older ones
 * keep every deck as JSON in `col.decks`. Both are best-effort — a default the user can edit is not
 * worth failing an import over — so anything unexpected gives up quietly and leaves the field blank.
 */
private fun SQLiteDatabase.readDeck(): AnkiDeck? =
    runCatching { readModernDeck() }.getOrNull()
        ?: runCatching { readLegacyDeck() }.getOrNull()

private fun SQLiteDatabase.readModernDeck(): AnkiDeck? =
    rawQuery("SELECT name, kind FROM decks WHERE id != 1 ORDER BY id LIMIT 1", null).use { c ->
        if (!c.moveToFirst()) return null
        AnkiDeck(
            name = c.getString(0)?.rootDeckName(ANKI_FIELD_SEPARATOR.toString()),
            description = c.getBlob(1)?.let(::normalDeckDescription),
        )
    }

private fun SQLiteDatabase.readLegacyDeck(): AnkiDeck? =
    rawQuery("SELECT decks FROM col LIMIT 1", null).use { c ->
        if (!c.moveToFirst()) return null
        val decks = JSONObject(c.getString(0) ?: return null)
        // The *shallowest* deck, not the first one listed. A deck with subdecks stores one JSON
        // object per subdeck, in no useful order, and only the root carries the description —
        // taking whichever came first landed on "Biochemistry::First Year::Mechanistic::Random"
        // with an empty `desc` while the real one sat on "Biochemistry".
        val deck = decks.keys().asSequence()
            .filter { it != DEFAULT_DECK_ID }
            .mapNotNull { decks.optJSONObject(it) }
            .filter { it.optString("name").isNotBlank() }
            .minByOrNull { it.optString("name").depth() }
            ?: return null
        AnkiDeck(
            name = deck.optString("name").rootDeckName(LEGACY_NESTING),
            description = deck.optString("desc").takeIf { it.isNotBlank() },
        )
    }

private fun String.depth(): Int = split(LEGACY_NESTING).size

/**
 * The `description` string out of a serialized `NormalDeck`, without a protobuf dependency.
 *
 * Schema 18 stores each deck's settings as a protobuf blob rather than JSON. Only one string is
 * wanted from it, and protobuf's wire format makes that a short scan: every field is a varint tag
 * naming its number and type, and a length-delimited one carries its own length. So walk the
 * fields, skip past anything that is not [DESCRIPTION_FIELD], and read that one as UTF-8. Anything
 * malformed returns null — this is a prefill, not a contract.
 */
private fun normalDeckDescription(blob: ByteArray): String? = runCatching {
    var offset = 0
    while (offset < blob.size) {
        val (tag, afterTag) = blob.readVarint(offset) ?: return null
        offset = afterTag
        val field = (tag ushr WIRE_TYPE_BITS).toInt()
        when ((tag and WIRE_TYPE_MASK).toInt()) {
            WIRE_VARINT -> offset = blob.readVarint(offset)?.second ?: return null
            WIRE_FIXED64 -> offset += Long.SIZE_BYTES
            WIRE_FIXED32 -> offset += Int.SIZE_BYTES
            WIRE_LENGTH_DELIMITED -> {
                val (length, afterLength) = blob.readVarint(offset) ?: return null
                val end = afterLength + length.toInt()
                if (end > blob.size) return null
                if (field == DESCRIPTION_FIELD) {
                    return blob.decodeToString(afterLength, end).takeIf { it.isNotBlank() }
                }
                offset = end
            }

            else -> return null
        }
    }
    null
}.getOrNull()

/** A protobuf varint at [start], and the offset just past it. */
private fun ByteArray.readVarint(start: Int): Pair<Long, Int>? {
    var value = 0L
    var shift = 0
    var offset = start
    while (offset < size && shift < Long.SIZE_BITS) {
        val byte = this[offset].toInt()
        value = value or ((byte and VARINT_PAYLOAD_MASK).toLong() shl shift)
        offset++
        if (byte and VARINT_CONTINUATION == 0) return value to offset
        shift += VARINT_PAYLOAD_BITS
    }
    return null
}

/**
 * Field names of the deck's note types, by ordinal, so the picker can name what it offers.
 *
 * Modern collections have a `fields` table; older ones keep note types as JSON in `col.models`.
 * When a deck mixes note types the names come from the one used by the most notes — the picker
 * describes the deck's shape, and a deck whose notes disagree about that has no single answer.
 */
private fun SQLiteDatabase.readFieldNames(): List<String> =
    runCatching { readModernFieldNames() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { readLegacyFieldNames() }.getOrNull().orEmpty()

private fun SQLiteDatabase.readModernFieldNames(): List<String> {
    val dominant = dominantNoteTypeId() ?: return emptyList()
    return rawQuery("SELECT name FROM fields WHERE ntid = ? ORDER BY ord", arrayOf(dominant)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
    }
}

private fun SQLiteDatabase.readLegacyFieldNames(): List<String> {
    val dominant = dominantNoteTypeId() ?: return emptyList()
    val models = rawQuery("SELECT models FROM col LIMIT 1", null).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: return emptyList()
    val fields = JSONObject(models).optJSONObject(dominant)?.optJSONArray("flds")
        ?: return emptyList()
    return (0 until fields.length()).map { fields.getJSONObject(it).optString("name") }
}

private fun SQLiteDatabase.dominantNoteTypeId(): String? =
    rawQuery("SELECT mid FROM notes GROUP BY mid ORDER BY COUNT(*) DESC LIMIT 1", null).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/**
 * The top of a nested deck's path, which is the deck the user thinks they are importing.
 *
 * Anki nests decks in the name itself — `Biochemistry::First Year::Mechanistic::Random` on older
 * collections, the same path joined by 0x1F on newer ones. Taking the leaf titles the deck
 * "Random"; taking the whole path puts the separator on the commit screen. The root is the one
 * part that names the deck.
 */
private fun String.rootDeckName(separator: String): String? =
    substringBefore(separator).trim().takeIf { it.isNotBlank() }

private const val LEGACY_NESTING = "::"

private const val DEFAULT_DECK_ID = "1"

/** Mirrors `PublishDeckViewModel`'s own ceiling, so the prefill never opens already invalid. */
private const val MAX_DESCRIPTION_CHARS = 500

/** `NormalDeck.description` in Anki's `deck_config.proto`. */
private const val DESCRIPTION_FIELD = 4

private const val WIRE_TYPE_BITS = 3
private const val WIRE_TYPE_MASK = 0x07L
private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_CONTINUATION = 0x80
