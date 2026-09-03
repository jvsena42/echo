package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A batch of cards, from a file — the shape both `card add --from-file` and `card edit
 * --from-file` take.
 *
 * **Batch mutation is a v1 requirement, not a convenience.** One card write is one chunk
 * read-modify-write plus a manifest patch plus a `/session` round trip (#105); 190 image
 * attachments done one at a time cost ~30–40 s each end to end. A surface offering only
 * `card edit <deckId> <cardId>` gets used in a loop, and editing is precisely what an agent does
 * *after* an import.
 *
 * Two formats, and the choice is not stylistic:
 *
 * - **TSV**, `front <TAB> back <TAB> front_image_url <TAB> back_image_url`, the last two optional.
 *   Image columns are here from day one because since #167 a card image can be a **remote ref** —
 *   a URL, no bytes on the wire, no media quota spent — which is the gap neither `.apkg` nor
 *   TSV-through-the-paste-parser can express: both carry a picture only when a field is *nothing
 *   but* that image, so "this side has text and a picture" had no representation at all.
 * - **JSONL**, one object per line, for the cases TSV cannot hold: a side containing a tab or a
 *   newline, and — for `card edit` — naming which card to change and which fields to leave alone.
 *
 * The format is chosen by extension, then by content, never by a flag: a caller that has to
 * remember to pass `--jsonl` will one day not, and a JSON object read as TSV is a card whose front
 * is `{"id":`.
 */
@Serializable
data class CardFileRow(
    /** Only for `card edit`; ignored on add, where every row is a new card. */
    val id: String? = null,
    val front: String? = null,
    val back: String? = null,
    @SerialName("front_image_url") val frontImageUrl: String? = null,
    @SerialName("back_image_url") val backImageUrl: String? = null,
) {
    val isEmpty: Boolean
        get() = front.isNullOrBlank() && back.isNullOrBlank() &&
            frontImageUrl.isNullOrBlank() && backImageUrl.isNullOrBlank()

    /**
     * A row that could become a card: something on **both** sides, text or a picture.
     *
     * False is not the same as [isEmpty]. An edit row is allowed to carry one side and mean "leave
     * the other alone", so this is asked only where a row becomes a *new* card.
     */
    val hasBothSides: Boolean
        get() = !(front.isNullOrBlank() && frontImageUrl.isNullOrBlank()) &&
            !(back.isNullOrBlank() && backImageUrl.isNullOrBlank())

    fun toCard(deckId: String, now: Long, index: Int): Card = Card(
        id = generateId(),
        deckId = deckId,
        updatedAt = now,
        front = CardSide(
            text = front?.takeIf { it.isNotBlank() },
            imageRef = frontImageUrl?.takeIf { it.isNotBlank() }?.let(::remoteImage),
        ),
        back = CardSide(
            text = back?.takeIf { it.isNotBlank() },
            imageRef = backImageUrl?.takeIf { it.isNotBlank() }?.let(::remoteImage),
        ),
        ord = ordForIndex(index),
    )
}

private val cardFileJson = Json { ignoreUnknownKeys = true }

/**
 * Read [path] — or stdin, when it is `-` — as a batch of cards.
 *
 * Blank lines are skipped and `# ` comments are honoured in TSV, so a generated file can carry a
 * header without a phantom first card — `#` **followed by whitespace**, so a card whose front is
 * `#1 ranked` survives.
 *
 * A row with nothing on either side is an error rather than a silent skip, and so is an image
 * column holding something that is not a URL: a file that produced fewer cards than it has lines,
 * or a card carrying prose where a picture should be, is exactly the kind of loss `--json` exists
 * to make visible.
 */
fun readCardFile(path: String): List<CardFileRow> {
    val text = if (path == "-") {
        System.`in`.readBytes().toString(Charsets.UTF_8)
    } else {
        val file = File(path)
        if (!file.isFile) throw CliError(ExitCode.BadInput, "No such file: $path")
        file.readText(Charsets.UTF_8)
    }
    val rows = if (looksLikeJsonl(path, text)) parseJsonl(text) else parseTsv(text)
    if (rows.isEmpty()) throw CliError(ExitCode.BadInput, "$path held no cards.")
    return rows
}

private fun looksLikeJsonl(path: String, text: String): Boolean =
    path.endsWith(".jsonl", ignoreCase = true) ||
        path.endsWith(".ndjson", ignoreCase = true) ||
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()?.startsWith("{") == true

private fun parseJsonl(text: String): List<CardFileRow> =
    text.lineSequence()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() }
        .map { (index, line) ->
            runCatching { cardFileJson.decodeFromString<CardFileRow>(line) }.getOrElse {
                throw CliError(ExitCode.BadInput, "Line ${index + 1} is not a card object: ${it.message}")
            }
        }
        .onEach { row ->
            if (row.isEmpty) throw CliError(ExitCode.BadInput, "A row has neither text nor an image.")
        }
        .toList()

