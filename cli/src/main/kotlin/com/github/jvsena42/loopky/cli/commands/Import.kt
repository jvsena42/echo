package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.anki.BulkNote
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ImportResult(
    val deck: DeckView,
    @SerialName("cards_written") val cardsWritten: Int,
    /** Rows the parser collapsed as duplicates of an earlier row. */
    @SerialName("duplicates_collapsed") val duplicatesCollapsed: Int = 0,
    /** Rows dropped for exceeding the parser's cap. Reported, never silent. */
    val truncated: Int = 0,
    /** Cards already in the deck when `--resume` picked it up, and therefore not written again. */
    val resumed: Int = 0,
    val separator: String,
)

/**
 * Import a deck from a file, or from stdin.
 *
 * The text path is the **app's own parser**, not a second one: `ImportRepository.parseBulk` runs
 * the spec §6 separator rules and the §9 edge cases, with the file-sized caps rather than the
 * paste box's. A deck imported here and a deck imported on a phone are split the same way, which
 * is what "the Android app opens it without a repair step" actually rests on.
 *
 * A tab-separated file carrying **image columns** takes the other entry point — see
 * [imageColumnRows].
 *
 * `--title` is required and beats any inference from the filename. See `deckCreate`.
 */
@Suppress("LongParameterList")
suspend fun import(
    args: Args,
    imports: ImportRepository,
    decks: DeckRepository,
    cards: CardRepository,
    session: Session,
    onProgress: (String) -> Unit,
): CommandResult {
    val source = args.word(1) ?: throw CliError(ExitCode.Usage, "Missing <file>, or - for stdin.")
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")

    val draft = parseSource(args, imports, source, title)
    val resume = resumeState(args, decks, cards, title)
    // Minted once and threaded down: every card carries its deck's id, so deriving it twice is how
    // a resumed run ends up writing cards addressed to a deck that does not exist.
    val deckId = resume.deck?.id ?: generateId()
    val built = buildCards(imports, draft, resume, deckId)

    val published = if (resume.deck != null) {
        appendMissing(decks, deckId, built, onProgress)
    } else {
        val deck = newDeck(args, draft, session, deckId, built.size)
        decks.publish(deck, built) { progress ->
            onProgress(
                "${progress.cardsWritten}/${progress.totalCards} cards, " +
                    "${progress.chunksWritten}/${progress.totalChunks} chunks",
            )
        }.getOrElse { throw asCliError(it) }
    }

    imports.clear()
    return result(
        ImportResult(
            deck = published.toView(),
            cardsWritten = built.size,
            duplicatesCollapsed = draft.duplicatesCollapsed,
            truncated = draft.truncated,
            resumed = resume.alreadyThere.size,
            separator = draft.separator::class.simpleName.orEmpty().lowercase(),
        ),
        buildString {
            appendLine("Imported ${built.size} cards into ${published.id} — $title")
            if (resume.alreadyThere.isNotEmpty()) {
                appendLine("Resumed: ${resume.alreadyThere.size} already present")
            }
            if (draft.duplicatesCollapsed > 0) appendLine("Duplicates collapsed: ${draft.duplicatesCollapsed}")
            if (draft.truncated > 0) appendLine("DROPPED at the parser cap: ${draft.truncated}")
            append("Separator: ${draft.separator::class.simpleName}")
        },
    )
}

private suspend fun parseSource(
    args: Args,
    imports: ImportRepository,
    source: String,
    title: String,
): ImportDraft {
    val text = readSource(source)
    val withImages = imageColumnRows(text)
    val draft = if (withImages != null) {
        imports.parseBulkNotes(withImages, suggestedTitle = title)
    } else {
        imports.parseBulk(text, args.option("separator")?.let(::separatorNamed), suggestedTitle = title)
    }.getOrElse { throw asCliError(it) }

    if (imports.keptRows().isEmpty()) {
        imports.clear()
        throw CliError(ExitCode.BadInput, "Nothing importable in $source.")
    }
    return draft
}

/**
 * What a `--resume` run already found on the homeserver.
 *
 * The deck **is** the checkpoint, rather than a local cursor file: it records exactly which cards
 * arrived, it survives the sandbox being thrown away, and it cannot disagree with reality the way
 * a cursor can. What it costs is one deck read; what it buys is that an import killed by the
 * hourly session expiry (#165) finishes instead of starting over or duplicating.
 *
 * Matched on the deck's **title**, which is why `--title` is mandatory and never derived: a
 * resumed run has to name the same deck it named the first time, and a title inferred from a
 * filename is one rename away from creating a second deck instead.
 */
private class ResumeState(val deck: Deck?, val alreadyThere: Set<String>)

private suspend fun resumeState(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    title: String,
): ResumeState {
    if (!args.has("resume")) return ResumeState(null, emptySet())
    val deck = decks.listOwned().firstOrNull { it.title == title } ?: return ResumeState(null, emptySet())
    val present = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
    return ResumeState(deck, present.mapTo(mutableSetOf()) { it.identityOf() })
}

