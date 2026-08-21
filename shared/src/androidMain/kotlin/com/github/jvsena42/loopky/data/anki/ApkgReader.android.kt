package com.github.jvsena42.loopky.data.anki

import android.database.sqlite.SQLiteDatabase
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Android `.apkg` reader built entirely on platform APIs — `java.util.zip` and
 * `android.database.sqlite` — so it adds no dependencies.
 */
actual object ApkgReader {

    actual fun canRead(header: ByteArray): Boolean =
        header.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { header[it] == ZIP_MAGIC[it] }

    actual suspend fun readNotes(path: String): Result<ApkgImport> =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(path).use { zip -> readArchive(zip) }
            }
        }

    private fun readArchive(zip: ZipFile): ApkgImport {
        val candidates = extractCollections(zip)
        if (candidates.isEmpty()) {
            throw ApkgException(
                ApkgFailure.UnsupportedFormat,
                "That .apkg has no readable collection.",
            )
        }
        return try {
            readFirstUsable(candidates)
        } finally {
            candidates.forEach { it.file.delete() }
        }
    }

    /**
     * The first candidate that actually holds a deck.
     *
     * Since Anki 2.1.50 an export ships a **legacy stub** `collection.anki2` beside the real
     * `collection.anki21`, holding one note that reads "Please update to the latest Anki version".
     * [COLLECTION_NAMES] prefers the newer file, but a stub can still be all there is — either
     * because the export is odd, or because the real collection is the zstd variant this build
     * skips. Falling through the candidates rather than committing to the first is what keeps a
     * strange export from being reported as an empty one.
     */
    private fun readFirstUsable(candidates: List<Candidate>): ApkgImport {
        var sawStub = false
        candidates.forEach { candidate ->
            val import = readCollection(candidate.file)
            if (import.noteCount > 0) {
                Log.d(TAG, "apkg: using ${candidate.name} (${import.noteCount} notes)")
                return import
            }
            if (import.isLegacyStub) sawStub = true
            Log.d(TAG, "apkg: ${candidate.name} held nothing usable (stub=${import.isLegacyStub})")
        }
        if (sawStub) {
            throw ApkgException(
                ApkgFailure.LegacyStubOnly,
                "That .apkg holds only Anki's legacy compatibility stub.",
            )
        }
        return ApkgImport(deckName = null, text = "", noteCount = 0)
    }

    /**
     * Pull every readable SQLite collection out of the zip onto disk, in preference order.
     *
     * SQLiteDatabase opens a path, not a stream, so the bytes have to land in a file.
     * `collection.anki21b` is zstd-compressed, which is the one thing here that would need a real
     * dependency, so it is skipped and reported rather than half-handled.
     */
    private fun extractCollections(zip: ZipFile): List<Candidate> =
        COLLECTION_NAMES.mapNotNull { name ->
            val entry = zip.getEntry(name) ?: return@mapNotNull null
            val file = File.createTempFile("loopky-apkg", ".sqlite")
            zip.getInputStream(entry).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Candidate(name = name, file = file)
        }

    private data class Candidate(val name: String, val file: File)

    private fun readCollection(file: File): ApkgImport {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return db.use {
            val lines = mutableListOf<String>()
            var noteRows = 0
            var stubOnly = true
            // `flds` holds the note's fields joined by 0x1F. One row per note, so a 20k-card deck
            // is one cursor walk rather than 20k reads.
            it.rawQuery("SELECT flds FROM notes", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val flds = cursor.getString(0) ?: continue
                    noteRows++
                    if (!isLegacyStubNote(flds)) stubOnly = false
                    ankiNoteToLine(flds)?.let(lines::add)
                }
            }
            ApkgImport(
                deckName = readDeckName(it),
                text = lines.joinToString("\n"),
                noteCount = lines.size,
                isLegacyStub = noteRows > 0 && stubOnly,
            )
        }
    }

    /**
     * Best-effort deck name, used only to prefill the title field.
     *
     * The `decks` table exists on newer schemas; older collections keep decks as JSON in `col`.
     * Not worth parsing that for a default the user can edit, so this gives up quietly.
     */
    private fun readDeckName(db: SQLiteDatabase): String? = runCatching {
        db.rawQuery("SELECT name FROM decks WHERE id != 1 ORDER BY id LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringAfterLast(ANKI_FIELD_SEPARATOR) else null
        }
    }.getOrNull()

    private const val TAG = "Loopky/ApkgReader"

    /**
     * Newest-first, because a modern export's `collection.anki2` is a stub (see [readFirstUsable])
     * and the real deck lives in `collection.anki21`. Oldest-first is how every current AnkiWeb
     * deck came to import as "no cards". `collection.anki21b` is absent on purpose: it is zstd, the
     * one variant that would need a new dependency.
     */
    private val COLLECTION_NAMES = listOf("collection.anki21", "collection.anki2")

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
