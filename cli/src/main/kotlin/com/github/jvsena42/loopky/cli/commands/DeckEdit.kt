package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.DeckView
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.toView
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Tag
import kotlinx.serialization.Serializable

@Serializable
data class DeckEditResult(
    val deck: DeckView,
    /**
     * False when every field the flags named already held that value, and nothing was written.
     *
     * Reported rather than folded into a plain success, for the reason `card add` reports
     * `skipped`: re-running a command is the documented recovery from the hourly session expiry
     * (#165), so a caller has to be able to tell "I applied your change" from "it was already so"
     * without re-reading the deck.
     */
    val changed: Boolean,
    /** Which manifest fields moved, named as [DeckView] names them. Empty when [changed] is false. */
    val fields: List<String> = emptyList(),
)

/**
 * Change what a deck *is* without touching what it holds.
 *
 * The hole this fills was a destructive one (#222). Until now the only way to add a cover, fix a
 * typo in a description or retype a deck's languages was `deck delete` followed by
 * `deck create --from-file` — which mints a **new deck id**, so every `pubky://…/decks/<id>/`
 * link breaks and every card goes back to new in the scheduler, and which needs the original card
 * file that a deck built with `card add` never had.
 *
 * One manifest write, whatever the deck's size: `updateMetadata` patches the record and
 * reconciles the tag records, and the chunk table and `card_count` are carried over from the deck
 * on the homeserver rather than from anything assembled here. Cards are never read and never
 * rewritten, so this costs the same on a 20k-card deck as on an empty one.
 *
 * Four rules, each matching something the surface already does:
 *
 * - **An absent flag leaves the field alone** — `card edit`'s rule, for the same reason: a caller
 *   scripting one field must not wipe the eleven it did not mention.
 * - **An explicitly empty value clears one field** (`--description=`), which is `card edit --back=`
 *   again. `--clear-tags` and `--clear-cover` exist because those two are not single values:
 *   `--tag ""` would have to mean both "no tags" and "one blank tag", and a cover is an emoji and
 *   an image that layer.
 * - **`--tag` replaces rather than appends**, so the command is idempotent and a script can say
 *   "the tags are exactly these" — which appending makes impossible without a delete. That is the
 *   same property `card add`'s dedupe exists for: the session dies hourly (#165) and re-running is
 *   the documented recovery, so a command that grows its own output on a retry is one a session
 *   expiry corrupts.
 * - **Nothing to change is not a write.** A manifest write bumps `updated_at`, which is what every
 *   follower's "the author published changes" badge reads, so re-running an edit that has already
 *   landed reports `changed: false` and leaves the record alone.
 *
 * The study opt-ins are in here with the metadata rather than held back, because turning one off
 * costs no progress: review state is keyed by `card_id` alone and the modes decide only how a card
 * is *presented*. Reverse's pairing is session-local and persisted nowhere, so even that one has
 * nothing to lose.
 */
suspend fun deckEdit(args: Args, decks: DeckRepository): CommandResult {
    val id = args.requireWord(2, "deckId")
    if (EDIT_FLAGS.none { args.has(it) }) {
        throw CliError(
            ExitCode.Usage,
            "deck edit needs at least one field to change. " +
                "Give --title, --description, --cover-url, --cover-emoji, --tag, --clear-tags, " +
                "--clear-cover, --front-lang, --back-lang, or a study-mode flag.",
        )
    }

    // The homeserver's copy, not this process's idea of it — and `updateMetadata` reads the deck
    // out of the cache this populates, so a `deck edit` without it patches nothing.
    val current = decks.sync(id).getOrElse { throw asCliError(it) }
    val edited = current.applying(args)

    val fields = edited.changedFieldsFrom(current)
    if (fields.isEmpty()) {
        return result(
            DeckEditResult(current.toView(), changed = false),
            "No change to ${current.id} — every field given already held that value.",
        )
    }

    val updated = decks.updateMetadata(edited.copy(updatedAt = System.currentTimeMillis()))
        .getOrElse { throw asCliError(it) }
    return result(
        DeckEditResult(updated.toView(), changed = true, fields = fields),
        "Updated ${updated.id} — ${fields.joinToString(", ")}",
    )
}

private fun Deck.applying(args: Args): Deck {
    val clearCover = args.has("clear-cover")
    if (clearCover && (args.has("cover-url") || args.has("cover-emoji"))) {
        throw CliError(ExitCode.Usage, "--clear-cover and --cover-url/--cover-emoji say opposite things.")
    }

    val frontLang = args.text("front-lang", frontLang)
    val backLang = args.text("back-lang", backLang)

    return copy(
        title = args.text("title", title) ?: throw CliError(ExitCode.Usage, "--title cannot be empty."),
        description = args.text("description", description),
        coverEmoji = if (clearCover) null else args.text("cover-emoji", coverEmoji),
        coverImageRef = args.editedCover(clearCover, coverImageRef),
        tags = args.editedTags(this, frontLang, backLang),
        listenEnabled = args.flag("listen", default = listenEnabled),
        speakEnabled = args.flag("speak", default = speakEnabled),
        typeEnabled = args.flag("type", default = typeEnabled),
        reverseEnabled = args.flag("reverse", default = reverseEnabled),
        frontLang = frontLang,
        backLang = backLang,
    )
}

