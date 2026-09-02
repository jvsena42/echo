package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toLine
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.generateId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeckListResult(val decks: List<DeckView>, val count: Int)

@Serializable
data class DeckShowResult(val deck: DeckView)

@Serializable
data class DeckDeleteResult(val id: String, val deleted: Boolean)

@Serializable
data class DeckCompactResult(
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
 */
suspend fun deckCreate(
    args: Args,
    decks: DeckRepository,
    session: Session,
    onProgress: (String) -> Unit,
): CommandResult {
    val title = args.requireOption("title").trim()
    if (title.isEmpty()) throw CliError(ExitCode.Usage, "--title cannot be empty.")

    val deckId = generateId()
    val now = System.currentTimeMillis()
    val cards = args.option("from-file")
        ?.let { readCardFile(it).requireBothSides().toCards(deckId, now) }
        .orEmpty()

    val deck = Deck(
        id = deckId,
        authorPubky = session.identity.pubky,
        title = title,
        description = args.option("description")?.takeIf { it.isNotBlank() },
        coverEmoji = args.option("cover-emoji")?.takeIf { it.isNotBlank() },
        coverImageRef = args.option("cover-url")?.let(::remoteImage),
        tags = args.options("tag").map { Tag(it) },
        createdAt = now,
        updatedAt = now,
        cardCount = cards.size,
        source = DeckSource(kind = DeckSource.Kind.Import, importedAt = now),
        listenEnabled = args.flag("listen", default = false),
        speakEnabled = args.flag("speak", default = false),
        typeEnabled = args.flag("type", default = false),
        reverseEnabled = args.flag("reverse", default = false),
        frontLang = args.option("front-lang"),
        backLang = args.option("back-lang"),
    )

    val published = decks.publish(deck, cards) { progress ->
        onProgress("${progress.cardsWritten}/${progress.totalCards} cards, ${progress.chunksWritten}/${progress.totalChunks} chunks")
    }.getOrElse { throw asCliError(it) }

    return result(
        DeckShowResult(published.toView()),
        "Created ${published.id} — ${published.title} (${published.cardCount} cards)",
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
    return result(
        DeckCompactResult(
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
 * Built exactly as the apps build one (`path`/`sha256` empty, `image/jpeg`), because a ref written
 * a second way is a ref one of the two clients cannot render.
 */
internal fun remoteImage(url: String): MediaRef.Image =
    MediaRef.Image(path = "", mime = "image/jpeg", sha256 = "", width = null, height = null, url = url)

/** A `--name`/`--no-name` pair, since a deck opt-in has to be turnable *off* as well as on. */
internal fun Args.flag(name: String, default: Boolean): Boolean = when {
    has("no-$name") -> false
    has(name) -> true
    else -> default
}

internal fun List<CardFileRow>.toCards(deckId: String, now: Long): List<Card> =
    mapIndexed { index, row -> row.toCard(deckId, now, index) }