private fun buildCards(
    imports: ImportRepository,
    draft: ImportDraft,
    resume: ResumeState,
    deckId: String,
): List<Card> {
    val now = System.currentTimeMillis()
    return imports.keptRows().mapIndexedNotNull { index, row ->
        val (front, back) = draft.frontBackOf(row)
        Card(
            id = generateId(),
            deckId = deckId,
            updatedAt = now,
            front = CardSide(
                text = front.takeIf { it.isNotBlank() },
                imageRef = imports.rowImage(row.index, isFront = true)?.url?.let(::remoteImage),
            ),
            back = CardSide(
                text = back.takeIf { it.isNotBlank() },
                imageRef = imports.rowImage(row.index, isFront = false)?.url?.let(::remoteImage),
            ),
            ord = ordForIndex(index),
        ).takeIf { it.identityOf() !in resume.alreadyThere }
    }
}

@Suppress("LongParameterList")
private fun newDeck(
    args: Args,
    draft: ImportDraft,
    session: Session,
    deckId: String,
    cardCount: Int,
): Deck {
    val now = System.currentTimeMillis()
    return Deck(
        id = deckId,
        authorPubky = session.identity.pubky,
        title = args.requireOption("title").trim(),
        description = args.option("description")?.takeIf { it.isNotBlank() },
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() },
        coverImageRef = args.option("cover-url")?.let(::remoteImage),
        tags = args.options("tag").map { Tag(it) },
        createdAt = now,
        updatedAt = now,
        // publish() writes the chunk table; this is the optimistic count it confirms.
        cardCount = cardCount,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = args.flag("listen", default = false),
        speakEnabled = args.flag("speak", default = false),
        typeEnabled = args.flag("type", default = false),
        // The one opt-in with a source opinion behind it: an `.apkg` built on a reversed note type
        // says so, and `parseBulkNotes` carries that through. A suggestion, still overridable.
        reverseEnabled = args.flag("reverse", default = draft.suggestsReverse),
        frontLang = args.option("front-lang"),
        backLang = args.option("back-lang"),
    )
}

private suspend fun appendMissing(
    decks: DeckRepository,
    deckId: String,
    cards: List<Card>,
    onProgress: (String) -> Unit,
): Deck {
    var deck = decks.getLocal(deckId) ?: decks.sync(deckId).getOrElse { throw asCliError(it) }
    cards.forEachIndexed { index, card ->
        deck = decks.upsertCard(deckId, card).getOrElse { throw asCliError(it) }
        onProgress("${index + 1}/${cards.size} cards")
    }
    return deck
}

private fun readSource(source: String): String = when (source) {
    "-" -> System.`in`.readBytes().toString(Charsets.UTF_8)
    else -> File(source).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        ?: throw CliError(ExitCode.BadInput, "No such file: $source")
}

/**
 * The rows of a tab-separated file that carries image columns, or null when it does not.
 *
 * `front <TAB> back <TAB> front_image_url <TAB> back_image_url` is a format this client defines,
 * so splitting it on tabs is reading a format rather than reimplementing spec §6. The rows then go
 * through `parseBulkNotes`, which is the same body as `parseBulk` minus the splitting step —
 * dedupe, the caps, truncation reporting and the drop-incomplete policy are shared. That entry
 * point exists because dedupe *renumbers* rows, so a picture cannot be re-attached afterwards by
 * the index it was read at.
 *
 * It engages only when **every** non-blank line has at least three tab-separated fields, which is
 * a statement about the format rather than a guess: one stray third column in a two-column file is
 * a card whose back got split, not a picture.
 *
 * Image columns are worth having from day one because since #167 a card image can be a remote ref
 * — a URL, no bytes on the wire, no media quota spent — and neither `.apkg` nor
 * TSV-through-the-parser can say "this side has text *and* a picture" (#54, finding 3).
 */
private fun imageColumnRows(text: String): List<BulkNote>? {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return null
    if (lines.any { it.split('\t').size < IMAGE_FORMAT_MIN_FIELDS }) return null
    return lines.map { line ->
        val fields = line.split('\t')
        BulkNote(
            front = fields[FRONT_COLUMN].trim(),
            back = fields[BACK_COLUMN].trim(),
            frontImage = fields.imageAt(FRONT_IMAGE_COLUMN),
            backImage = fields.imageAt(BACK_IMAGE_COLUMN),
        )
    }
}

private fun List<String>.imageAt(column: Int): DraftCardImage? =
    getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }?.let { DraftCardImage(url = it) }

private fun separatorNamed(name: String): Separator = when (name.lowercase()) {
    "auto" -> Separator.Auto
    "tab" -> Separator.Tab
    "comma" -> Separator.Comma
    "semicolon" -> Separator.Semicolon
    "pipe" -> Separator.Pipe
    "dash", "emdash" -> Separator.EmDash
    "colon" -> Separator.Colon
    "blank", "blankline" -> Separator.BlankLine
    "markdown", "table" -> Separator.MarkdownTable
    else -> throw CliError(
        ExitCode.Usage,
        "Unknown --separator '$name'. One of: auto, tab, comma, semicolon, pipe, dash, colon, " +
            "blank, markdown.",
    )
}

private const val FRONT_COLUMN = 0
private const val BACK_COLUMN = 1
private const val FRONT_IMAGE_COLUMN = 2
private const val BACK_IMAGE_COLUMN = 3

/** A file with fewer fields than this on any line is ordinary text for the shared parser. */
private const val IMAGE_FORMAT_MIN_FIELDS = 3