/**
 * The cover image after the edit: unchanged unless `--cover-url` or `--clear-cover` said so.
 *
 * The URL goes through [checkedImageUrl] like every other picture this client stores, so an
 * `http://` cover is refused here rather than written into a manifest and rendered as a blank
 * tile on both apps.
 */
private fun Args.editedCover(clearCover: Boolean, current: MediaRef.Image?): MediaRef.Image? = when {
    clearCover -> null
    !has("cover-url") -> current
    else -> text("cover-url", null)?.checkedImageUrl("--cover-url")?.let(::remoteImage)
}

/**
 * The tag set after the edit: `--clear-tags` empties it, `--tag` **replaces** it, and anything
 * else leaves it alone.
 *
 * Replacing rather than appending is what makes the command idempotent, and idempotence is not a
 * nicety here: the session dies hourly (#165) and re-running is the documented recovery, so a
 * command that grew its own tag list on every retry would be one a session expiry corrupts.
 *
 * The labels a declared language contributes are reconciled **only when the invocation names a
 * pair** (`LanguageTags.retag` — the drop is the half that is easy to miss: retyping a deck away
 * from Spanish has to take `"spanish"` off it). Doing it unconditionally would put `"language"`
 * back on a deck the caller had just emptied with `--clear-tags`, and these are ordinary
 * author-removable tags rather than a reserved family, so that gesture has to be believed.
 *
 * Named rather than *moved*, which is the narrower rule this started with. Restating the pair a
 * deck already has is how a deck published before the CLI derived labels at all (#225) gains
 * them — otherwise the only way was to retype it to a different *region* of the same language and
 * back, which is a trick rather than a command. It costs nothing when there is nothing to do:
 * a reconciliation that changes no tag is not a write, because `changedFieldsFrom` diffs.
 */
private fun Args.editedTags(deck: Deck, frontLang: String?, backLang: String?): List<Tag> {
    val clearTags = has("clear-tags")
    val requested = options("tag").normalizedTags()
    if (clearTags && requested.isNotEmpty()) {
        throw CliError(ExitCode.Usage, "--clear-tags and --tag say opposite things; pass one of them.")
    }

    val tags = when {
        clearTags -> emptyList()
        requested.isNotEmpty() -> requested
        else -> deck.tags.map { it.value }
    }
    val named = has("front-lang") || has("back-lang")
    val labelled = if (named) {
        LanguageTags.retag(tags, deck.frontLang, deck.backLang, frontLang, backLang)
    } else {
        tags
    }
    return labelled.map { Tag(it) }
}

/**
 * Which manifest fields this edit actually moves, named as [DeckView] names them.
 *
 * Computed by diffing rather than by remembering which flags were passed, because the two are not
 * the same question: `--title` with the title it already has changes nothing, and reporting it as
 * a change would make `changed` mean "you typed a flag" — which nothing can verify against.
 */
private fun Deck.changedFieldsFrom(before: Deck): List<String> = listOfNotNull(
    "title".takeIf { title != before.title },
    "description".takeIf { description != before.description },
    "cover_emoji".takeIf { coverEmoji != before.coverEmoji },
    "cover_image".takeIf { coverImageRef != before.coverImageRef },
    "tags".takeIf { tags != before.tags },
    "front_lang".takeIf { frontLang != before.frontLang },
    "back_lang".takeIf { backLang != before.backLang },
    "listen_enabled".takeIf { listenEnabled != before.listenEnabled },
    "speak_enabled".takeIf { speakEnabled != before.speakEnabled },
    "type_enabled".takeIf { typeEnabled != before.typeEnabled },
    "reverse_enabled".takeIf { reverseEnabled != before.reverseEnabled },
)

/**
 * A nullable text field under an overlay: [current] when the option was not given at all, null
 * when it was given empty, and the trimmed value otherwise.
 *
 * The empty case is the one worth spelling out. `option(name)` cannot tell "absent" from
 * `--description=` on its own — both read as "no value here" — and collapsing them would make
 * clearing a field impossible while making every partial edit a wipe.
 */
private fun Args.text(name: String, current: String?): String? =
    if (!has(name)) current else option(name)?.trim()?.takeIf { it.isNotEmpty() }

/** Every flag that names a field, so "you changed nothing" can be a usage error rather than a write. */
private val EDIT_FLAGS = listOf(
    "title", "description", "cover-url", "cover-emoji", "tag", "clear-tags", "clear-cover",
    "front-lang", "back-lang",
    "listen", "no-listen", "speak", "no-speak", "type", "no-type", "reverse", "no-reverse",
)
