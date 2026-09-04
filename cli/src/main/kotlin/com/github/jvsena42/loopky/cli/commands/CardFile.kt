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
 * A batch of cards, from a file — the shape both `card add --from-file` and `card edit --from-file`
 * take.
 *
 * **Batch mutation is a v1 requirement, not a convenience.** One card write is one chunk
 * read-modify-write plus a manifest patch plus a `/session` round trip (#105); 190 image attachments
 * done one at a time cost ~30–40 s each. A surface offering only `card edit <deckId> <cardId>` gets
 * used in a loop, and editing is precisely what an agent does *after* an import.
 *
 * Two formats, and the choice is not stylistic:
 *
 * - **TSV**, `front <TAB> back <TAB> front_image_url <TAB> back_image_url`, the last two optional.
 *   Image columns are here from day one because since #167 a card image can be a **remote ref** — a
 *   URL, no bytes on the wire, no quota spent — which is the gap neither `.apkg` nor
 *   TSV-through-the-paste-parser can express: both carry a picture only when a field is *nothing but*
 *   that image, so "this side has text and a picture" had no representation.
 * - **JSONL**, one object per line, for what TSV cannot hold: a side containing a tab or newline, and
 *   — for `card edit` — naming which card to change and which fields to leave alone.
 *
 * The format is chosen by extension, then content, never by a flag: a caller that has to remember
 * `--jsonl` will one day not, and a JSON object read as TSV is a card whose front is `{"id":`.
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
     * A row that could become a card: something on **both** sides, text or a picture. False is not
     * the same as [isEmpty] — an edit row may carry one side and mean "leave the other alone", so
     * this is asked only where a row becomes a *new* card.
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
 * Blank lines are skipped and `# ` comments honoured in TSV, so a generated file can carry a header
 * without a phantom first card — `#` **followed by whitespace**, so a card whose front is `#1 ranked`
 * survives.
 *
 * A row with nothing on either side is an error rather than a silent skip, and so is an image column
 * holding something that is not a URL: a file producing fewer cards than it has lines is exactly the
 * kind of loss `--json` exists to make visible.
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
        .onEachIndexed { index, row ->
            if (row.isEmpty) throw CliError(ExitCode.BadInput, "A row has neither text nor an image.")
            // Checked here and not only in the TSV columns: a JSONL row names its image fields
            // outright, so there is no "is this a picture or prose" question to answer — but an
            // unrenderable URL still has to be refused before `toCard` turns it into a ref, or it
            // surfaces as exit 1 "internal" plus a Kotlin assertion for a typo in someone's file.
            row.frontImageUrl?.checkedImageUrl("Line ${index + 1}, front_image_url")
            row.backImageUrl?.checkedImageUrl("Line ${index + 1}, back_image_url")
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
 * Refuse a batch holding a card with nothing on one of its sides.
 *
 * Checked here rather than left to `publish`, which `require`s the same thing but throws an
 * `IllegalArgumentException` no classifier recognises — so it would reach the user as exit 1
 * "internal" plus a Kotlin assertion message, for a blank column in their own file. The message names
 * the row, since the point of a batch is that nobody reads it line by line.
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
 * A comment line: `#` followed by whitespace, never a bare `#`. The space is load-bearing —
 * `startsWith("#")` alone silently swallows a card whose front is `#1 ranked`, a markdown heading or
 * a hashtag.
 */
private fun String.isComment(): Boolean =
    startsWith("#") && (length == 1 || this[1].isWhitespace())

/**
 * The image column at [column], refused if it is not a URL.
 *
 * Every app-side constructor of a remote `MediaRef.Image` gets its URL from a picker; this is the
 * first path where an arbitrary string reaches one. Without the check, a 3-column Anki export
 * (Front / Back / Example sentence) publishes every card with
 * `MediaRef.Image(url = "una manzana roja")`, both apps try to load prose as an image, and `--json`
 * reports success.
 *
 * An error rather than a fall-through, unlike the import path: the four-column TSV is what this file
 * *documents*.
 */
private fun List<String>.imageUrlAt(column: Int, lineIndex: Int): String? {
    val value = getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!value.looksLikeImageUrl()) {
        throw CliError(
            ExitCode.BadInput,
            "Line ${lineIndex + 1}, column ${column + 1} is an image column but holds " +
                "\"${value.take(IMAGE_URL_ERROR_EXCERPT)}\" — it must be an https:// URL. " +
                "A two-column file has no image columns at all.",
        )
    }
    return value.checkedImageUrl("Line ${lineIndex + 1}, column ${column + 1}")
}

/**
 * Whether a value is *shaped* like an image column — a scheme and nothing else.
 *
 * Deliberately looser than [isRenderableImageUrl], and not interchangeable with it. This one answers
 * "is this column pictures or prose", which decides how a whole file is read; the strict one answers
 * "could this ever render", which decides whether a single card is written. An `http://` address is a
 * clear answer to the first question, and refusing it here would send the whole import through the
 * text parser, silently turning every picture into a third card side.
 */
internal fun String.looksLikeImageUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private const val IMAGE_URL_ERROR_EXCERPT = 40
