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
 * Blank lines are skipped and `#` comments are honoured in TSV, so a generated file can carry a
 * header without a phantom first card. A row with nothing on either side is an error rather than a
 * silent skip: a file that produced fewer cards than it has lines is exactly the kind of loss
 * `--json` exists to make visible.
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
        .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
        .map { (index, line) ->
            // `split`, not a CSV reader: the separator is a tab, which is the one character a card
            // side reliably does not contain, and quoting rules would only add a way to get it
            // wrong. A side that needs a tab or a newline uses JSONL.
            val fields = line.split('\t')
            CardFileRow(
                front = fields.getOrNull(FRONT_COLUMN)?.trim(),
                back = fields.getOrNull(BACK_COLUMN)?.trim(),
                frontImageUrl = fields.getOrNull(FRONT_IMAGE_COLUMN)?.trim()?.takeIf { it.isNotEmpty() },
                backImageUrl = fields.getOrNull(BACK_IMAGE_COLUMN)?.trim()?.takeIf { it.isNotEmpty() },
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
