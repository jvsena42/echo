package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /**
     * How the file was split. `"none"` for a source that knows its own structure and was never
     * split at all — an `.apkg` — because reporting the `Separator.Tab` the structured entry point
     * carries would name a rule that did not run.
     */
    val separator: String,
    /** `"text"` or `"apkg"`. See [detectImportFormat]. */
    val format: String = ImportFormat.Text.json,
    /** The `.apkg` reader's own accounting, or null for a text import. See [ApkgSummary]. */
    val apkg: ApkgSummary? = null,
)

/**
 * What `--dry-run` reports: everything an import would do, and no deck.
 *
 * A separate shape from [ImportResult] rather than one with a nulled-out deck, because the two
 * answer different questions and a caller must not be able to mistake one for the other. It exists
 * mostly for the `.apkg` path, where guessing the field mapping wrong is the likeliest way to
 * publish 9,000 cards of database ids (#96) — `--json`'s job is verification, and this is the one
 * import where verifying *after* the write is too late.
 *
 * It needs **no session**: reading a local file is not a homeserver operation, and requiring a
 * live one to look at a file would put a sign-in between an agent and the check that stops it
 * spending someone's quota.
 */
@Serializable
data class ImportPreview(
    @SerialName("dry_run") val dryRun: Boolean = true,
    val source: String,
    val format: String,
    /** `--title`, if it was given. Optional here: a preview may be what decides the title. */
    val title: String? = null,
    /** Cards this file would publish, after dedupe and the parser's caps. */
    val cards: Int,
    @SerialName("duplicates_collapsed") val duplicatesCollapsed: Int = 0,
    val truncated: Int = 0,
    @SerialName("columns_dropped") val columnsDropped: Int = 0,
    val separator: String,
    val apkg: ApkgSummary? = null,
)

/** The `--json` spelling of a format, kept beside the enum so the two cannot drift. */
internal val ImportFormat.json: String
    get() = when (this) {
        ImportFormat.Text -> "text"
        ImportFormat.Apkg -> "apkg"
    }

/**
 * Import a deck from a file, or from stdin.
 *
 * The text path is the **app's own parser**, not a second one: `ImportRepository.parseBulk` runs
 * the spec §6 separator rules and the §9 edge cases, with the file-sized caps rather than the
 * paste box's. A deck imported here and a deck imported on a phone are split the same way, which
 * is what "the Android app opens it without a repair step" actually rests on.
 *
 * An **Anki `.apkg`** takes the same command and the same spine: the shared reader turns it into
 * `BulkNote`s and `parseBulkNotes` — the entry point that exists for exactly this — carries them
 * through the same dedupe, caps and drop policy. A second verb would have been a second import
 * flow, which is the one thing #54 rules out. See `ApkgImport.kt`.
 *
 * A tab-separated file carrying **image columns** takes the other entry point — see
 * [imageColumnRows].
 *
 * `--title` is required and beats any inference from the filename. See `deckCreate`.
 */
@Suppress("LongParameterList", "LongMethod")
suspend fun import(
    args: Args,
    imports: ImportRepository,
    decks: DeckRepository,
    cards: CardRepository,
    media: MediaRepository,
    session: Session,
    onProgress: (String) -> Unit,
    /**
     * Something the caller needs to know rather than a counter — reaches stderr even under
     * `--json`. Two notes today: that `--resume` matched no deck, where the titles it lists are
     * what turns "no match" into "you meant *this* one"; and what an `.apkg`'s pictures are about
     * to spend against a quota nothing can read back. Suppressing either under `--json` withholds
     * it exactly where the mistake is expensive.
     */
    onNote: (String) -> Unit,
): CommandResult {
    val source = args.word(1) ?: throw CliError(ExitCode.Usage, "Missing <file>, or - for stdin.")
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")

    val parsed = parseSource(args, imports, source, title, keepImageBytes = true)
    val draft = parsed.draft
    parsed.apkg?.let { warnAboutMediaSpend(it, onNote) }
    val resume = resumeState(args, decks, cards, title, onNote)
    // Minted once and threaded down: every card carries its deck's id, so deriving it twice is how
    // a resumed run ends up writing cards addressed to a deck that does not exist.
    val deckId = resume.deck?.id ?: generateId()

    // Blobs this invocation wrote, so an aborted publish of a *new* deck can take them back out.
    val uploaded = mutableListOf<MediaRef>()
    val written = try {
        val built = buildCards(imports, draft, resume, deckId, media, uploaded, onProgress)
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
        Written(published, built.size)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // Only for a deck that does not exist yet. Blobs are content-addressed per deck, so on a
        // resumed run the shas this invocation uploaded are the same shas the *already published*
        // cards point at — sweeping them there would strip the pictures off cards that were fine.
        if (resume.deck == null) sweepUploadedMedia(media, deckId, uploaded, onNote)
        throw error
    }

    imports.clear()
    val published = written.deck
    return result(
        ImportResult(
            deck = published.toView(),
            cardsWritten = written.cards,
            duplicatesCollapsed = draft.duplicatesCollapsed,
            truncated = draft.truncated,
            resumed = resume.alreadyThere.size,
            resumeMatched = if (args.has("resume")) resume.deck != null else null,
            columnsDropped = if (parsed.droppedColumns) 1 else 0,
            separator = draft.separatorName(),
            format = parsed.format.json,
            apkg = parsed.apkg,
        ),
        describeImport(written, title, parsed, resume),
    )
}

