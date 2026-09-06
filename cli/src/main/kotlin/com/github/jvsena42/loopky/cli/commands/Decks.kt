package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.requireUsableOperand
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toLine
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.remoteImageRef
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeckListResult(val decks: List<DeckView>, val count: Int)

@Serializable
data class DeckShowResult(val deck: DeckView)

/**
 * `deck create`'s own shape, so it can report `--check-images` findings that `deck show` has no
 * way to produce. Same `deck` field either way — a caller reading the deck out of the envelope
 * does not have to know which command wrote it.
 */
@Serializable
data class DeckCreateResult(
    val deck: DeckView,
    @SerialName("image_checks") val imageChecks: List<ImageCheck> = emptyList(),
    /**
     * False when `--if-not-exists` found the deck already there and wrote nothing.
     *
     * The field exists so the two outcomes are distinguishable at all: both are exit 0 carrying
     * the same deck, and an agent retrying an ambiguous failure needs to know whether this call
     * or a previous one put it there.
     */
    val created: Boolean = true,
    /**
     * True when `--dry-run` reported this deck instead of publishing it. See [deckCreate].
     *
     * Beside [created] rather than folded into it: `created: false` already means "the deck was
     * already there", and a caller reading one field could not tell that outcome from a preview.
     */
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class DeckDeleteResult(val id: String, val deleted: Boolean)

@Serializable
data class DeckCompactResult(
    /** The deck as it stands afterwards, chunk table included — see `DeckView.chunks`. */
    val deck: DeckView,
    val id: String,
    val merges: Int,
    @SerialName("cards_moved") val cardsMoved: Int,
    @SerialName("chunks_before") val chunksBefore: Int,
    @SerialName("chunks_after") val chunksAfter: Int,
    val complete: Boolean,
)

suspend fun deckList(decks: DeckRepository): CommandResult {
    val owned = decks.listOwned().map { it.toView() }
    return result(
        DeckListResult(owned, owned.size),
        if (owned.isEmpty()) "No decks." else owned.joinToString("\n") { it.toLine() },
    )
}

/**
 * A deck as it is on the homeserver, not as this process happens to remember it.
 *
 * [DeckRepository.sync] rather than `getLocal`, because a fresh invocation has an empty cache and
 * `--json` is a verification channel: a caller diffing what it asked for against what is stored
 * has to be reading the homeserver.
 */
suspend fun deckShow(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val deck = decks.sync(id).getOrElse { throw asCliError(it) }
    return result(DeckShowResult(deck.toView()), deck.toView().describe())
}

private fun DeckView.describe(): String = buildString {
    appendLine("Id:          $id")
    appendLine("Title:       $title")
    description?.let { appendLine("Description: $it") }
    appendLine("Cards:       $cardCount")
    appendLine("Tags:        ${tags.joinToString(", ").ifEmpty { "—" }}")
    coverImage?.let { appendLine("Cover:       ${it.url ?: it.sha256}") }
    if (frontLang != null || backLang != null) {
        appendLine("Languages:   ${frontLang ?: "?"} -> ${backLang ?: "?"}")
    }
    appendLine(
        "Study modes: " + listOfNotNull(
            "listen".takeIf { listenEnabled },
            "speak".takeIf { speakEnabled },
            "type".takeIf { typeEnabled },
            "reverse".takeIf { reverseEnabled },
        ).joinToString(", ").ifEmpty { "flip only" },
    )
    if (incomplete) appendLine("INCOMPLETE:  a publish did not finish; re-publish or delete it.")
    append("Uri:         $uri")
}

/**
 * Create a deck, optionally with its cards in the same call.
 *
 * `--title` is required and never derived from a filename. Derivation is not a shortcut worth
 * having: it replaced the hyphen in `Biomas e Sub-ecossistemas Brasileiros` with a space, and that
 * was caught only because a human read the result afterwards (#54, finding "explicit beats
 * inferred").
 *
 * Cards go up in the same `publish` as the manifest, which is what makes this cheap: the
 * repository writes an `incomplete: true` marker manifest first, then the chunks at
 * `MAX_IN_FLIGHT = 4`, then the real manifest — so an interrupted run leaves a deck that is
 * visible and deletable rather than orphaned chunk records under a manifest-less root.
 *
 * A declared `--front-lang`/`--back-lang` pair contributes its labels to the tag set, the way the
 * apps' language pick does — see [deckTags]. A deck that declares no pair gets none.
 *
 * **`--dry-run` reports the deck and stops.** It runs the real path — the `--id` existence check,
 * this command's own card-file reader, every row's validation and `--check-images` — and returns
 * before the publish. `import --dry-run` was the only pre-flight there was, and it goes through a
 * *different* parser (#257, item 8), so it could not answer what this command would do with the
 * same file. Unlike that one it needs a session, because the two things worth checking here — is
 * this id free, and would a publish replace a deck's chunk table — are homeserver reads.
 */
suspend fun deckCreate(
    args: Args,
    decks: DeckRepository,
    session: Session,
    onNote: (String) -> Unit = System.err::println,
    onProgress: (String) -> Unit,
): CommandResult {
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")

    val deckId = args.deckIdToCreate()
    // Before the card file is read and before any picture is probed: when the deck is already
    // there this call has nothing left to do, and a `--from-file` of 9,000 rows should not be
    // parsed to find that out.
    existingDeck(args, decks, session, deckId)?.let { existing ->
        return result(
            // `dry_run` still travels, even though this branch writes nothing either way: a
            // caller branching on it must not have to know which of the two reasons applied.
            DeckCreateResult(existing.toView(), created = false, dryRun = args.has(DRY_RUN_FLAG)),
            "${existing.id} already exists — ${existing.title} (${existing.cardCount} cards). Nothing written.",
        )
    }
    val now = System.currentTimeMillis()
    val frontLang = args.option("front-lang")
    val backLang = args.option("back-lang")
    val cards = args.option("from-file")
        ?.let { readCardFile(it, onNote).requireBothSides().toCards(deckId, now) }
        .orEmpty()
    val imageChecks = if (args.checksImages()) {
        checkImageUrls(
            cards.flatMap { listOfNotNull(it.front.imageRef?.url, it.back.imageRef?.url) },
            onNote,
            args.imageCheckConcurrency(),
        )
    } else {
        emptyList()
    }

    val deck = Deck(
        id = deckId,
        authorPubky = session.identity.pubky,
        title = title,
        description = args.option("description")?.takeIf { it.isNotBlank() },
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() },
        coverImageRef = args.option("cover-url")?.checkedImageUrl("--cover-url", onNote)?.let(::remoteImage),
        tags = deckTags(args.options("tag"), frontLang, backLang),
        createdAt = now,
        updatedAt = now,
        cardCount = cards.size,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = args.flag("listen", default = false),
        speakEnabled = args.flag("speak", default = false),
        typeEnabled = args.flag("type", default = false),
        reverseEnabled = args.flag("reverse", default = false),
        frontLang = frontLang,
        backLang = backLang,
    )

    if (args.has(DRY_RUN_FLAG)) {
        return result(
            DeckCreateResult(deck.toView(), imageChecks, created = false, dryRun = true),
            "$deckId would be created — $title (${cards.size} cards). Nothing was written.",
        )
    }

    val published = decks.publish(deck, cards) { progress ->
        onProgress("${progress.cardsWritten}/${progress.totalCards} cards, ${progress.chunksWritten}/${progress.totalChunks} chunks")
    }.getOrElse { throw asCliError(it) }

    return result(
        DeckCreateResult(published.toView(), imageChecks),
        "Created ${published.id} — ${published.title} (${published.cardCount} cards)",
    )
}

