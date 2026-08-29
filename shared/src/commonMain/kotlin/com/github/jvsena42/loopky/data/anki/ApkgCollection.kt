@file:Suppress("TooManyFunctions")
// The function count tracks the number of Anki schema variants this has to read — modern tables
// and legacy JSON, for decks, fields and templates — not complexity. Each one is a few lines.

package com.github.jvsena42.loopky.data.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads an Anki collection through whatever SQLite the platform has.
 *
 * The queries and every bit of interpretation live here rather than per platform. Android has
 * `android.database.sqlite` and iOS a cinterop over the system `libsqlite3`; what differs between
 * them is how a row is fetched, which is all [AnkiDb] abstracts. Everything downstream — which
 * schema a collection uses, where a description hides, which note type dominates — is one
 * implementation, and is now testable without a database.
 */
internal interface AnkiDb {
    fun query(sql: String, args: List<String> = emptyList()): List<AnkiRow>
}

internal interface AnkiRow {
    fun text(index: Int): String?
    fun blob(index: Int): ByteArray?
    fun int(index: Int): Int
}

/** One row of Anki's `notes` table, before any of it is interpreted. */
internal data class RawNote(val fields: List<String>, val tags: String)

/** Everything read straight out of a collection, before field mapping decides what to keep. */
internal data class RawCollection(
    val rows: List<RawNote>,
    val deckName: String?,
    val deckDescription: String?,
    val fieldNamesByOrd: List<String>,
    /**
     * The dominant note type generates more than one card per note — Anki's "Basic (and reversed
     * card)" and its relatives. Loopky still imports one card per note; what this carries is the
     * hint that the deck's author meant it to be drilled both ways.
     */
    val reversible: Boolean,
    /** Held notes, but every one of them was Anki's compatibility placeholder. */
    val stubOnly: Boolean,
) {
    /** Field names padded to [count], so the picker can always label every column it offers. */
    fun fieldNames(count: Int): List<String> =
        (0 until count).map { fieldNamesByOrd.getOrNull(it) ?: "Field ${it + 1}" }

    /**
     * The first value each field actually holds, padded to [count].
     *
     * Scans a bounded prefix rather than the first row alone: a field is often empty on the
     * opening notes and filled in later, and an empty sample teaches the picker nothing.
     */
    fun fieldSamples(count: Int): List<String> = (0 until count).map { ord ->
        rows.asSequence()
            .take(SAMPLE_SCAN_LIMIT)
            .mapNotNull { it.fields.getOrNull(ord)?.let(::parseAnkiField)?.text?.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }
}

internal fun AnkiDb.readRawNotes(): RawCollection {
    val rows = mutableListOf<RawNote>()
    var stubOnly = true
    // `flds` holds the note's fields joined by 0x1F. One row per note, so a 20k-card deck is one
    // query rather than 20k reads.
    query("SELECT flds, tags FROM notes").forEach { row ->
        val flds = row.text(0) ?: return@forEach
        if (!isLegacyStubNote(flds)) stubOnly = false
        rows += RawNote(fields = flds.split(ANKI_FIELD_SEPARATOR), tags = row.text(1).orEmpty())
    }
    val deck = readDeck()
    return RawCollection(
        rows = rows,
        deckName = deck?.name,
        deckDescription = deck?.description?.let(::readableDescription),
        fieldNamesByOrd = readFieldNames(),
        reversible = readTemplateCount() >= REVERSIBLE_TEMPLATE_COUNT,
        stubOnly = rows.isNotEmpty() && stubOnly,
    )
}

private fun readableDescription(raw: String): String? =
    parseAnkiField(raw).text.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION_CHARS)

internal data class AnkiDeck(val name: String?, val description: String?)

/**
 * Name and description of the deck, used only to prefill the commit screen.
 *
 * Two schemas, and the description hides in a different place in each. Modern collections keep one
 * row per deck in a `decks` table, with the description inside a protobuf `kind` blob; older ones
 * keep every deck as JSON in `col.decks`. Both are best-effort — a default the user can edit is not
 * worth failing an import over — so anything unexpected gives up quietly and leaves the field blank.
 */
private fun AnkiDb.readDeck(): AnkiDeck? =
    runCatching { readModernDeck() }.getOrNull()
        ?: runCatching { readLegacyDeck() }.getOrNull()

private fun AnkiDb.readModernDeck(): AnkiDeck? {
    val row = query("SELECT name, kind FROM decks WHERE id != 1 ORDER BY id LIMIT 1").firstOrNull()
        ?: return null
    return AnkiDeck(
        name = row.text(0)?.rootDeckName(ANKI_FIELD_SEPARATOR.toString()),
        description = row.blob(1)?.let(::normalDeckDescription),
    )
}

