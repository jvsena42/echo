package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CardView
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toLine
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardListResult(
    @SerialName("deck_id") val deckId: String,
    val cards: List<CardView>,
    val count: Int,
)

@Serializable
data class CardWriteResult(
    @SerialName("deck_id") val deckId: String,
    val cards: List<CardView>,
    val written: Int,
    /**
     * Rows that matched a card already in the deck and were therefore not written again.
     *
     * `card add` twice with the same front and back has to be *detectable*, or an agent retrying
     * after a session expiry double-posts every card it had already written (#54). Reported rather
     * than merely skipped, so a caller can tell "already there" from "did nothing".
     */
    val skipped: Int = 0,
    /**
     * Cards this call actually removed — 0 when the id was not in the deck.
     *
     * `card rm` used to answer identically whether it deleted a card or did nothing: `deleteCard`
     * treats a missing card as a no-op and the CLI reported that as success, so an agent pruning ids
     * could not tell which were real without re-reading the deck between every delete.
     *
     * Reported rather than turned into an error, and the asymmetry with `card edit` — which does
     * return `not_found` — is deliberate: removing a card that is already gone leaves the deck in the
     * state the caller asked for, so failing it would break the retry-after-expiry pattern the whole
     * surface is built around. An *edit* has no such reading.
     */
    val removed: Int = 0,
    @SerialName("card_count") val cardCount: Int = 0,
)

suspend fun cardList(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val list = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }
        .inStudyOrder()
        .map { it.toView() }
    return result(
        CardListResult(deckId, list, list.size),
        if (list.isEmpty()) "No cards." else list.joinToString("\n") { it.toLine() },
    )
}

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
suspend fun cardAdd(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }

    val rows = (
        args.option("from-file")?.let(::readCardFile) ?: listOf(
            CardFileRow(
                front = args.option("front"),
                back = args.option("back"),
                frontImageUrl = args.option("front-image")?.checkedImageUrl("--front-image"),
                backImageUrl = args.option("back-image")?.checkedImageUrl("--back-image"),
            ).also {
                if (it.isEmpty) throw CliError(ExitCode.Usage, "Give --front/--back, or --from-file.")
            },
        )
        ).requireBothSides()

    val seen = existing.mapTo(mutableSetOf()) { it.identityOf() }
    val now = System.currentTimeMillis()
    val written = mutableListOf<Card>()
    var skipped = 0
    var latest = deck

    for (row in rows) {
        // No ord is computed here on purpose. `upsertCard` assigns one from the chunk the card
        // lands in and ignores whatever the caller sent, so an ord invented here would be dead
        // weight that *looks* meaningful — which is precisely how this command came to report a
        // number the homeserver never stored.
        val card = row.toCard(deckId, now, index = 0)
        if (!seen.add(card.identityOf())) {
            skipped++
            continue
        }
        latest = decks.upsertCard(deckId, card).getOrElse { throw asCliError(it) }
        written += cards.stored(deckId, card)
    }

    return result(
        // Named, every one of them. Constructed positionally, this call read
        // `(…, written.size, skipped, latest.cardCount)` against a class whose fourth and fifth
        // parameters are `skipped` and **`removed`** — so the deck's size was reported as the number
        // of cards this call deleted. `removed` was inserted between them by 9c0492524, which changed
        // what those positions mean without failing to compile.
        CardWriteResult(
            deckId = deckId,
            cards = written.map { it.toView() },
            written = written.size,
            skipped = skipped,
            cardCount = latest.cardCount,
        ),
        "Added ${written.size} card(s)" + if (skipped > 0) ", skipped $skipped already present" else "",
    )
}

/**
 * Change cards that already exist, one or a fileful. A field that is not given is left alone rather
 * than cleared; clearing a side needs an explicit empty value (`--back=`), because a batch file that
 * omitted a column would otherwise silently wipe it on every row it touched.
 */
suspend fun cardEdit(args: Args, decks: DeckRepository, cards: CardRepository): CommandResult {
    val deckId = args.requireWord(2, "deckId")
    val deck = decks.sync(deckId).getOrElse { throw asCliError(it) }
    val existing = cards.fetchByDeck(deck).getOrElse { throw asCliError(it) }.associateBy { it.id }

    val rows = args.option("from-file")?.let(::readCardFile) ?: listOf(
        CardFileRow(
            id = args.requireWord(3, "cardId"),
            front = args.option("front"),
            back = args.option("back"),
            frontImageUrl = args.option("front-image")?.checkedImageUrl("--front-image"),
            backImageUrl = args.option("back-image")?.checkedImageUrl("--back-image"),
        ),
    )

    val now = System.currentTimeMillis()
    val written = mutableListOf<Card>()
    var latest = deck
    for (row in rows) {
        val id = row.id ?: throw CliError(
            ExitCode.Usage,
            "Every row of a card edit --from-file needs an id. Read them with `card list --json`.",
        )
        val current = existing[id]
            ?: throw CliError(ExitCode.NotFound, "Deck $deckId has no card $id.")
        val updated = current.applying(row, now).requireBothSides(id)
        latest = decks.upsertCard(deckId, updated).getOrElse { throw asCliError(it) }
        written += cards.stored(deckId, updated)
    }

    return result(
        CardWriteResult(
            deckId = deckId,
            cards = written.map { it.toView() },
            written = written.size,
            cardCount = latest.cardCount,
        ),
        "Edited ${written.size} card(s)",
    )
}

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