private fun parseTsv(text: String): List<CardFileRow> =
    text.lineSequence()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() && !line.isComment() }
        .map { (index, line) ->
            // `split`, not a CSV reader: the separator is a tab, which is the one character a card
            // side reliably does not contain, and quoting rules would only add a way to get it
            // wrong. A side that needs a tab or a newline uses JSONL.
            val fields = line.split('\t')
            CardFileRow(
                front = fields.getOrNull(FRONT_COLUMN)?.trim(),
                back = fields.getOrNull(BACK_COLUMN)?.trim(),
                frontImageUrl = fields.imageUrlAt(FRONT_IMAGE_COLUMN, index),
                backImageUrl = fields.imageUrlAt(BACK_IMAGE_COLUMN, index),
            ).also {
                if (it.isEmpty) {
                    throw CliError(ExitCode.BadInput, "Line ${index + 1} has neither text nor an image.")
                }
            }
        }
        .toList()

/** The four TSV columns, in the order the format documents them. */
private const val FRONT_COLUMN = 0
private const val BACK_COLUMN = 1
private const val FRONT_IMAGE_COLUMN = 2
private const val BACK_IMAGE_COLUMN = 3

/**
 * Refuse a batch that holds a card with nothing on one of its sides.
 *
 * Checked here rather than left to `publish`, which `require`s the same thing: that throws an
 * `IllegalArgumentException` no classifier recognises, so it would reach the user as exit 1
 * "internal" plus a Kotlin assertion message — for a blank column in their own file. The import
 * path needs none of this, because the shared parser already drops half-empty rows.
 *
 * The message names the row, since the whole point of a batch is that nobody is reading it line by
 * line.
 */
internal fun List<CardFileRow>.requireBothSides(): List<CardFileRow> = onEachIndexed { index, row ->
    if (!row.hasBothSides) {
        throw CliError(
            ExitCode.BadInput,
            "Row ${index + 1} has nothing on one side; a card needs a front and a back. " +
                "An image counts as a side.",
        )
    }
}

/**
 * A comment line: `#` followed by whitespace, never a bare `#`.
 *
 * The space is load-bearing. `startsWith("#")` alone silently swallows a card whose front is
 * `#1 ranked`, a markdown heading or a hashtag — and this file's own contract is that a batch
 * producing fewer cards than it has lines is exactly the loss `--json` exists to make visible.
 */
private fun String.isComment(): Boolean =
    startsWith("#") && (length == 1 || this[1].isWhitespace())

/**
 * The image column at [column], refused if it is not a URL.
 *
 * Every app-side constructor of a remote `MediaRef.Image` gets its URL from a picker; this is the
 * first path where an arbitrary string reaches one. Without the check, a 3-column Anki export
 * (Front / Back / Example sentence) publishes every card with
 * `MediaRef.Image(url = "una manzana roja")`, both apps try to load prose as an image, the third
 * column's real content is lost, and `--json` reports success.
 *
 * An error rather than a fall-through, unlike the import path: the four-column TSV is what this
 * file *documents*, so a third column that is not a URL is a file that does not match the format
 * its author asked for.
 */
private fun List<String>.imageUrlAt(column: Int, lineIndex: Int): String? {
    val value = getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!value.looksLikeImageUrl()) {
        throw CliError(
            ExitCode.BadInput,
            "Line ${lineIndex + 1}, column ${column + 1} is an image column but holds " +
                "\"${value.take(IMAGE_URL_ERROR_EXCERPT)}\" — it must be an http(s) URL. " +
                "A two-column file has no image columns at all.",
        )
    }
    return value
}

/**
 * Whether a value can be stored as a remote image reference.
 *
 * Scheme only, deliberately: this decides "is this a URL or is it prose", not "does this resolve".
 * Anything stricter would start rejecting addresses that work.
 */
internal fun String.looksLikeImageUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private const val IMAGE_URL_ERROR_EXCERPT = 40