/**
 * The id the new deck gets: `--id` when one was given, otherwise a fresh one.
 *
 * A client-supplied id is what makes a retry addressable (#240, finding 5). Without one, an agent
 * whose `deck create` was killed mid-flight — the hourly expiry and a 2.5 s round trip make that
 * ordinary — can only recover by listing decks and matching on title, which is neither cheap nor
 * race-free, and a second run simply publishes a second deck.
 */
private fun Args.deckIdToCreate(): String {
    val supplied = option("id")?.trim() ?: return generateId().also {
        if (has("if-not-exists")) {
            throw CliError(
                ExitCode.Usage,
                "--if-not-exists needs --id. Without one this command mints a fresh id, which " +
                    "never exists, so the flag could only mean matching on --title — a listing " +
                    "per call, and two decks named the same are indistinguishable anyway.",
            )
        }
    }
    return requireUsableOperand(supplied, "id")
}

/**
 * The deck already at [deckId], or null when there is nothing usable there.
 *
 * Three things this has to get right, none of them visible from the call site.
 *
 * **A read failure that is not a miss is rethrown.** Treating an unreachable homeserver as "the
 * deck does not exist" is how `--if-not-exists` would publish the duplicate it exists to prevent.
 *
 * **It reads the manifest, not the deck.** `DeckRepository.sync` is `fetchRemote` *plus*
 * `fetchByDeck`, which on a cold CLI process pulls every chunk — ~200 reads for a 20k-card deck,
 * paid on every idempotent retry, to answer a question the manifest alone settles. `fetchRemote`
 * reads one record and carries everything used here: the author, `incomplete`, the title and the
 * count.
 *
 * **A deck belonging to someone else is not an answer to "is my id taken?".** Reading
 * `session.identity.pubky`'s own namespace is what guarantees that — `sync` resolves an unknown
 * id's author through the caller's *follow* records first, so a `--id` colliding with a followed
 * deck used to come back as that author's manifest: `--if-not-exists` accepting a deck you cannot
 * write, or a refusal for an id that was free all along. The author check below is then a cheap
 * consistency assertion on a record this client does not control the contents of, rather than the
 * guarantee itself.
 *
 * **An `incomplete` manifest is not a deck.** `publish` writes that marker *before* the chunks, so
 * a killed `deck create --id X` — the case `--if-not-exists` exists for — leaves one behind.
 * Accepting it would report success for a deck whose cards were never uploaded, permanently.
 *
 * Without `--if-not-exists` an existing id is refused rather than overwritten: `publish` replaces
 * the manifest and its whole chunk table, so a reused id would take a deck's cards with it — and
 * nothing here prompts, so this is the only place that can say no.
 */
