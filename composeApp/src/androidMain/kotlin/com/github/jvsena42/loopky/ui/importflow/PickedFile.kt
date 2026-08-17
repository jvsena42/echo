package com.github.jvsena42.loopky.ui.importflow

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the picker recovered from a `content://` uri. */
internal data class PickedFile(val name: String, val bytes: ByteArray) {
    // ByteArray gives identity equality, which is wrong for a data class. Only `name` is ever
    // compared, and the payload can be megabytes — not something to walk on every ==.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = name.hashCode()
}

/** A read that failed in a way the user can act on, rather than a raw exception message. */
internal class FileReadException(val reason: BulkImportError) : Exception(reason.name)

/**
 * Ceiling on a picked file, as a memory backstop rather than a policy.
 *
 * The policy is the parser's own `MAX_BULK_CHARS` (10M), which reports truncation. This exists
 * because the file lands in memory three times over — bytes, then String, then parsed rows — so an
 * unbounded read is an OOM waiting for a big enough `.apkg`. A 20k-card Anki text export is ~2 MB,
 * so 32 MB leaves ~15x headroom for anything plausible while bounding peak heap.
 */
internal const val MAX_IMPORT_FILE_BYTES = 32L * 1024 * 1024

/**
 * Reads [uri] off the main thread.
 *
 * The read used to run in the picker callback, so a multi-MB `.apkg` blocked the UI for its whole
 * duration — worst exactly when the file is big enough to matter.
 */
internal suspend fun ContentResolver.readPickedFile(uri: Uri): Result<PickedFile> =
    withContext(Dispatchers.IO) {
        runCatching {
            // Checked before reading so an oversized file costs a cursor query, not 200 MB.
            val declared = queryLong(uri, OpenableColumns.SIZE)
            if (declared != null && declared > MAX_IMPORT_FILE_BYTES) {
                throw FileReadException(BulkImportError.TooLarge)
            }

            val bytes = openInputStream(uri)?.use { it.readBytes() }
                ?: throw FileReadException(BulkImportError.Unreadable)

            // Providers are not obliged to report SIZE, so re-check what actually arrived.
            if (bytes.size > MAX_IMPORT_FILE_BYTES) throw FileReadException(BulkImportError.TooLarge)

            PickedFile(name = displayName(uri) ?: uri.fallbackName(), bytes = bytes)
        }
    }

/**
 * The real file name.
 *
 * `uri.lastPathSegment` is frequently an opaque document id for a content-provider uri
 * (`msf:1000000123`), which is what the screen used to show and what the deck title fell back to.
 */
private fun ContentResolver.displayName(uri: Uri): String? =
    queryString(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }

/** Last resort when the provider is not openable and reports no columns at all. */
private fun Uri.fallbackName(): String = lastPathSegment?.substringAfterLast('/').orEmpty()

private fun ContentResolver.queryString(uri: Uri, column: String): String? =
    queryColumn(uri, column) { cursor, index -> cursor.getString(index) }

private fun ContentResolver.queryLong(uri: Uri, column: String): Long? =
    queryColumn(uri, column) { cursor, index -> cursor.getLong(index) }

/**
 * A non-openable provider returns a null cursor or omits the column entirely, so every step here
 * has to tolerate absence rather than assume the OpenableColumns contract holds.
 */
private fun <T> ContentResolver.queryColumn(
    uri: Uri,
    column: String,
    read: (android.database.Cursor, Int) -> T,
): T? = runCatching {
    query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(column)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) read(cursor, index) else null
    }
}.getOrNull()
