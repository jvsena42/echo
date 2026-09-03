package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.cardAdd
import com.github.jvsena42.loopky.cli.commands.cardEdit
import com.github.jvsena42.loopky.cli.commands.cardList
import com.github.jvsena42.loopky.cli.commands.cardRemove
import com.github.jvsena42.loopky.cli.commands.deckCompact
import com.github.jvsena42.loopky.cli.commands.deckCreate
import com.github.jvsena42.loopky.cli.commands.deckDelete
import com.github.jvsena42.loopky.cli.commands.deckList
import com.github.jvsena42.loopky.cli.commands.deckShow
import com.github.jvsena42.loopky.cli.commands.deckSync
import com.github.jvsena42.loopky.cli.commands.import
import com.github.jvsena42.loopky.cli.commands.login
import com.github.jvsena42.loopky.cli.commands.logout
import com.github.jvsena42.loopky.cli.commands.tagTrending
import com.github.jvsena42.loopky.cli.commands.whoami
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import kotlin.system.exitProcess

/**
 * `loopky` — a headless Loopky client, so an agent can create and manage decks (#54).
 *
 * Three properties everything here is arranged around:
 *
 * - **stdout is a machine channel.** Results go there and nothing else does; progress, prompts,
 *   the QR code and every log line go to stderr. One stray line on stdout makes `--json`
 *   undecodable, which is why the JVM `Log` actual writes to stderr even for debug.
 * - **Nothing prompts.** There is no interactive question anywhere, including the nice ones: the
 *   "announce this deck?" confirmation is gone *by construction* rather than behind a flag,
 *   because this client never requests the capability a post would need (see `CLI_CAPABILITIES`).
 * - **The exit code is the primary result.** See [ExitCode] — session expiry has its own, because
 *   it is hourly and unrecoverable without a human, and an agent that cannot tell it from a
 *   network wobble either retries forever or gives up on a working network.
 */
fun main(argv: Array<String>) {
    val exit = runCatching { run(argv) }.getOrElse { error ->
        // Nothing should reach here; if it does, say so honestly rather than exiting 0.
        System.err.println("loopky: ${error::class.simpleName}: ${error.message}")
        ExitCode.Internal
    }
    exitProcess(exit.code)
}

@Suppress("ReturnCount")
private fun run(argv: Array<String>): ExitCode {
    val args = runCatching { Args.parse(argv) }.getOrElse { error ->
        System.err.println("loopky: ${error.message}")
        System.err.println(USAGE)
        return ExitCode.Usage
    }

    // Version before help, and both before the empty-command check: `loopky --version` has no
    // positional words, so the "you gave me nothing" branch would answer it with the usage block.
    if (args.has("version")) {
        println(VERSION)
        return ExitCode.Ok
    }
    if (args.words.isEmpty() || args.has("help")) {
        println(USAGE)
        return if (args.has("help")) ExitCode.Ok else ExitCode.Usage
    }

    Log.debugEnabled = args.has("verbose")
    val environment = CliEnvironment.resolve(args)
    try {
        // Inside the boundary, not before it: starting Koin resolves `PubkyClient`, which is where
        // a host outside the shipped matrix fails at `Native.load`.
        val koin = startCli(environment)
        val result = runBlocking { dispatch(args, koin.identity(), koin, environment) }
        emit(args, environment, args.verb, result)
        return ExitCode.Ok
    } catch (error: CliError) {
        fail(args, environment, args.verb, error.exitCode, error.message.orEmpty())
        return error.exitCode
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // `Throwable`, not `Exception`. An x86-64 Mac or a Windows box fails at `Native.load` with
        // `UnsatisfiedLinkError` / `ExceptionInInitializerError` — both `Error`s — and catching
        // only `Exception` let them past `fail()` to a bare exit 1 with **nothing on stdout**.
        // That breaks the "results and failures both go there as --json" contract for precisely
        // the case the README and `Platform.jvm.kt` both name as expected.
        val code = ExitCode.of(error)
        Log.e(TAG, "command failed", error)
        fail(args, environment, args.verb, code, error.message ?: error::class.simpleName.orEmpty())
        return code
    } finally {
        // Koin may never have started, in which case stopping it throws over the failure we are
        // already reporting.
        runCatching { stopCli() }
    }
}

/**
 * Route to a command function.
 *
 * Every arm is one call to one function that takes plain values and returns its `--json` shape.
 * That is deliberate and load-bearing: a remote MCP server serves an audience the CLI cannot reach
 * at all — a chat-only agent has no shell and no egress — and it should be a binding over these
 * functions rather than a second implementation of them (#54, open question 2).
 */