private suspend fun existingDeck(
    args: Args,
    decks: DeckRepository,
    session: Session,
    deckId: String,
): Deck? {
    if (args.option("id") == null) return null
    val existing = decks.fetchRemote(session.identity.pubky, deckId).fold(
        onSuccess = { it },
        onFailure = { if (ExitCode.of(it) == ExitCode.NotFound) null else throw asCliError(it) },
    ) ?: return null
    if (existing.authorPubky != session.identity.pubky) return null
    if (existing.incomplete) {
        if (args.has("if-not-exists")) return null
        throw CliError(
            ExitCode.BadInput,
            "Deck $deckId exists but is incomplete — a previous publish did not finish, so its " +
                "cards are missing. Pass --if-not-exists to finish it by re-publishing, or use " +
                "`deck delete`.",
        )
    }
    if (args.has("if-not-exists")) return existing
    throw CliError(
        ExitCode.BadInput,
        "Deck $deckId already exists — \"${existing.title}\", ${existing.cardCount} cards. " +
            "Publishing over it would replace its chunk table and lose them. Pass " +
            "--if-not-exists to accept it as it is, or use `deck edit` / `deck delete`.",
    )
}

suspend fun deckDelete(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    decks.delete(id).getOrElse { throw asCliError(it) }
    return result(DeckDeleteResult(id, deleted = true), "Deleted $id")
}

suspend fun deckSync(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val deck = decks.sync(id).getOrElse { throw asCliError(it) }
    // Pull the cards too: `sync` refreshes the manifest, and a caller asking to sync a deck means
    // the deck, not its metadata. Chunks whose `updated_at` has not moved are not re-fetched.
    cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
    return result(DeckShowResult(deck.toView()), "Synced ${deck.id} — ${deck.cardCount} cards")
}

/**
 * Fold away the holes card deletes leave behind.
 *
 * Explicit rather than automatic, and that is the deliberate half of `InlineBackgroundTasks`
 * dropping the scheduled pass: a process that ends when its command does has no deferred window,
 * and a surprise compaction in the middle of an import is exactly what the Android scheduler's
 * constraints exist to prevent. Nothing here is required for correctness — a deck with holes is a
 * deck every reader already handles — so it is offered, never imposed.
 */