private fun AnkiDb.readLegacyDeck(): AnkiDeck? {
    val raw = query("SELECT decks FROM col LIMIT 1").firstOrNull()?.text(0) ?: return null
    val decks = ankiJson.parseToJsonElement(raw).jsonObject
    // The *shallowest* deck, not the first one listed. A deck with subdecks stores one JSON object
    // per subdeck, in no useful order, and only the root carries the description — taking whichever
    // came first landed on "Biochemistry::First Year::Mechanistic::Random" with an empty `desc`
    // while the real one sat on "Biochemistry".
    val deck = decks.entries.asSequence()
        .filter { it.key != DEFAULT_DECK_ID }
        .mapNotNull { runCatching { it.value.jsonObject }.getOrNull() }
        .filter { it.stringOrEmpty("name").isNotBlank() }
        .minByOrNull { it.stringOrEmpty("name").depth() }
        ?: return null
    return AnkiDeck(
        name = deck.stringOrEmpty("name").rootDeckName(LEGACY_NESTING),
        description = deck.stringOrEmpty("desc").takeIf { it.isNotBlank() },
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
internal fun normalDeckDescription(blob: ByteArray): String? = runCatching {
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
private fun AnkiDb.readFieldNames(): List<String> =
    runCatching { readModernFieldNames() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { readLegacyFieldNames() }.getOrNull().orEmpty()

private fun AnkiDb.readModernFieldNames(): List<String> {
    val dominant = dominantNoteTypeId() ?: return emptyList()
    return query("SELECT name FROM fields WHERE ntid = ? ORDER BY ord", listOf(dominant))
        .map { it.text(0).orEmpty() }
}

private fun AnkiDb.readLegacyFieldNames(): List<String> {
    val dominant = dominantNoteTypeId() ?: return emptyList()
    val models = query("SELECT models FROM col LIMIT 1").firstOrNull()?.text(0) ?: return emptyList()
    val fields = ankiJson.parseToJsonElement(models).jsonObject[dominant]
        ?.jsonObject?.get("flds")?.jsonArray ?: return emptyList()
    return fields.map { it.jsonObject.stringOrEmpty("name") }
}

/**
 * How many cards the deck's dominant note type generates per note.
 *
 * The same two schemas as [readFieldNames], read the same way round: a `templates` table on modern
 * collections, the `tmpls` array of `col.models` on older ones. Two or more means a reverse (or
 * some other second card), which is all the import needs to know — Loopky reads the two mapped
 * fields either way, and the count only decides how the deck arrives set up to be studied.
 *
 * Zero on anything unreadable, which lands on "not reversible": a deck that arrives without the
 * opt-in has a toggle on the publish screen, while one that arrives with it wrongly on does not
 * announce itself.
 */
private fun AnkiDb.readTemplateCount(): Int {
    val dominant = dominantNoteTypeId() ?: return 0
    val modern = runCatching {
        query("SELECT COUNT(*) FROM templates WHERE ntid = ?", listOf(dominant))
            .firstOrNull()?.int(0) ?: 0
    }.getOrNull() ?: 0
    if (modern > 0) return modern
    return runCatching {
        val models = query("SELECT models FROM col LIMIT 1").firstOrNull()?.text(0) ?: return 0
        ankiJson.parseToJsonElement(models).jsonObject[dominant]
            ?.jsonObject?.get("tmpls")?.jsonArray?.size ?: 0
    }.getOrNull() ?: 0
}

private fun AnkiDb.dominantNoteTypeId(): String? =
    query("SELECT mid FROM notes GROUP BY mid ORDER BY COUNT(*) DESC LIMIT 1")
        .firstOrNull()?.text(0)

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

private fun kotlinx.serialization.json.JsonObject.stringOrEmpty(key: String): String =
    runCatching { get(key)?.jsonPrimitive?.content }.getOrNull().orEmpty()

/** Anki's JSON is written by Anki, not by us — unknown keys are the norm. */
private val ankiJson = Json { ignoreUnknownKeys = true; isLenient = true }

private const val LEGACY_NESTING = "::"

private const val DEFAULT_DECK_ID = "1"

/** Mirrors `PublishDeckViewModel`'s own ceiling, so the prefill never opens already invalid. */
private const val MAX_DESCRIPTION_CHARS = 500

/** Rows to look through for a non-empty sample of each field. */
private const val SAMPLE_SCAN_LIMIT = 50

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

/** Templates per note type at which a deck is taken to be meant for both directions. */
private const val REVERSIBLE_TEMPLATE_COUNT = 2
