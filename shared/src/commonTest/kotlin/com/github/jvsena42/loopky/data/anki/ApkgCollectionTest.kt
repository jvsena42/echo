package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the collection reading that used to live in `androidMain` behind `SQLiteDatabase`, and so
 * could not be tested at all. [FakeAnkiDb] answers the same SQL a real collection would.
 */
class ApkgCollectionTest {

    @Test
    fun `reads notes and splits fields on the unit separator`() {
        val db = FakeAnkiDb(
            notes = listOf(row("hola${SEP}hello", "spanish greeting")),
        )
        val raw = db.readRawNotes()

        assertEquals(1, raw.rows.size)
        assertEquals(listOf("hola", "hello"), raw.rows[0].fields)
        assertEquals("spanish greeting", raw.rows[0].tags)
    }

    @Test
    fun `a collection of only Anki's compatibility stub is reported as such`() {
        val db = FakeAnkiDb(
            notes = listOf(row("Please update to the latest Anki version.$SEP", "")),
        )
        assertTrue(db.readRawNotes().stubOnly)
    }

    @Test
    fun `a real note alongside the stub is not a stub-only collection`() {
        val db = FakeAnkiDb(
            notes = listOf(
                row("Please update to the latest Anki version.$SEP", ""),
                row("hola${SEP}hello", ""),
            ),
        )
        assertTrue(!db.readRawNotes().stubOnly)
    }

    @Test
    fun `nested deck names keep the root rather than the leaf`() {
        // Anki nests in the name itself. The leaf titles the deck "Random"; the root is what the
        // user thinks they are importing.
        val db = FakeAnkiDb(
            notes = listOf(row("a${SEP}b", "")),
            decks = listOf(row("Biochemistry${SEP}First Year${SEP}Mechanistic${SEP}Random")),
        )
        assertEquals("Biochemistry", db.readRawNotes().deckName)
    }

    @Test
    fun `the legacy schema takes the shallowest deck rather than the first listed`() {
        // Only the root carries the description, and subdecks are stored in no useful order.
        val decks = """
            {
              "1": {"name": "Default", "desc": ""},
              "77": {"name": "Biochemistry::First Year::Random", "desc": ""},
              "42": {"name": "Biochemistry", "desc": "Everything for first year."}
            }
        """.trimIndent()
        val db = FakeAnkiDb(notes = listOf(row("a${SEP}b", "")), legacyDecks = decks)
        val raw = db.readRawNotes()

        assertEquals("Biochemistry", raw.deckName)
        assertEquals("Everything for first year.", raw.deckDescription)
    }

    @Test
    fun `field names come from the note type most notes use`() {
        val db = FakeAnkiDb(
            notes = listOf(row("a${SEP}b", "", mid = "2"), row("c${SEP}d", "", mid = "2"), row("e${SEP}f", "", mid = "9")),
            fieldsByNoteType = mapOf("2" to listOf("Front", "Back"), "9" to listOf("Term", "Reading")),
        )
        assertEquals(listOf("Front", "Back"), db.readRawNotes().fieldNamesByOrd)
    }

    @Test
    fun `two templates on the dominant note type marks the deck reversible`() {
        val db = FakeAnkiDb(
            notes = listOf(row("a${SEP}b", "", mid = "2")),
            templateCounts = mapOf("2" to 2),
        )
        assertTrue(db.readRawNotes().reversible)
    }

    @Test
    fun `one template is not reversible`() {
        val db = FakeAnkiDb(
            notes = listOf(row("a${SEP}b", "", mid = "2")),
            templateCounts = mapOf("2" to 1),
        )
        assertTrue(!db.readRawNotes().reversible)
    }

    @Test
    fun `field names are padded so the picker can label every column`() {
        val raw = FakeAnkiDb(
            notes = listOf(row("a${SEP}b${SEP}c", "")),
            fieldsByNoteType = mapOf("1" to listOf("Front")),
        ).readRawNotes()

        assertEquals(listOf("Front", "Field 2", "Field 3"), raw.fieldNames(3))
    }

    @Test
    fun `a field sample skips notes where the field is empty`() {
        // A field is often blank on the opening notes; an empty sample teaches the picker nothing.
        val raw = FakeAnkiDb(
            notes = listOf(row("a$SEP", ""), row("b${SEP}filled", "")),
        ).readRawNotes()

        assertEquals("filled", raw.fieldSamples(2)[1])
    }

    @Test
    fun `a protobuf deck blob yields its description`() {
        // field 4 (description), wire type 2 (length-delimited) -> tag byte 0x22.
        val text = "Everything for first year."
        val blob = byteArrayOf(0x22, text.length.toByte()) + text.encodeToByteArray()
        assertEquals(text, normalDeckDescription(blob))
    }

    @Test
    fun `a protobuf blob without a description yields null`() {
        // field 1, varint — anything but the description field is skipped.
        assertNull(normalDeckDescription(byteArrayOf(0x08, 0x01)))
    }

    @Test
    fun `a truncated protobuf blob yields null rather than throwing`() {
        // A prefill is not worth failing an import over.
        assertNull(normalDeckDescription(byteArrayOf(0x22, 0x7F)))
    }

    private fun row(vararg values: String?, mid: String = "1"): List<String?> =
        values.toList() + mid

    private companion object {
        const val SEP = ANKI_FIELD_SEPARATOR
    }
}

/**
 * An [AnkiDb] that answers the shared reader's SQL from in-memory tables, so the interpretation can
 * be tested without SQLite. Matches on the distinguishing fragment of each query rather than the
 * whole string, so reformatting the SQL does not silently stop a fake answering.
 */
private class FakeAnkiDb(
    private val notes: List<List<String?>> = emptyList(),
    private val decks: List<List<String?>>? = null,
    private val legacyDecks: String? = null,
    private val fieldsByNoteType: Map<String, List<String>> = emptyMap(),
    private val templateCounts: Map<String, Int> = emptyMap(),
) : AnkiDb {

    override fun query(sql: String, args: List<String>): List<AnkiRow> = when {
        sql.contains("FROM notes GROUP BY mid") -> dominantNoteType()
        sql.contains("FROM notes") -> notes.map { FakeRow(it) }
        sql.contains("FROM decks") -> decks?.map { FakeRow(it) } ?: error("no decks table")
        sql.contains("decks FROM col") -> legacyDecks?.let { listOf(FakeRow(listOf(it))) }.orEmpty()
        sql.contains("FROM fields") ->
            (fieldsByNoteType[args.firstOrNull()] ?: error("no fields table"))
                .map { FakeRow(listOf(it)) }
        sql.contains("COUNT(*) FROM templates") ->
            listOf(FakeIntRow(templateCounts[args.firstOrNull()] ?: 0))
        sql.contains("models FROM col") -> emptyList()
        else -> emptyList()
    }

    /** `mid` is the last column the fake rows carry; the dominant one is simply the commonest. */
    private fun dominantNoteType(): List<AnkiRow> =
        notes.mapNotNull { it.lastOrNull() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.let { listOf(FakeRow(listOf(it.key))) }
            .orEmpty()

    private class FakeRow(private val values: List<String?>) : AnkiRow {
        override fun text(index: Int): String? = values.getOrNull(index)
        override fun blob(index: Int): ByteArray? = null
        override fun int(index: Int): Int = values.getOrNull(index)?.toIntOrNull() ?: 0
    }

    private class FakeIntRow(private val value: Int) : AnkiRow {
        override fun text(index: Int): String? = value.toString()
        override fun blob(index: Int): ByteArray? = null
        override fun int(index: Int): Int = value
    }
}
