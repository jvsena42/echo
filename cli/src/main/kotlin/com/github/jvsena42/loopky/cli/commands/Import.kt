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
    /**
     * Whether `--resume` found a deck to continue, or `null` when `--resume` was not asked for.
     *
     * `resumed: 0` cannot answer this: it is what a legitimate first `--resume` run reports *and*
     * what a typo'd `--title` reports one character away from an existing deck — where the second
     * silently publishes a duplicate, spends the quota twice and leaves two near-identical decks
     * in the library. This is the field that tells them apart.
     */
    @SerialName("resume_matched") val resumeMatched: Boolean? = null,
    /**
     * Columns past the front and back that the parser dropped, per row.
     *
     * A three-column file whose third column is not a URL is ordinary text, and spec §8 keeps
     * fields 0 and 1. The import is a success and each card is missing something the file held —
     * a loss worth naming rather than leaving for someone to notice in the app.
     */
    @SerialName("columns_dropped") val columnsDropped: Int = 0,
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

    val parsed = parseSource(args, imports, source, title)
    val draft = parsed.draft
    val resume = resumeState(args, decks, cards, title, onProgress)
    // Minted once and threaded down: every card carries its deck's id, so deriving it twice is how
    // a resumed run ends up writing cards addressed to a deck that does not exist.
    val deckId = resume.deck?.id ?: generateId()
    val built = buildCards(imports, draft, resume, deckId)

    val published = if (resume.deck != null) {
        appendMissing(decks, deckId, built, resume.deck.overlaidWith(args), onProgress)
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
            resumeMatched = if (args.has("resume")) resume.deck != null else null,
            columnsDropped = if (parsed.droppedColumns) 1 else 0,
            separator = draft.separator::class.simpleName.orEmpty().lowercase(),
        ),
        buildString {
            appendLine("Imported ${built.size} cards into ${published.id} — $title")
            if (resume.alreadyThere.isNotEmpty()) {
                appendLine("Resumed: ${resume.alreadyThere.size} already present")
            }
            if (draft.duplicatesCollapsed > 0) appendLine("Duplicates collapsed: ${draft.duplicatesCollapsed}")
            if (draft.truncated > 0) appendLine("DROPPED at the parser cap: ${draft.truncated}")
            if (parsed.droppedColumns) {
                appendLine(
                    "DROPPED a third column on every row — it held text, not image URLs, so the " +
                        "parser kept only front and back.",
                )
            }
            append("Separator: ${draft.separator::class.simpleName}")
        },
    )
}

private class ParsedSource(val draft: ImportDraft, val droppedColumns: Boolean)

