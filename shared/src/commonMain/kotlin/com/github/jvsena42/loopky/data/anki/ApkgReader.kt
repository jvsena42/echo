package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage

/**
 * Reads an Anki `.apkg` export into the notes the existing import pipeline can commit.
 *
 * An `.apkg` is a zip holding a SQLite collection (`collection.anki2`, or `collection.anki21` /
 * `collection.anki21b` on newer Anki), a media manifest, and numbered blobs. The notes live in one
 * table with their fields joined by the ASCII unit separator.
 *
 * This used to hand back one tab-separated `String`, so that the paste parser could be reused
 * verbatim. That reuse was worth having and is kept — the notes still go through the same dedupe,
 * the same caps and the same commit screen — but the flattening had to go. A `.apkg` is a
 * structured store of typed notes with named fields and media blobs, and three of the things it
 * gets wrong (#96: junk field choices, uncounted dropped notes, discarded images) cannot be fixed
 * downstream of a string that has already thrown all three away.
 *
 * **No new dependencies.** Android has `java.util.zip` and `android.database.sqlite` in the
 * platform. That leaves only `collection.anki21b`, which is zstd-compressed and is reported as
 * unsupported with a pointer at Anki's plain-text export.
 */
expect object ApkgReader {
    /** True if [header] — the first handful of bytes of a file — looks like a zip. */
    fun canRead(header: ByteArray): Boolean

    /**
     * Read the deck at [path].
     *
     * [mapping] names the two fields to import; null asks for `chooseDefaultFields`. Re-reading
     * with an explicit mapping is how the field picker works — a second pass over an already
     * spooled file, not a second pipeline.
     *
     * [compressImage] is how a side's picture gets shrunk on its way through. It is a parameter
     * rather than an injected `MediaProcessor` because this is an `object`, outside the Koin graph;
     * passing it also means only one raw blob is ever in memory, instead of every image in the deck.
     */
    suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping? = null,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport>
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

/** Which two of a note type's fields become the card's front and back. */
data class ApkgFieldMapping(val frontOrd: Int, val backOrd: Int)

internal data class AnkiField(
    /** The field as readable text. May contain newlines — a card side is not one line. */
    val text: String,
    /**
     * The `src` of this field's image, set only when the field is **nothing but** that image.
     *
     * Restricted to the sole-image case on purpose: that is the shape where dropping the picture
     * loses the whole card, and where putting it on the side is unambiguous. A field mixing prose
     * and figures needs a layout decision this importer has no way to make.
     */
    val imageSrc: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && imageSrc == null
}

/** One card produced by expanding a cloze deletion. See `expandCloze`. */
internal data class ClozeCard(val front: String, val back: String)

/** What was recovered from an `.apkg`. */
data class ApkgImport(
    val deckName: String?,
    /** Anki's own deck description, to prefill the commit screen. Best-effort; often absent. */
    val deckDescription: String? = null,
    /** Labels for the commit screen's chips, derived from note tags. See `suggestDeckTags`. */
    val suggestedTags: List<String> = emptyList(),
    /** Field names of the deck's dominant note type, for the field picker. */
    val fieldNames: List<String> = emptyList(),
    /**
     * One real value per field, parallel to [fieldNames], for the field picker to show beside
     * each name. Anki decks routinely carry fields called "Field 3" or nothing at all, and a name
     * alone makes choosing between them guesswork.
     */
    val fieldSamples: List<String> = emptyList(),
    /** The two fields these [notes] were built from. */
    val mapping: ApkgFieldMapping = ApkgFieldMapping(0, 1),
    val notes: List<BulkNote> = emptyList(),
    /** Notes read from the collection, before any were dropped or a cloze note expanded. */
    val noteCount: Int = 0,
    val dropped: ApkgDropped = ApkgDropped(),
    /**
     * The deck's dominant note type generates more than one card per note, i.e. it is a reversed
     * note type. Loopky still emits one card per note — a reverse is a way of studying a card, not
     * a second card — so what this does is arrive the publish screen with the both-directions
     * opt-in already on, as a suggestion the user can turn off.
     */
    val reversible: Boolean = false,
    /** Distinct pictures pulled out of the archive and attached to a card side. */
    val imagesImported: Int = 0,
    /** Pictures left behind at the importer's per-deck ceiling. Reported, never silent. */
    val imagesSkipped: Int = 0,
    /**
     * True when the collection read held notes but every one of them was Anki's compatibility
     * placeholder. Distinguishes "this file has no deck in it" from "this file's deck is somewhere
     * this build didn't look".
     */
    val isLegacyStub: Boolean = false,
)

/**
 * Notes that never became cards.
 *
 * Reported rather than merely subtracted: 1,458 notes going in and 1,338 cards coming out used to
 * be explained by "1 duplicates merged", because the other 119 were dropped inside this reader and
 * every count downstream was computed from rows that no longer existed (#96).
 */
data class ApkgDropped(
    /** Neither chosen field held anything — the note has no side to show. */
    val empty: Int = 0,
    /** One side was there, the other was not; a card needs both. */
    val halfEmpty: Int = 0,
    /** A picture this reader could not find among the archive's media blobs. */
    val missingMedia: Int = 0,
) {
    val total: Int get() = empty + halfEmpty + missingMedia
}

/**
 * One note on its way to becoming a card: two sides of text, each optionally a picture.
 *
 * The structured counterpart of the `front\tback` line this reader used to emit, and the shape
 * `ImportRepository.parseBulkNotes` takes.
 */
data class BulkNote(
    val front: String,
    val back: String,
    val frontImage: DraftCardImage? = null,
    val backImage: DraftCardImage? = null,
    /**
     * Distinguishes two notes whose text matches but whose pictures do not, so dedupe collapses
     * only genuine duplicates. Anki's media filenames, when there are any.
     */
    val imageKey: String? = null,
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
internal const val ANKI_FIELD_SEPARATOR = '\u001F'

/**
 * Turn one Anki note into the cards it should become.
 *
 * A cloze note expands to one card per deletion; every other note yields at most one. Returns empty
 * when a side is missing, which is the caller's cue to count the note as dropped and say so.
 */
internal fun ankiNoteToCards(
    fields: List<AnkiField>,
    mapping: ApkgFieldMapping,
    images: Map<Int, DraftCardImage> = emptyMap(),
): List<BulkNote> {
    val front = fields.getOrNull(mapping.frontOrd) ?: AnkiField("")
    val back = fields.getOrNull(mapping.backOrd) ?: AnkiField("")

    // Cloze first: a cloze note's second field is Anki's Extra, not the answer, so the ordinary
    // front/back reading of it is wrong before the markup is even considered.
    val cloze = expandCloze(front.text, back.text)
    if (cloze.isNotEmpty()) {
        return cloze.map { BulkNote(front = it.front, back = it.back) }
    }

    val frontImage = images[mapping.frontOrd]
    val backImage = images[mapping.backOrd]
    if (front.text.isBlank() && frontImage == null) return emptyList()
    if (back.text.isBlank() && backImage == null) return emptyList()

    return listOf(
        BulkNote(
            front = front.text,
            back = back.text,
            frontImage = frontImage,
            backImage = backImage,
            imageKey = listOfNotNull(front.imageSrc, back.imageSrc)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("|"),
        ),
    )
}
