package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.cliJson
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import kotlinx.coroutines.delay

/**
 * Add one card, or a fileful.
 *
 * **Idempotent by front/back.** A row whose two sides already exist is skipped and counted, never
 * written twice. The session dies after about an hour and nothing renews it (#165), so an agent's
 * normal recovery is to re-run the command — and a surface where re-running duplicates the work is
 * one where a session expiry costs the deck rather than the retry.
 *
 * The batch goes through one `upsertCard` per card because that owns the chunk write and the manifest
 * patch together. What a batch saves over a shell loop is the process start, the session round trip
 * and the deck read.
 *
 * **The dedupe costs a full card read** — ~200 chunk requests on a 20k-card deck before the first
 * write. There is no index to ask instead, and skipping it would trade the retry guarantee for speed.
 * That is the other reason to use `--from-file`: the read is paid once for the whole batch.
 */
suspend fun cardAdd(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }

    val rows = (
        args.option("from-file")?.let { readCardFile(it, onNote) } ?: listOf(
            CardFileRow(
                front = args.option("front"),
                back = args.option("back"),
                frontImageUrl = args.option("front-image")?.checkedImageUrl("--front-image", onNote),
                backImageUrl = args.option("back-image")?.checkedImageUrl("--back-image", onNote),
            ).also {
                if (it.isEmpty) throw CliError(ExitCode.Usage, "Give --front/--back, or --from-file.")
            },
        )
        ).requireBothSides()

    val seen = existing.mapTo(mutableSetOf()) { it.identityOf() }
    val now = System.currentTimeMillis()
    val planned = mutableListOf<PlannedWrite>()
    var skipped = 0

    for ((index, row) in rows.withIndex()) {
        // No ord is computed here on purpose. `upsertCard` assigns one from the chunk the card
        // lands in and ignores whatever the caller sent, so an ord invented here would be dead
        // weight that *looks* meaningful — which is precisely how this command came to report a
        // number the homeserver never stored.
        val card = row.toCard(deckId, now, index = 0)
        if (!seen.add(card.identityOf())) {
            skipped++
            continue
        }
        planned += PlannedWrite(row = index + 1, card = card)
    }

    val checks = planned.checkedImages(args, onNote)
    return applyBatch(deckId, deck, decks, cards, planned, skipped, "Added", checks)
}

/**
 * Change cards that already exist, one or a fileful. A field that is not given is left alone rather
 * than cleared; clearing a side needs an explicit empty value (`--back=`), because a batch file that
 * omitted a column would otherwise silently wipe it on every row it touched.
 *
 * **Idempotent, which is what a `--resume` would have been.** A row whose fields already hold what
 * it asks for is skipped rather than rewritten, so re-running the same file after a failure applies
 * only what is missing — no cursor to keep, nothing to pass, and no `updated_at` churn on rows that
 * did not change. `card edit --from-file` stopping at the first failure with 35 of 665 rows applied
 * and no way to resume was the most expensive finding in #229.
 *
 * **Everything is checked before anything is written.** Ids, both-sides, image URLs: the whole file
 * is resolved into cards first, so a bad row 400 fails the command with the homeserver untouched
 * rather than 399 rows in.
 */
suspend fun cardEdit(
    args: Args,
    decks: DeckRepository,
    cards: CardRepository,
    onNote: (String) -> Unit = System.err::println,
): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }.associateBy { it.id }

    val rows = args.option("from-file")?.let { readCardFile(it, onNote) } ?: listOf(
        CardFileRow(
            id = args.requireWord(3, "cardId"),
            front = args.option("front"),
            back = args.option("back"),
            frontImageUrl = args.option("front-image")?.checkedImageUrl("--front-image", onNote),
            backImageUrl = args.option("back-image")?.checkedImageUrl("--back-image", onNote),
        ),
    )

    val now = System.currentTimeMillis()
    val planned = mutableListOf<PlannedWrite>()
    var skipped = 0
    for ((index, row) in rows.withIndex()) {
        val id = row.id ?: throw CliError(
            ExitCode.Usage,
            "Every row of a card edit --from-file needs an id. Read them with `card list --json`.",
        )
        val current = existing[id]
            ?: throw CliError(ExitCode.NotFound, "Deck $deckId has no card $id.")
        val updated = current.applying(row, now).requireBothSides(id)
        // `updatedAt` is stamped on every row, so comparing the whole card would never match.
        if (updated.sameContentAs(current)) {
            skipped++
            continue
        }
        planned += PlannedWrite(row = index + 1, card = updated)
    }

    val checks = planned.checkedImages(args, onNote)
    return applyBatch(deckId, deck, decks, cards, planned, skipped, "Edited", checks)
}

/** One row of a batch, resolved into the card it will write. [row] is 1-based, as the file counts. */
private class PlannedWrite(val row: Int, val card: Card)

/**
 * `--check-images` over the pictures this batch is about to write, or nothing.
 *
 * The rows the batch **skipped** are deliberately not probed: their pictures are already on the
 * homeserver and were not asked about, so checking them would turn a two-row edit of a 4,000-card
 * deck into 4,000 requests.
 */