@Suppress("CyclomaticComplexMethod")
private suspend fun dispatch(
    args: Args,
    identity: IdentityRepository,
    koin: Koin,
    environment: CliEnvironment,
): CommandResult {
    val progress: (String) -> Unit = { line -> if (!args.has("json")) System.err.println(line) }
    return when (val verb = args.verb) {
        "login" -> login(args, identity, environment, { line -> emitEvent(args, line) }, System.err::println)
        "logout" -> logout(identity)
        "whoami" -> whoami(identity, koin.get<PubkyClient>(), environment)

        "deck list" -> authed(identity, environment) { deckList(koin.decks()) }
        "deck show" -> authed(identity, environment) { deckShow(args, koin.decks()) }
        "deck create" -> authed(identity, environment) { session ->
            deckCreate(args, koin.decks(), session, progress)
        }
        "deck delete" -> authed(identity, environment) { deckDelete(args, koin.decks()) }
        "deck sync" -> authed(identity, environment) { deckSync(args, koin.decks(), koin.cards()) }
        "deck compact" -> authed(identity, environment) { deckCompact(args, koin.decks()) }

        "card list" -> authed(identity, environment) { cardList(args, koin.decks(), koin.cards()) }
        "card add" -> authed(identity, environment) { cardAdd(args, koin.decks(), koin.cards()) }
        "card edit" -> authed(identity, environment) { cardEdit(args, koin.decks(), koin.cards()) }
        "card rm" -> authed(identity, environment) { cardRemove(args, koin.decks()) }

        "import" -> authed(identity, environment) { session ->
            import(args, koin.get<ImportRepository>(), koin.decks(), koin.cards(), session, progress)
        }

        // No session: a Nexus read is plain HTTP against a public index.
        "tag trending" -> tagTrending(args, koin.get<TagRepository>(), environment)

        // The message stays one line. `--json` puts it in an `error.message`, and pasting a
        // 60-line usage block into a JSON string helps nobody parsing it; `--help` is where the
        // usage lives, and the human path prints it below.
        else -> throw CliError(ExitCode.Usage, "Unknown command '$verb'. Try `loopky --help`.")
    }
}

/** Resolve the session once, before the command runs, so a missing one fails the same way everywhere. */
private suspend inline fun authed(
    identity: IdentityRepository,
    environment: CliEnvironment,
    block: (Session) -> CommandResult,
): CommandResult = block(requireSession(identity, environment))

private fun Koin.identity() = get<IdentityRepository>()
private fun Koin.decks() = get<DeckRepository>()
private fun Koin.cards() = get<CardRepository>()

private fun emit(args: Args, env: CliEnvironment, command: String, result: CommandResult) {
    if (args.has("json")) {
        println(successEnvelope(command, env.name, env.indexer, result.data))
    } else if (result.text.isNotEmpty()) {
        println(result.text)
    }
}

private fun emitEvent(args: Args, line: String) {
    if (args.has("json")) println(line)
}

private fun fail(args: Args, env: CliEnvironment, command: String, code: ExitCode, message: String) {
    if (args.has("json")) {
        // The failure envelope goes to **stdout**, like a success: a caller parsing `--json` has to
        // be able to read the error out of the same stream, and splitting the two would make the
        // machine channel say nothing at all about half the outcomes.
        println(failureEnvelope(command, env.name, env.indexer, code, message))
    } else {
        System.err.println("loopky: $message")
        if (code == ExitCode.Usage) System.err.println("\n" + USAGE)
    }
}

private const val TAG = "Loopky/Cli"

private const val VERSION = "loopky 0.1.0 (schema $SCHEMA_VERSION)"

private val USAGE = """
    loopky — a headless Loopky client for decks and cards.

    USAGE
      loopky <command> [options]

    IDENTITY
      login [--export] [--qr-out FILE] [--url-only]
                                Print a QR code for Pubky Ring and wait for approval.
                                --export also prints the session secret for LOOPKY_SESSION.
      logout                    Forget the stored session.
      whoami                    Pubky, homeserver, capabilities, environment, and whether the
                                session is still accepted.

    DECKS
      deck list
      deck show <deckId>
      deck create --title T [--description D] [--tag T]... [--cover-url URL]
                  [--cover-emoji E] [--from-file F]
                  [--listen] [--speak] [--type] [--reverse]
                  [--front-lang BCP47] [--back-lang BCP47]
      deck delete <deckId>
      deck sync <deckId>
      deck compact <deckId>     Fold away the holes card deletes leave in the chunk table.

    CARDS
      card list <deckId>
      card add <deckId> --front F --back B [--front-image URL] [--back-image URL]
      card add <deckId> --from-file cards.tsv|cards.jsonl
      card edit <deckId> <cardId> [--front F] [--back B] [--front-image URL] [--back-image URL]
      card edit <deckId> --from-file edits.jsonl
      card rm <deckId> <cardId>

    IMPORT
      import <file|-> --title T [--separator auto|tab|comma|semicolon|pipe|dash|colon|blank|markdown]
                      [--description D] [--tag T]... [--resume]

    DISCOVERY
      tag trending [--limit N]  Read the Nexus indexer. No session, no capability.

    GLOBAL
      --json                    Machine-readable output on stdout. Stable, versioned schema.
      --env staging|production  Which network to talk to. Defaults to production.
      --verbose                 Debug logging on stderr.
      --help, --version

    ENVIRONMENT
      LOOPKY_SESSION            A session secret. Read *before* the stored session, and the only
                                way in on a sandbox that has no stored one. Mint it with
                                `loopky login --export` on a machine with a human at it.
      LOOPKY_ENV                staging | production. --env wins. Defaults to production.
      LOOPKY_CONFIG_HOME        Where state lives. Defaults to ${'$'}XDG_CONFIG_HOME/loopky.

    CARD FILES
      TSV:   front <TAB> back <TAB> front_image_url <TAB> back_image_url   (last two optional)
      JSONL: {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
             "id" is for `card edit`; a field that is absent is left unchanged.

    EXIT CODES
      0 ok                      5 network
      1 internal                6 not found
      2 usage                   7 storage full (507 — terminal, never retried)
      3 not signed in           8 environment mismatch
      4 session expired         9 bad input

    NOTES
      Sessions are stored as a mode-0600 file, not in an OS keyring. libsecret is usually absent
      on the headless box this is built for, so a keyring default would fail exactly where the
      tool is meant to work. What is stored is a capability-scoped, expiring session — never a
      secret key, which never leaves Pubky Ring.

      This client asks Ring for /pub/loopky/:rw and nothing else. It therefore cannot post,
      follow, or edit a profile under any bug or any prompt injection, and there is no announce
      flag to pass.
""".trimIndent()