suspend fun deckCompact(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    val outcome = decks.compactDeck(id).getOrElse { throw asCliError(it) }
    // Re-read afterwards rather than reporting only the outcome's counts: compaction's whole job
    // is rearranging the chunk table, and a caller has to be able to check the work rather than
    // take the command's word for it.
    val compacted = decks.sync(id).getOrElse { throw asCliError(it) }
    return result(
        DeckCompactResult(
            deck = compacted.toView(),
            id = id,
            merges = outcome.merges,
            cardsMoved = outcome.cardsMoved,
            chunksBefore = outcome.chunksBefore,
            chunksAfter = outcome.chunksAfter,
            complete = outcome.complete,
        ),
        "Compacted $id: ${outcome.merges} merges, ${outcome.cardsMoved} cards moved, " +
            "${outcome.chunksBefore} -> ${outcome.chunksAfter} chunks" +
            if (outcome.complete) "" else " (budget reached; run again to continue)",
    )
}

/**
 * A remote image reference — a URL, with no blob and no upload.
 *
 * Delegates to the shared factory rather than rebuilding the shape, because a ref written a second
 * way is a ref one of the clients cannot render — and a validation added to one copy is a
 * validation the other copies do not have. The URL is already checked by the time it arrives (see
 * `requireRenderableImageUrl`), so a rejection here is a bug rather than bad input.
 */
internal fun remoteImage(url: String): MediaRef.Image = requireNotNull(remoteImageRef(url)) {
    "not a renderable image URL: $url"
}

/**
 * The tags a deck carries once its declared languages have contributed theirs: what `--tag` asked
 * for, plus `"spanish"` and the `"language"` umbrella for a deck typed as English-to-Spanish.
 *
 * Deriving them is the whole reason a pair is worth declaring beyond the audio (#225). A tag
 * record is the only thing Loopky publishes that a network-wide index can answer questions about
 * (Architecture.md §7.7), so a deck that *says* it is Japanese in its manifest and carries no
 * label is invisible to tag browse, to `tag trending` and to anyone on Nexus looking for Japanese
 * decks — while the byte-identical deck published from a phone is not, because both ViewModels
 * route a language pick through [LanguageTags.retag]. Nothing reported the difference: the deck
 * published, `--json` said ok, and the only symptom was a search that came back empty somewhere
 * else entirely.
 *
 * **A deck that declares no pair gets nothing**, umbrella included — most decks are not language
 * decks, and `LanguageTags.forPair` is what keeps `"language"` off a deck of capital cities.
 *
 * The labels are ordinary author-removable tags rather than a reserved family, which is why
 * `deck edit --tag` is allowed to replace the set and drop them (see `editedTags`).
 *
 * [LanguageTags.retag] rather than `forPair` even here, where there is no previous pair to drop:
 * one function across create, import and edit is one dedupe and one ordering rule, and a
 * hand-typed `--tag language` beside a declared pair must not become two chips.
 */
internal fun deckTags(requested: List<String>, frontLang: String?, backLang: String?): List<Tag> =
    LanguageTags.retag(requested.normalizedTags(), null, null, frontLang, backLang).map(::Tag)

/** `--tag` as it is stored: trimmed, blanks dropped, first occurrence wins. */
internal fun List<String>.normalizedTags(): List<String> =
    mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()

/**
 * `--dry-run`: read, validate, report, write nothing.
 *
 * On `import`, `deck create` and `card add` — the three commands that take a file of cards. Each
 * runs its **own** path up to the write rather than borrowing another's, which is the whole point:
 * a pre-flight through a different parser answers a question nobody asked (#257, item 8).
 */
internal const val DRY_RUN_FLAG = "dry-run"

/** A `--name`/`--no-name` pair, since a deck opt-in has to be turnable *off* as well as on. */
internal fun Args.flag(name: String, default: Boolean): Boolean = flagOrNull(name) ?: default

internal fun List<CardFileRow>.toCards(deckId: String, now: Long): List<Card> =
    mapIndexed { index, row -> row.toCard(deckId, now, index) }
