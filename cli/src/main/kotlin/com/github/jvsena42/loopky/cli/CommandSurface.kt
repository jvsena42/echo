package com.github.jvsena42.loopky.cli

/**
 * The command surface as data, so a shell can be told about it.
 *
 * `loopky completion bash|zsh|fish` generates its script from this table and nothing else. That
 * is the whole reason the table exists rather than three checked-in scripts: a completion that
 * has drifted from the binary is worse than none — it offers a flag that is refused and hides one
 * that works, and nothing reports either. `CompletionTest` holds it against [Args] and the usage
 * block, so a new flag that is not described here fails the build.
 *
 * **Nothing here reaches the network, and that is a constraint rather than an omission.** The
 * obvious next feature is completing a deck id after `deck show`, and it would mean a homeserver
 * round trip on a keypress — a tab that hangs for a second, or forever on a dead session, in the
 * one place a user cannot interrupt without losing the line they were typing. Deck ids are
 * [Operand.Opaque]: the shell says what the word *is* and offers nothing.
 */
internal sealed interface OptionValue {

    /**
     * Takes nothing.
     *
     * Every one of these must also be in [Args.SWITCHES] — an option the parser does not know is
     * a switch consumes the next token as its value, so completing `--listen` before a deck id
     * would produce a command that silently reads the id as a language tag.
     */
    data object Switch : OptionValue

    /** Takes a value nothing local can guess: a title, a tag, a card front. */
    data object Text : OptionValue

    /** Takes a path. */
    data object Path : OptionValue

    /** Takes one of a closed set, which is the case worth completing. */
    data class OneOf(val choices: List<String>) : OptionValue
}

/** A `--long` option. There is no short form anywhere in this CLI; see [Args]. */
internal data class CliOption(
    val name: String,
    val summary: String,
    val value: OptionValue = OptionValue.Text,
)

/** What the non-option words after a command are. */
internal sealed interface Operand {

    data object None : Operand

    /** A file, so the shell completes paths. `import`, and nothing else. */
    data object Path : Operand

    /** A closed set of words. [name] is what the operand is called when it is shown. */
    data class OneOf(val name: String, val choices: List<String>) : Operand

    /**
     * Deck and card ids: known to the homeserver, unknowable here. The names are shown as a hint.
     *
     * A list rather than one string because arity is part of the surface — `card edit` takes two
     * words and the second is optional, which a machine-readable surface has to be able to say
     * (#240, finding 4) and `"deckId cardId"` could only imply.
     */
    data class Opaque(val required: List<String>, val optional: List<String> = emptyList()) : Operand {
        constructor(vararg required: String) : this(required.toList())

        val names: List<String> get() = required + optional
    }
}

/**
 * One command. [path] is exactly [Args.verb] — one word, or a group noun plus its verb.
 *
 * [needsSession], [writes] and [local] exist for one consumer: `loopky commands --json` derives
 * the exit codes a command can produce from them rather than listing them per command by hand
 * (see `exitCodes`). Derived, so the answer cannot drift from the table the way a hand-kept list
 * would — and useful, because "`tag trending` can never answer `session_expired`" is exactly the
 * kind of thing an agent otherwise learns by hitting it.
 */
internal data class CliCommand(
    val path: String,
    val summary: String,
    val operand: Operand = Operand.None,
    val options: List<CliOption> = emptyList(),
    /** Refuses with [ExitCode.NotSignedIn] rather than running when there is no session. */
    val needsSession: Boolean = true,
    /** Writes to the homeserver, so it can hit the quota wall. */
    val writes: Boolean = false,
    /** Runs before Koin: no FFI, no homeserver, no relay. `update`, `completion`, `commands`. */
    val local: Boolean = false,
    /** Codes this command alone can produce, which nothing about its shape implies. */
    val alsoExits: List<ExitCode> = emptyList(),
)

/**
 * Options accepted by every command, so they are offered on every command.
 *
 * `--version` is here because it genuinely is global: [Main] answers it before it looks at a verb,
 * so `loopky deck list --version` prints the version.
 */
internal val GLOBAL_OPTIONS = listOf(
    CliOption("json", "machine-readable output on stdout", OptionValue.Switch),
    CliOption("env", "which network to talk to", OptionValue.OneOf(listOf("staging", "production"))),
    CliOption("no-update-check", "do not look for a newer release on this invocation", OptionValue.Switch),
    CliOption("verbose", "debug logging on stderr", OptionValue.Switch),
    CliOption("help", "print the usage block", OptionValue.Switch),
    CliOption("version", "print the version and the --json schema", OptionValue.Switch),
)