private suspend fun parseSource(
    args: Args,
    imports: ImportRepository,
    source: String,
    title: String,
): ParsedSource {
    val text = readSource(source)
    val read = imageColumnRows(text)
    val draft = if (read.notes != null) {
        imports.parseBulkNotes(read.notes, suggestedTitle = title)
    } else {
        imports.parseBulk(text, args.option("separator")?.let(::separatorNamed), suggestedTitle = title)
    }.getOrElse { throw asCliError(it) }

    if (imports.keptRows().isEmpty()) {
        imports.clear()
        throw CliError(ExitCode.BadInput, "Nothing importable in $source.")
    }
    return ParsedSource(draft, droppedColumns = read.extraColumns)
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
    onNote: (String) -> Unit,
): ResumeState {
    if (!args.has("resume")) return ResumeState(null, emptySet())
    val owned = decks.listOwned()
    val matches = owned.filter { it.title == title }
    if (matches.isEmpty()) {
        // Not an error: an agent that always passes `--resume` so its retries are safe has to be
        // able to make the *first* run. But it is not silent either — this is the only signal that
        // separates a first run from a `--title` typo about to publish a second copy of a deck the
        // account already has, and `resumed: 0` says the same thing in both cases.
        onNote(
            "--resume found no deck titled \"$title\", so this is publishing a NEW deck. " +
                "If that was a typo, delete it and re-run. Your decks: " +
                owned.joinToString(", ") { it.title }.ifEmpty { "none" },
        )
        return ResumeState(null, emptySet())
    }
    if (matches.size > 1) {
        // Refused rather than resolved. `firstOrNull` over a listing in homeserver order would
        // pick one of them arbitrarily and append somebody's cards to the wrong deck — a silent
        // wrong answer, where this is a loud one the user can act on.
        throw CliError(
            ExitCode.BadInput,
            "${matches.size} of your decks are called \"$title\", so --resume cannot tell which " +
                "one to continue: ${matches.joinToString(", ") { it.id }}. Rename or delete one, " +
                "or drop --resume to publish a new deck.",
        )
    }
    val deck = matches.firstOrNull() ?: return ResumeState(null, emptySet())
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

/**
 * The existing deck with whatever metadata this invocation actually specified applied on top.
 *
 * Null when nothing was specified, so a bare `--resume` costs no metadata write at all.
 *
 * Accepting `--tag`, `--description` or `--front-lang` on a resumed run and silently dropping them
 * was the third of the resume findings, and the sharpest of the three: the natural way to use the
 * feature is to re-run the *same command* with `--resume` appended, so an agent lost every flag
 * but `--title` — and a dropped language pair means `Deck.speechReady` stays false and Listen and
 * Speak never appear, with the CLI reporting success.
 *
 * Only what was given is overlaid. Absent is not the same as "set it to the default": that
 * distinction is why the opt-ins go through [Args.flagOrNull] rather than [Args.flag], and
 * without it a bare `--resume` would turn off every mode the deck already had.
 */
private fun Deck.overlaidWith(args: Args): Deck? {
    val tags = args.options("tag").map { Tag(it) }
    val updated = copy(
        description = args.option("description")?.takeIf { it.isNotBlank() } ?: description,
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() } ?: coverEmoji,
        coverImageRef = args.option("cover-url")?.let(::remoteImage) ?: coverImageRef,
        tags = tags.ifEmpty { this.tags },
        listenEnabled = args.flagOrNull("listen") ?: listenEnabled,
        speakEnabled = args.flagOrNull("speak") ?: speakEnabled,
        typeEnabled = args.flagOrNull("type") ?: typeEnabled,
        reverseEnabled = args.flagOrNull("reverse") ?: reverseEnabled,
        frontLang = args.option("front-lang") ?: frontLang,
        backLang = args.option("back-lang") ?: backLang,
    )
    return updated.takeIf { it != this }
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

/**
 * Write the cards a resumed run is missing, and bring the deck's metadata up to date.
 *
 * One `appendCards` rather than a loop of `upsertCard`. The loop cost a chunk write *plus* a full
 * manifest read-modify-write per card — 60 writes for 30 cards where a publish spends 2 — which
 * made the recovery path an order of magnitude slower than the attempt it was recovering, on the
 * same one-hour session budget. A large import that died at 55 minutes could never finish.
 */
private suspend fun appendMissing(
    decks: DeckRepository,
    deckId: String,
    cards: List<Card>,
    metadata: Deck?,
    onProgress: (String) -> Unit,
): Deck {
    decks.getLocal(deckId) ?: decks.sync(deckId).getOrElse { throw asCliError(it) }
    onProgress("writing ${cards.size} missing cards")
    var deck = decks.appendCards(deckId, cards).getOrElse { throw asCliError(it) }
    if (metadata != null) {
        // Metadata last: a failed publish should not have renamed the deck it failed to fill.
        onProgress("updating deck metadata")
        deck = decks.updateMetadata(metadata.copy(id = deckId, cardCount = deck.cardCount))
            .getOrElse { throw asCliError(it) }
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
 * It engages only when **every** non-blank line has at least three tab-separated fields *and*
 * every non-empty image column holds an http(s) URL. Both halves are needed. The first keeps one
 * stray third column in a two-column file from being read as a format; the second keeps a
 * three-column Anki export — Front / Back / Example sentence, a very common shape — from
 * publishing every card with `MediaRef.Image(url = "una manzana roja")`, which both apps then try
 * to load as a picture while the column's real content is lost and `--json` reports success.
 *
 * A file that fails either test is ordinary text and falls through to the shared parser, which is
 * the right answer here — unlike `card add --from-file`, where the four-column TSV is what the
 * user explicitly asked for and a non-URL is an error.
 *
 * Image columns are worth having from day one because since #167 a card image can be a remote ref
 * — a URL, no bytes on the wire, no media quota spent — and neither `.apkg` nor
 * TSV-through-the-parser can say "this side has text *and* a picture" (#54, finding 3).
 */
private fun imageColumnRows(text: String): ImageColumnRead {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return ImageColumnRead(null, extraColumns = false)
    val rows = lines.map { it.split('\t') }
    if (rows.any { it.size < IMAGE_FORMAT_MIN_FIELDS }) return ImageColumnRead(null, extraColumns = false)
    // Three-plus columns, but not addresses: ordinary text with a column the shared parser will
    // drop. Declining is right; declining *quietly* is not, so the caller is told.
    if (rows.any { !it.imageColumnsAreUrls() }) return ImageColumnRead(null, extraColumns = true)
    return ImageColumnRead(
        notes = rows.map { fields ->
            BulkNote(
                front = fields[FRONT_COLUMN].trim(),
                back = fields[BACK_COLUMN].trim(),
                frontImage = fields.imageAt(FRONT_IMAGE_COLUMN),
                backImage = fields.imageAt(BACK_IMAGE_COLUMN),
            )
        },
        extraColumns = false,
    )
}

/**
 * What reading a file as the image format found.
 *
 * [extraColumns] is the case that has to be *reported* rather than merely handled: a file whose
 * every line has three or more tab fields, none of them URLs, is ordinary text — and spec §8's
 * parser keeps fields 0 and 1 and drops the rest. So the import succeeds while each card quietly
 * loses a column. That is a quieter version of the loss `--json` exists to make visible: not fewer
 * cards, less of each card. Nothing here changes what is parsed — diverging from the app's parser
 * is the one thing this command must not do — only what is said about it.
 */
private class ImageColumnRead(val notes: List<BulkNote>?, val extraColumns: Boolean)

/** An empty image column says nothing either way; a filled one has to look like an address. */
private fun List<String>.imageColumnsAreUrls(): Boolean =
    listOf(FRONT_IMAGE_COLUMN, BACK_IMAGE_COLUMN).all { column ->
        getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }?.looksLikeImageUrl() ?: true
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