/**
 * The same numbers as [ImportResult], for a person.
 *
 * Deliberately the *same* numbers rather than a friendlier subset: `--json` and the plain output
 * are two renderings of one result, and a loss that only one of them mentions is a loss somebody
 * will miss. The capitalised DROPPED lines are the ones worth noticing in a scroll-back.
 */
private fun describeImport(
    written: Written,
    title: String,
    parsed: ParsedSource,
    resume: ResumeState,
): String {
    val draft = parsed.draft
    return buildString {
        appendLine("Imported ${written.cards} cards into ${written.deck.id} — $title")
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
        parsed.apkg?.let { appendLine(it.describe()) }
        append("Separator: ${draft.separatorName()}")
    }
}

/**
 * `--dry-run`: read the file, report what publishing it would do, write nothing.
 *
 * Takes no [Session] on purpose — see [ImportPreview]. It is also where `--title` stops being
 * mandatory: a preview is frequently what tells you what the deck is called, and demanding the
 * answer before showing the question is the wrong way round.
 */
suspend fun importDryRun(args: Args, imports: ImportRepository): CommandResult {
    val source = args.word(1) ?: throw CliError(ExitCode.Usage, "Missing <file>, or - for stdin.")
    val title = args.option("title")?.trim()?.takeIf { it.isNotEmpty() }

    // Nothing is uploaded, so the blobs are measured and dropped rather than held: a dry run of a
    // 500-image deck should not need the deck's media in heap to answer how big it is.
    val parsed = parseSource(args, imports, source, title, keepImageBytes = false)
    val draft = parsed.draft
    val cards = imports.keptRows().size
    imports.clear()

    return result(
        ImportPreview(
            source = source,
            format = parsed.format.json,
            title = title,
            cards = cards,
            duplicatesCollapsed = draft.duplicatesCollapsed,
            truncated = draft.truncated,
            columnsDropped = if (parsed.droppedColumns) 1 else 0,
            separator = draft.separatorName(),
            apkg = parsed.apkg,
        ),
        buildString {
            appendLine("$source would publish $cards ${if (cards == 1) "card" else "cards"}. Nothing was written.")
            if (draft.duplicatesCollapsed > 0) appendLine("Duplicates collapsed: ${draft.duplicatesCollapsed}")
            if (draft.truncated > 0) appendLine("DROPPED at the parser cap: ${draft.truncated}")
            if (parsed.droppedColumns) {
                appendLine("A third column on every row holds text, not image URLs, and would be dropped.")
            }
            parsed.apkg?.let { summary ->
                summary.deckName?.let { appendLine("Anki deck name: $it") }
                appendLine(summary.describe())
            }
            append("Separator: ${draft.separatorName()}")
        },
    )
}

/**
 * A `Separator` as the envelope names it, with structured sources reported as having none.
 *
 * `parseBulkNotes` stamps `Separator.Tab` on a draft it never split, because the field is not
 * nullable — reporting that for an `.apkg` would name a rule that did not run, on the one format
 * where a caller checking "was this split the way I meant?" is asking a real question.
 */
private fun ImportDraft.separatorName(): String =
    if (structured) "none" else separator::class.simpleName.orEmpty().lowercase()

private class Written(val deck: Deck, val cards: Int)

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

/**
 * The kept rows as [Card]s, with any blob-backed picture uploaded on the way through.
 *
 * A TSV's images are URLs and cost nothing to resolve; an `.apkg`'s are bytes and have to be
 * written to the homeserver before a card can reference one, because the ref is content-addressed
 * and the digest is [MediaRepository]'s to compute.
 *
 * **The upload happens before the `--resume` filter, not after, and it is worth knowing why it is
 * that way round.** `identityOf` distinguishes two cards asking the same question about different
 * pictures by the image's `sha256` — so deciding whether an image card is already published needs
 * the sha, and the sha needs the upload. The cost is that a resumed image-heavy `.apkg` re-writes
 * the archive's blobs: bounded (at most 500 distinct pictures, and [uploads] collapses repeats
 * within a run), quota-neutral because a blob is stored under its own digest and a rewrite lands
 * on the same path — but not free in wall-clock, on an hourly session budget.
 */