/**
 * Switches the parser accepts and completion deliberately never offers.
 *
 * `--yes` and `--force` are taken and ignored: nothing in this client prompts, so there is nothing
 * to confirm, and they exist only so a caller in the habit of passing one is not refused. Offering
 * them would advertise a confirmation step that does not exist — and `--force` in particular reads
 * like it overrides something. Listed rather than dropped from [Args] so the pair stays a decision
 * with a reason attached, and so `CompletionTest` can tell "not described yet" from "not offered".
 */
internal val UNOFFERED_SWITCHES = setOf("yes", "force")

private val LANGUAGE_OPTIONS = listOf(
    CliOption("front-lang", "BCP-47 tag for the front side, such as en-US"),
    CliOption("back-lang", "BCP-47 tag for the back side, such as es-ES"),
)

private val STUDY_OPT_INS = listOf(
    CliOption("listen", "read the card aloud - needs a declared language pair", OptionValue.Switch),
    CliOption("speak", "grade a spoken answer - needs a declared language pair", OptionValue.Switch),
    CliOption("type", "type the answer instead of revealing it", OptionValue.Switch),
    CliOption("reverse", "ask each card in both directions", OptionValue.Switch),
)

private val STUDY_OPT_OUTS = STUDY_OPT_INS.map {
    CliOption("no-${it.name}", "turn ${it.name} off", OptionValue.Switch)
}

private val CARD_FIELDS = listOf(
    CliOption("front", "the front text"),
    CliOption("back", "the back text"),
    CliOption("front-image", "https URL for a picture on the front"),
    CliOption("back-image", "https URL for a picture on the back"),
    CliOption("from-file", "a TSV or JSONL card file instead of the flags above", OptionValue.Path),
    CliOption("check-images", "HEAD each picture URL and warn about the ones that are not images", OptionValue.Switch),
)

private val DECK_METADATA = listOf(
    CliOption("title", "the deck title"),
    CliOption("description", "the deck description"),
    CliOption("tag", "a tag - repeat for several, and it replaces rather than appends"),
    CliOption("cover-url", "https URL for the cover image"),
    CliOption("cover-emoji", "an emoji to use as the cover"),
)

/** The separators the paste parser accepts, spelled as `--separator` takes them. */
private val SEPARATORS = listOf(
    "auto", "tab", "comma", "semicolon", "pipe", "dash", "colon", "blank", "markdown",
)

/** The shells [completion] can generate for. */
internal val COMPLETION_SHELLS = listOf("bash", "zsh", "fish")