private suspend fun List<PlannedWrite>.checkedImages(args: Args, onNote: (String) -> Unit): List<ImageCheck> {
    if (!args.checksImages()) return emptyList()
    val urls = flatMap { listOfNotNull(it.card.front.imageRef?.url, it.card.back.imageRef?.url) }
    return checkImageUrls(urls, onNote)
}

/**
 * Write a planned batch, and report what landed whether or not all of it did.
 *
 * Three rules, all from the same failure (#229, item 2): a 665-row `card edit --from-file` that
 * 500'd after 35 writes, said nothing about the 35, and had no way to pick up where it stopped.
 *
 * - **A row failure does not end the batch.** The rows after it are attempted, because the ones
 *   that worked when sent singly were the same rows.
 * - **A batch-ending failure does.** An expired session, a full disk or an unsupported host will
 *   refuse every remaining row identically, and 600 more round trips against it buy nothing — so
 *   the batch stops and reports how many it never reached. So does a run of consecutive failures,
 *   which is what a homeserver in trouble looks like from here.
 * - **A failed batch still reports what it wrote.** The result travels on the failure envelope as
 *   `data`, ids included, so the caller knows which rows are on the homeserver.
 */
@Suppress("LongParameterList")
private suspend fun applyBatch(
    deckId: String,
    deck: Deck,
    decks: DeckRepository,
    cards: CardRepository,
    planned: List<PlannedWrite>,
    skipped: Int,
    verb: String,
    imageChecks: List<ImageCheck>,
): CommandResult {
    val written = mutableListOf<Card>()
    val failures = mutableListOf<CardWriteFailure>()
    var latest = deck
    var consecutiveFailures = 0
    var stopped: CliError? = null
    var index = 0

    while (index < planned.size) {
        val entry = planned[index]
        val result = writeWithRetry(decks, deckId, entry.card)
        result.onSuccess {
            latest = it
            written += cards.stored(deckId, entry.card)
            consecutiveFailures = 0
        }.onFailure { error ->
            val failure = asCliError(error)
            failures += CardWriteFailure(
                row = entry.row,
                cardId = entry.card.id,
                code = failure.exitCode.json,
                message = failure.message.orEmpty(),
            )
            consecutiveFailures++
            if (failure.exitCode in BATCH_ENDING || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopped = failure
            }
        }
        index++
        if (stopped != null) break
    }

    val notAttempted = planned.size - index
    val payload = CardWriteResult(
        deckId = deckId,
        cards = written.map { it.toView() },
        written = written.size,
        skipped = skipped,
        cardCount = latest.cardCount,
        failed = failures.size,
        failures = failures,
        notAttempted = notAttempted,
        imageChecks = imageChecks,
    )
    val text = buildString {
        append("$verb ${written.size} card(s)")
        if (skipped > 0) append(", skipped $skipped already ${if (verb == "Added") "present" else "up to date"}")
        if (failures.isNotEmpty()) append(", FAILED ${failures.size}")
        if (notAttempted > 0) append(", $notAttempted not attempted")
    }
    if (failures.isEmpty()) return result(payload, text)

    throw CliError(
        failures.first().exitCode(),
        "$text. First failure was row ${failures.first().row} (card ${failures.first().cardId}): " +
            "${failures.first().message} Re-run the same file — rows already applied are skipped.",
        cliJson.encodeToJsonElement(CardWriteResult.serializer(), payload),
    )
}

/**
 * One card write, retried through a homeserver that answered 5xx.
 *
 * The shared layer already retries an expiry, a 429 and an unreachable session round trip
 * (`withWriteRetry`); a 500 is the gap, and it is the one this saw. Bounded and short: three
 * attempts, because a homeserver that is still 500ing after two seconds is not going to be talked
 * round by a fourth, and a 665-row batch cannot afford a long backoff per row.
 */
private suspend fun writeWithRetry(decks: DeckRepository, deckId: String, card: Card): Result<Deck> {
    var backoff = RETRY_BACKOFF_MS
    repeat(WRITE_ATTEMPTS - 1) {
        val result = decks.upsertCard(deckId, card)
        val error = result.exceptionOrNull() ?: return result
        if (ExitCode.of(error) != ExitCode.ServerError) return result
        delay(backoff)
        backoff *= 2
    }
    return decks.upsertCard(deckId, card)
}

private fun CardWriteFailure.exitCode(): ExitCode =
    ExitCode.entries.firstOrNull { it.json == code } ?: ExitCode.Internal

/**
 * Failures that will refuse every remaining row the same way, so attempting them buys nothing but
 * round trips. [ExitCode.NotFound] is deliberately absent — that is one missing card, not a dead
 * batch.
 */
private val BATCH_ENDING = setOf(
    ExitCode.NotSignedIn,
    ExitCode.SessionExpired,
    ExitCode.StorageFull,
    ExitCode.EnvironmentMismatch,
    ExitCode.UnsupportedHost,
)