@Suppress("LongParameterList")
private suspend fun buildCards(
    imports: ImportRepository,
    draft: ImportDraft,
    resume: ResumeState,
    deckId: String,
    media: MediaRepository,
    uploaded: MutableList<MediaRef>,
    onProgress: (String) -> Unit,
): List<Card> {
    val now = System.currentTimeMillis()
    // Keyed on the draft image itself. `DraftCardImage` is a data class over a `ByteArray`, whose
    // equals and hashCode are identity — which is exactly the question being asked here, since
    // `MediaIndex` hands back one instance per distinct blob. A picture on forty cards uploads
    // once.
    val uploads = mutableMapOf<DraftCardImage, MediaRef.Image>()
    return imports.keptRows().mapIndexedNotNull { index, row ->
        val (front, back) = draft.frontBackOf(row)
        Card(
            id = generateId(),
            deckId = deckId,
            updatedAt = now,
            front = CardSide(
                text = front.takeIf { it.isNotBlank() },
                imageRef = resolveImage(
                    imports.rowImage(row.index, isFront = true),
                    deckId, media, uploads, uploaded, onProgress,
                ),
            ),
            back = CardSide(
                text = back.takeIf { it.isNotBlank() },
                imageRef = resolveImage(
                    imports.rowImage(row.index, isFront = false),
                    deckId, media, uploads, uploaded, onProgress,
                ),
            ),
            ord = ordForIndex(index),
        ).takeIf { it.identityOf() !in resume.alreadyThere }
    }
}

/**
 * A draft picture as a [MediaRef.Image]: a URL wrapped, bytes uploaded, nothing left over.
 *
 * **A failed upload throws rather than degrading to `null`.** Swallowing it is how a publish comes
 * back successful with the pictures quietly missing — a deck missing media the file held is a
 * failed import (#91), and here it would be a failed import that `--json` reported as fine.
 */
@Suppress("LongParameterList")
private suspend fun resolveImage(
    image: DraftCardImage?,
    deckId: String,
    media: MediaRepository,
    uploads: MutableMap<DraftCardImage, MediaRef.Image>,
    uploaded: MutableList<MediaRef>,
    onProgress: (String) -> Unit,
): MediaRef.Image? {
    val bytes = image?.bytes
    if (image == null) return null
    if (bytes == null) return image.url?.let(::remoteImage)
    uploads[image]?.let { return it }

    onProgress("uploading picture ${uploads.size + 1} (${formatBytes(bytes.size.toLong())})")
    val ref = media.putImage(deckId, bytes, image.mime ?: DEFAULT_IMAGE_MIME)
        .getOrElse { throw asCliError(it) }
    uploads[image] = ref
    uploaded += ref
    return ref
}

private const val DEFAULT_IMAGE_MIME = "image/jpeg"

/**
 * Take back the blobs an aborted publish already wrote.
 *
 * By hand rather than through `DeckRepository.delete`, which walks a manifest to find what to
 * sweep — and at this point there is no manifest, because media goes up before `publish` writes
 * one. Best-effort and never fatal: the reason the import aborted is quite plausibly that storage
 * is what ran out, and failing the failure would replace a useful error with a useless one.
 */
private suspend fun sweepUploadedMedia(
    media: MediaRepository,
    deckId: String,
    uploaded: List<MediaRef>,
    onNote: (String) -> Unit,
) {
    if (uploaded.isEmpty()) return
    var failed = 0
    uploaded.forEach { ref -> media.delete(deckId, ref).onFailure { failed++ } }
    if (failed > 0) {
        onNote("$failed of ${uploaded.size} pictures this run uploaded could not be removed again.")
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
        coverImageRef = args.option("cover-url")?.checkedImageUrl("--cover-url")?.let(::remoteImage)
            ?: coverImageRef,
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
        coverImageRef = args.option("cover-url")?.checkedImageUrl("--cover-url")?.let(::remoteImage),
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
        // Metadata last: a failed append should not have renamed the deck it failed to fill.
        onProgress("updating deck metadata")
        // `chunks` and `cardCount` are re-read inside `updateMetadata`'s lock, so the stale ones
        // carried on this snapshot are discarded — but **`updatedAt` is not**, and it is what
        // `hasUpdate` compares against a follower's last-seen mark. Writing the pre-append value
        // would rewind the manifest's timestamp below what the append just set, and a follower
        // who had already seen the deck would never be told the new cards exist.
        deck = decks.updateMetadata(metadata.copy(updatedAt = deck.updatedAt))
            .getOrElse { throw asCliError(it) }
    }
    return deck
}
