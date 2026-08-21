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
    /** True if [header] — the first handful of bytes of a file — looks like a zip. */
    fun canRead(header: ByteArray): Boolean

    /**
     * Extract notes as tab-separated `front\tback` lines, ready for
     * [com.github.jvsena42.loopky.data.repository.ImportRepository.parseBulk].
     *
     * Takes a **path** rather than bytes: an `.apkg`'s collection is a small fraction of the
     * archive, the rest being media this reader does not want, so holding the whole file in memory
     * to read part of it capped the flow far below the size of a real Anki deck (#96).
     */
    suspend fun readNotes(path: String): Result<ApkgImport>
}

/**
 * Why an `.apkg` could not be read, in terms the UI can speak about.
 *
 * The reader used to throw plain `error(...)`s, which the ViewModel tagged with a single constant —
 * so a zstd collection, a corrupt zip and an Anki-2.0 export all surfaced as "Loopky can't open this
 * .apkg". They need different advice, so the reason travels with the failure.
 */
enum class ApkgFailure {
    /** A real `.apkg`, but in a format this build can't unpack (zstd `collection.anki21b`). */
    UnsupportedFormat,

    /**
     * The only collection in the file is the legacy stub a modern Anki ships for backwards
     * compatibility — one note reading "Please update to the latest Anki version". There is no deck
     * in here to find, and telling the user "no cards" sends them looking for one.
     */
    LegacyStubOnly,

    /** The zip or the SQLite inside it would not open at all. */
    Unreadable,
}

/** An `.apkg` read that failed for a reason worth reporting differently. See [ApkgFailure]. */
class ApkgException(val reason: ApkgFailure, message: String) : Exception(message)

/** What was recovered from an `.apkg`. [text] is tab-separated, one note per line. */
data class ApkgImport(
    val deckName: String?,
    val text: String,
    val noteCount: Int,
    /**
     * True when the collection read held notes but every one of them was Anki's compatibility
     * placeholder. Distinguishes "this file has no deck in it" from "this file's deck is somewhere
     * this build didn't look".
     */
    val isLegacyStub: Boolean = false,
)

/**
 * Anki's backwards-compatibility placeholder note, shipped in the legacy `collection.anki2` of
 * every export since 2.1.50.
 *
 * Matched on a prefix rather than the exact string: the sentence has been reworded between Anki
 * releases and carries a trailing field separator, but it has always opened this way.
 */
internal fun isLegacyStubNote(flds: String): Boolean =
    flds.trimStart().startsWith(LEGACY_STUB_PREFIX, ignoreCase = true)

private const val LEGACY_STUB_PREFIX = "Please update to the latest Anki version"

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