/** What a homeserver in trouble looks like from here: not one bad row, but a batch worth stopping. */
private const val MAX_CONSECUTIVE_FAILURES = 5

private const val WRITE_ATTEMPTS = 3
private const val RETRY_BACKOFF_MS = 500L

suspend fun cardRemove(args: Args, decks: DeckRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val cardId = args.requireWord(3, "cardId")
    // The manifest read that makes the answer possible. `deleteCard` returns the deck either way,
    // so without a count from before there is nothing to compare against — and the chunk table is
    // where `cardCount` comes from, so this is one record, not the deck's cards.
    val before = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val after = decks.deleteCard(deckId, cardId).getOrElse { throw asCliError(it) }
    val removed = (before.cardCount - after.cardCount).coerceAtLeast(0)
    return result(
        CardWriteResult(
            deckId = deckId,
            cards = emptyList(),
            written = 0,
            removed = removed,
            cardCount = after.cardCount,
        ),
        if (removed > 0) {
            "Removed $cardId from $deckId (${after.cardCount} cards left)"
        } else {
            "Deck $deckId has no card $cardId — nothing removed (${after.cardCount} cards)."
        },
    )
}

/**
 * The card as the homeserver has it, falling back to what was sent.
 *
 * `upsertCard` **discards the caller's `ord`** and recomputes one from the chunk it lands in, so
 * echoing the local `Card` reports intent rather than result — on an empty deck the CLI said 1000
 * where 0 was stored. For a channel whose purpose is diffing intent against result, that is the one
 * thing it set out not to do. A cache read, not a round trip: the write just populated it.
 */
private suspend fun CardRepository.stored(deckId: String, sent: Card): Card =
    get(deckId, sent.id) ?: sent

/**
 * Refuse a card an edit has emptied one side of.
 *
 * `--back=` is the documented way to clear a side, so this is the supported gesture rather than
 * misuse — and `upsertCard` `require`s both sides, throwing an `IllegalArgumentException` that
 * `toErrorReason` classifies `Unknown`, so the user got exit 1 "internal" plus a Kotlin assertion
 * string for a blank column in their own file. An agent told "internal" retries; told exit 9 it fixes
 * its input.
 *
 * The row-level guard cannot cover this: an edit row is *allowed* to be partial, so what has to be
 * checked is the card the edit produces.
 */
private fun Card.requireBothSides(id: String): Card = also {
    if (front.isEmpty || back.isEmpty) {
        throw CliError(
            ExitCode.BadInput,
            "Card $id would be left with an empty ${if (front.isEmpty) "front" else "back"}; " +
                "a card needs both sides. An image counts as a side.",
        )
    }
}

/**
 * Whether an edit would leave the card exactly as it already is.
 *
 * `updatedAt` is stamped on every row before this is asked, so comparing whole cards would never
 * match — and `ord` belongs to the chunk the card lives in rather than to the edit. What is left is
 * what a card file can express, which is what makes re-running the same file cheap instead of a
 * rewrite of every row (#229, item 2).
 */
private fun Card.sameContentAs(other: Card): Boolean =
    front == other.front && back == other.back

private fun Card.applying(row: CardFileRow, now: Long): Card = copy(
    updatedAt = now,
    front = front.applying(row.front, row.frontImageUrl),
    back = back.applying(row.back, row.backImageUrl),
)

private fun CardSide.applying(text: String?, imageUrl: String?): CardSide = CardSide(
    text = if (text == null) this.text else text.takeIf { it.isNotBlank() },
    imageRef = when {
        imageUrl == null -> imageRef
        imageUrl.isBlank() -> null
        else -> remoteImage(imageUrl)
    },
    audioRef = audioRef,
)

/**
 * What makes two cards "the same card" for a repeated `card add` or an `import --resume`.
 *
 * Text and image together, normalised for whitespace and case. Text alone would collapse two cards
 * asking the same question about different pictures — a flags deck is exactly that — and including
 * the ord or the id would defeat the check, since both are freshly minted.
 *
 * One definition, shared with `import`: a `--resume` that dedupes differently from the `card add`
 * before it writes the duplicates the mechanism exists to prevent.
 */
internal fun Card.identityOf(): String = listOf(
    front.text.orEmpty().trim().lowercase(),
    back.text.orEmpty().trim().lowercase(),
    front.imageRef?.let { it.url ?: it.sha256 }.orEmpty(),
    back.imageRef?.let { it.url ?: it.sha256 }.orEmpty(),
).joinToString(IDENTITY_SEPARATOR)

/**
 * The separator between a card's four identity fields: `NUL`, because no card text can contain it,
 * so `"ab" + "c"` and `"a" + "bc"` cannot collide.
 *
 * **Written as an escape, and that is the point of the constant.** It used to be a literal NUL *byte*
 * in the source, which made this file binary as far as `grep` is concerned — `grep -rn
 * CardWriteResult` found nothing at all, silently, and exited 1.
 */
private const val IDENTITY_SEPARATOR = "\u0000"
