package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.platform.toByteArray
import com.github.jvsena42.loopky.platform.toNSData
import com.github.jvsena42.loopky.util.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS `.apkg` reader.
 *
 * Built on what the platform already ships, matching the "no new dependencies" rule the common
 * [ApkgReader] states: zip containers are parsed by [ZipReader] over Kotlin/Native's `zlib`
 * platform library, and the collection is read through a cinterop over the system `libsqlite3`
 * (`sqlite3.def`). Everything above that — which collection to prefer, how a note becomes cards,
 * where the deck name hides — is the shared code Android runs.
 *
 * `collection.anki21b` is still unsupported: it is zstd-compressed, and zstd is the one thing here
 * that would need a real dependency. It is reported rather than half-handled, exactly as on Android.
 */
@OptIn(ExperimentalForeignApi::class)
actual object ApkgReader {

    actual fun canRead(header: ByteArray): Boolean = ZipReader.isZip(header)

    actual suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> = withContext(Dispatchers.Default) {
        runCatching {
            // Mapped rather than copied: the archive can be hundreds of megabytes and only a few
            // entries are ever inflated, so this stays virtual memory rather than resident.
            val data = NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, null)
                ?: throw ApkgException(ApkgFailure.Unreadable, "That file could not be opened.")
            readArchive(ZipBytesArchive(data.toByteArray()), mapping, compressImage)
        }
    }

    private suspend fun readArchive(
        archive: ZipBytesArchive,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport {
        val present = COLLECTION_NAMES.filter(archive::contains)
        if (present.isEmpty()) {
            throw ApkgException(
                ApkgFailure.UnsupportedFormat,
                "That .apkg has no readable collection.",
            )
        }
        var sawStub = false
        present.forEach { name ->
            val import = readCollection(archive, name, mapping, compressImage) ?: return@forEach
            if (import.noteCount > 0) {
                Log.d(
                    TAG,
                    "apkg: using $name — ${import.noteCount} notes, ${import.notes.size} cards, " +
                        "${import.imagesImported} images, ${import.dropped.total} dropped",
                )
                return import
            }
            if (import.isLegacyStub) sawStub = true
            Log.d(TAG, "apkg: $name held nothing usable (stub=${import.isLegacyStub})")
        }
        if (sawStub) {
            throw ApkgException(
                ApkgFailure.LegacyStubOnly,
                "That .apkg holds only Anki's legacy compatibility stub.",
            )
        }
        return ApkgImport(deckName = null)
    }

    /**
     * SQLite opens a path, not a buffer, so the collection — and only the collection — is spooled
     * to a temp file. Media blobs stay in the archive until a card turns out to need one.
     */
    private suspend fun readCollection(
        archive: ZipBytesArchive,
        name: String,
        requested: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport? {
        val bytes = archive.read(name, ApkgLimits.MAX_COLLECTION_BYTES) ?: run {
            Log.d(TAG, "apkg: $name is absent or over ${ApkgLimits.MAX_COLLECTION_BYTES} bytes")
            return null
        }
        val file = NSTemporaryDirectory() + "loopky-apkg-${name.hashCode()}.sqlite"
        if (!bytes.toNSData().writeToFile(file, true)) return null
        try {
            val db = IosAnkiDb.open(file) ?: return null
            val raw = try { db.readRawNotes() } finally { db.close() }
            if (raw.rows.isEmpty()) {
                return ApkgImport(deckName = raw.deckName, isLegacyStub = raw.stubOnly)
            }
            return raw.toImport(archive, requested, compressImage)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(file, null)
        }
    }

    private suspend fun RawCollection.toImport(
        archive: ZipBytesArchive,
        requested: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): ApkgImport {
        val parsed = rows.map { row -> row.fields.map(::parseAnkiField) }
        val fieldCount = parsed.maxOf { it.size }
        val mapping = requested ?: chooseDefaultFields(parsed, fieldCount)

        val media = MediaIndex(archive)
        var dropped = ApkgDropped()
        val notes = mutableListOf<BulkNote>()
        // Only the notes that became cards suggest tags: a tag on a note this deck dropped
        // describes nothing the user is about to publish.
        val noteTags = mutableListOf<String>()
        parsed.forEachIndexed { index, fields ->
            val images = media.resolve(fields, mapping, compressImage)
            if (images == null) {
                dropped = dropped.copy(missingMedia = dropped.missingMedia + 1)
                return@forEachIndexed
            }
            val cards = ankiNoteToCards(fields, mapping, images)
            if (cards.isEmpty()) {
                dropped = dropped.tally(fields, mapping)
                return@forEachIndexed
            }
            notes += cards
            rows[index].tags.takeIf { it.isNotBlank() }?.let(noteTags::add)
        }

        return ApkgImport(
            deckName = deckName,
            deckDescription = deckDescription,
            suggestedTags = suggestDeckTags(noteTags),
            fieldNames = fieldNames(fieldCount),
            fieldSamples = fieldSamples(fieldCount),
            mapping = mapping,
            notes = notes,
            noteCount = rows.size,
            reversible = reversible,
            dropped = dropped,
            imagesImported = media.imported,
            imagesSkipped = media.skipped,
            isLegacyStub = stubOnly,
        )
    }

    /** Which of the two "this note had nothing to show" cases this was, so the summary can say. */
    private fun ApkgDropped.tally(fields: List<AnkiField>, mapping: ApkgFieldMapping): ApkgDropped {
        val front = fields.getOrNull(mapping.frontOrd)?.isEmpty != false
        val back = fields.getOrNull(mapping.backOrd)?.isEmpty != false
        return if (front && back) copy(empty = empty + 1) else copy(halfEmpty = halfEmpty + 1)
    }

    private const val TAG = "Loopky/ApkgReader"

    /**
     * Newest-first: a modern export's `collection.anki2` is Anki's legacy stub and the real deck
     * lives in `collection.anki21`. `collection.anki21b` is absent on purpose — it is zstd.
     */
    private val COLLECTION_NAMES = listOf("collection.anki21", "collection.anki2")
}
