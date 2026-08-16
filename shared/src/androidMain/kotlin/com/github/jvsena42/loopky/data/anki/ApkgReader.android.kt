package com.github.jvsena42.loopky.data.anki

import android.database.sqlite.SQLiteDatabase
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Android `.apkg` reader built entirely on platform APIs — `java.util.zip` and
 * `android.database.sqlite` — so it adds no dependencies.
 */
actual object ApkgReader {

    actual fun canRead(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    actual suspend fun readNotes(bytes: ByteArray): Result<ApkgImport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val collection = extractCollection(bytes)
                    ?: error(
                        "That .apkg has no readable collection. If it was exported by Anki 2.1.50 " +
                            "or newer, re-export it as \"Notes in Plain Text\" instead.",
                    )
                try {
                    readCollection(collection)
                } finally {
                    collection.delete()
                }
            }
        }

    /**
     * Pull the SQLite collection out of the zip onto disk.
     *
     * SQLiteDatabase opens a path, not a stream, so the bytes have to land in a file. Prefers the
     * older uncompressed formats: `collection.anki21b` is zstd-compressed, which is the one thing
     * here that would need a real dependency, so it is skipped and reported rather than
     * half-handled.
     */
    private fun extractCollection(bytes: ByteArray): File? {
        val candidates = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in COLLECTION_NAMES) {
                    candidates[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val name = COLLECTION_NAMES.firstOrNull { it in candidates } ?: return null
        Log.d(TAG, "apkg: using $name")
        return File.createTempFile("loopky-apkg", ".sqlite").apply {
            writeBytes(candidates.getValue(name))
        }
    }

    private fun readCollection(file: File): ApkgImport {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return db.use {
            val lines = mutableListOf<String>()
            // `flds` holds the note's fields joined by 0x1F. One row per note, so a 20k-card deck
            // is one cursor walk rather than 20k reads.
            it.rawQuery("SELECT flds FROM notes", null).use { cursor ->
                while (cursor.moveToNext()) {
                    ankiNoteToLine(cursor.getString(0))?.let(lines::add)
                }
            }
            ApkgImport(
                deckName = readDeckName(it),
                text = lines.joinToString("\n"),
                noteCount = lines.size,
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
            if (c.moveToFirst()) c.getString(0)?.substringAfterLast("") else null
        }
    }.getOrNull()

    private const val TAG = "Loopky/ApkgReader"

    /** Newest-first would hit the zstd variant; these are the ones readable without a new dep. */
    private val COLLECTION_NAMES = listOf("collection.anki2", "collection.anki21")

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
