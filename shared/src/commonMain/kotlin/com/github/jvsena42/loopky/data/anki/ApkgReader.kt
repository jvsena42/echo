package com.github.jvsena42.loopky.data.anki

/**
 * Reads an Anki `.apkg` export into plain text the existing import pipeline can parse.
 *
 * An `.apkg` is a zip holding a SQLite collection (`collection.anki2`, or `collection.anki21` /
 * `collection.anki21b` on newer Anki), a media manifest, and numbered blobs. The notes live in one
 * table with their fields joined by the ASCII unit separator, which is why this can hand back
 * tab-separated text and reuse the paste parser rather than growing a second one.
 *
 * **No new dependencies.** The plan for this originally assumed a KMP zip reader, a SQLite driver
 * and zstd — three dependencies for content already reachable as text. Android has `java.util.zip`
 * and `android.database.sqlite` in the platform, so the Android implementation needs none of them.
 * That leaves only `collection.anki21b`, which is zstd-compressed and is reported as unsupported
 * with a pointer at Anki's plain-text export.
 */
expect object ApkgReader {
    /** True if [bytes] looks like a zip, i.e. plausibly an `.apkg`. */
    fun canRead(bytes: ByteArray): Boolean

    /**
     * Extract notes as tab-separated `front\tback` lines, ready for
     * [com.github.jvsena42.loopky.data.repository.ImportRepository.parseBulk].
     */
    suspend fun readNotes(bytes: ByteArray): Result<ApkgImport>
}

/** What was recovered from an `.apkg`. [text] is tab-separated, one note per line. */
data class ApkgImport(
    val deckName: String?,
    val text: String,
    val noteCount: Int,
)

/** Anki joins a note's fields with the ASCII unit separator (0x1F). */
internal const val ANKI_FIELD_SEPARATOR = ''

/**
 * Turn one Anki note's `flds` value into a `front\tback` line, or null if it has no usable pair.
 *
 * Anki notes can carry many fields and HTML; a Loopky card has exactly two plain-text sides, so
 * the first two fields are used and the rest dropped — the same rule the paste importer applies to
 * extra columns (spec §8). Rich text and the Note→Card split are #46, deliberately not here.
 */
internal fun ankiNoteToLine(flds: String): String? {
    val fields = flds.split(ANKI_FIELD_SEPARATOR).map { it.stripAnkiHtml() }
    val front = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    val back = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    // Tabs and newlines inside a field would break the line-per-note shape the parser expects.
    return "${front.flatten()}\t${back.flatten()}"
}

private fun String.flatten(): String =
    replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

/**
 * Strip the HTML Anki stores in note fields down to readable text.
 *
 * Deliberately minimal: enough that a card reads correctly rather than showing `<div>` noise.
 * Real rich-text fidelity is #46.
 */
internal fun String.stripAnkiHtml(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        // Adjacent tags each leave a space behind, so collapse runs rather than shipping
        // double-spaced card text.
        .replace(Regex("\\s+"), " ")
        .trim()