@Suppress("LongMethod")
internal fun cliCommands(): List<CliCommand> = listOf(
    CliCommand(
        path = "login",
        summary = "print a QR code for Pubky Ring and wait for approval",
        options = listOf(
            CliOption("export", "also print the session secret, for LOOPKY_SESSION", OptionValue.Switch),
            CliOption("qr-out", "write the QR code to a file", OptionValue.Path),
            CliOption("url-only", "print the pubkyauth URL and no QR code", OptionValue.Switch),
            CliOption("timeout", "give up after this many seconds rather than waiting forever"),
        ),
        // Sign-in is what produces a session; it cannot require one.
        needsSession = false,
        alsoExits = listOf(ExitCode.Timeout),
    ),
    CliCommand("logout", "forget the stored session"),
    CliCommand("whoami", "pubky, homeserver, capabilities, environment, and whether the session is live"),

    CliCommand("deck list", "every deck you have published"),
    CliCommand("deck show", "one deck and its manifest", Operand.Opaque("deckId")),
    CliCommand(
        path = "deck create",
        summary = "publish a new deck",
        options = DECK_METADATA +
            CliOption("id", "publish under this deck id rather than a fresh one") +
            CliOption(
                "if-not-exists",
                "with --id, return the deck that is already there rather than publishing",
                OptionValue.Switch,
            ) +
            CliOption("from-file", "a TSV or JSONL card file to publish with it", OptionValue.Path) +
            CliOption(
                "check-images",
                "HEAD each picture URL and warn about the ones that are not images",
                OptionValue.Switch,
            ) +
            STUDY_OPT_INS + LANGUAGE_OPTIONS,
        writes = true,
    ),
    CliCommand(
        path = "deck edit",
        summary = "change deck metadata - one manifest write, cards untouched",
        operand = Operand.Opaque("deckId"),
        options = DECK_METADATA + STUDY_OPT_INS + STUDY_OPT_OUTS + LANGUAGE_OPTIONS + listOf(
            CliOption("clear-tags", "remove every tag", OptionValue.Switch),
            CliOption("clear-cover", "remove the cover image and emoji", OptionValue.Switch),
        ),
        writes = true,
    ),
    CliCommand("deck delete", "delete a deck and its cards", Operand.Opaque("deckId"), writes = true),
    CliCommand("deck sync", "re-read a deck from the homeserver", Operand.Opaque("deckId")),
    CliCommand(
        path = "deck compact",
        summary = "fold away the holes card deletes leave in the chunk table",
        operand = Operand.Opaque("deckId"),
        writes = true,
    ),

    CliCommand(
        path = "card list",
        summary = "every card in a deck, or a page of them",
        operand = Operand.Opaque("deckId"),
        options = listOf(
            CliOption("limit", "how many cards to return - reads only the chunks a page needs"),
            CliOption("cursor", "carry on from a previous page, as its next_cursor said"),
            CliOption("missing-image", "only cards with no picture on either side", OptionValue.Switch),
            CliOption("has-image", "only cards with a picture", OptionValue.Switch),
        ),
    ),
    CliCommand("card add", "add one card, or a file of them", Operand.Opaque("deckId"), CARD_FIELDS, writes = true),
    CliCommand(
        path = "card edit",
        summary = "change a card - a field you omit is left alone",
        // The card id is optional: `--from-file` names one per row instead.
        operand = Operand.Opaque(required = listOf("deckId"), optional = listOf("cardId")),
        options = CARD_FIELDS,
        writes = true,
    ),
    CliCommand("card rm", "remove one card", Operand.Opaque("deckId", "cardId"), writes = true),

    CliCommand(
        path = "import",
        summary = "publish a deck from a pasted file or an Anki .apkg",
        operand = Operand.Path,
        options = listOf(
            CliOption("title", "the deck title"),
            CliOption("description", "the deck description"),
            CliOption("tag", "a tag - repeat for several"),
            CliOption("separator", "how the columns are split", OptionValue.OneOf(SEPARATORS)),
            CliOption("resume", "carry on an import that stopped part way", OptionValue.Switch),
            CliOption("dry-run", "report what would be published and write nothing", OptionValue.Switch),
            CliOption("front-field", "which .apkg field becomes the front, by number or name"),
            CliOption("back-field", "which .apkg field becomes the back, by number or name"),
            CliOption(
                "check-images",
                "HEAD each picture URL and warn about the ones that are not images",
                OptionValue.Switch,
            ),
        ) + STUDY_OPT_INS + LANGUAGE_OPTIONS,
        writes = true,
    ),

    CliCommand(
        path = "tag trending",
        summary = "what is being tagged on the Nexus indexer",
        options = listOf(CliOption("limit", "how many to return")),
        // A Nexus read is plain HTTP against a public index.
        needsSession = false,
    ),

    CliCommand(
        path = "batch",
        summary = "run a file of operations against one session, one JSON object per line",
        operand = Operand.Path,
        options = listOf(
            CliOption("stop-on-error", "stop at the first operation that fails", OptionValue.Switch),
        ),
        writes = true,
    ),

    CliCommand(
        path = "update",
        summary = "replace this binary with the newest release",
        options = listOf(CliOption("check", "ask without doing", OptionValue.Switch)),
        needsSession = false,
        local = true,
        alsoExits = listOf(ExitCode.Network, ExitCode.UpdateUnsupported),
    ),
    CliCommand(
        path = "completion",
        summary = "print a shell completion script",
        operand = Operand.OneOf("shell", COMPLETION_SHELLS),
        needsSession = false,
        local = true,
    ),
    CliCommand(
        path = "commands",
        summary = "this table, as JSON - every verb, its operands, its flags and its exit codes",
        needsSession = false,
        local = true,
    ),
)

/** The group nouns — `deck`, `card`, `tag` — mapped to the verbs that follow them. */
internal fun commandGroups(): Map<String, List<CliCommand>> = cliCommands()
    .filter { " " in it.path }
    .groupBy { it.path.substringBefore(' ') }

/** What a group noun on its own means, since it has no command of its own to describe it. */
private val GROUP_SUMMARIES = mapOf(
    "deck" to "create, read and edit decks",
    "card" to "add, edit and remove cards",
    "tag" to "read the public tag index",
)

/** The first word of every command, in the order they are listed: group nouns and one-word verbs. */
internal fun topLevelWords(): List<Pair<String, String>> {
    val groups = commandGroups()
    return cliCommands()
        .map { it.path.substringBefore(' ') }
        .distinct()
        .map { head ->
            head to (
                if (head in groups) {
                    GROUP_SUMMARIES.getValue(head)
                } else {
                    cliCommands().first { it.path == head }.summary
                }
                )
        }
}

/** Every option that consumes the token after it, which is what a completion has to know. */
internal fun valueTakingOptions(): List<String> =
    (cliCommands().flatMap { it.options } + GLOBAL_OPTIONS)
        .filter { it.value != OptionValue.Switch }
        .map { it.name }
        .distinct()
        .sorted()
