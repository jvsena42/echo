package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.cardAdd
import com.github.jvsena42.loopky.cli.commands.cardEdit
import com.github.jvsena42.loopky.cli.commands.cardList
import com.github.jvsena42.loopky.cli.commands.cardRemove
import com.github.jvsena42.loopky.cli.commands.deckCompact
import com.github.jvsena42.loopky.cli.commands.deckCreate
import com.github.jvsena42.loopky.cli.commands.deckDelete
import com.github.jvsena42.loopky.cli.commands.deckEdit
import com.github.jvsena42.loopky.cli.commands.deckList
import com.github.jvsena42.loopky.cli.commands.deckShow
import com.github.jvsena42.loopky.cli.commands.deckSync
import com.github.jvsena42.loopky.cli.commands.import
import com.github.jvsena42.loopky.cli.commands.importDryRun
import com.github.jvsena42.loopky.cli.commands.login
import com.github.jvsena42.loopky.cli.commands.logout
import com.github.jvsena42.loopky.cli.commands.tagTrending
import com.github.jvsena42.loopky.cli.commands.update
import com.github.jvsena42.loopky.cli.commands.whoami
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.async
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
    val updates = Updates(UpdateChecker(environment.configHome), detectInstallation())

    // One `runBlocking` around the whole command rather than around `dispatch` alone, so the
    // update check can run *concurrently* with the work (#209). On the one invocation a day that
    // actually goes to the network, it overlaps a homeserver round trip instead of adding to it —
    // and both outcomes need the answer, since `update_available` travels on a failure envelope
    // as well as a success.
    return runBlocking {
        // `runSuspendCatching`, and it is structural rather than defensive. `async` here is a child
        // of `runBlocking`'s job, which is **not** a supervisor: an exception escaping this lambda
        // cancels the in-flight command at its next suspension point, and the resulting
        // `CancellationException` reaches the generic handler below — which calls `await()` inside
        // its own catch block, re-throwing the original failure past `runBlocking` entirely, to
        // exit 1 with **nothing on stdout**. That is the exact contract the `Throwable`-not-
        // `Exception` note below exists to protect, broken by the update check. `check()` is
        // written to return null on every path; this is what makes that impossible to undo.
        val update = async {
            if (UpdateChecker.enabled(args)) {
                runSuspendCatching { updates.checker.check() }.getOrNull()
            } else {
                null
            }
        }
        try {
            // `update` before the boundary, because it is the command you reach for when the
            // install is *broken*. Everything below starts Koin, which resolves `PubkyClient` and
            // therefore loads `libpubkycore` — so on a host where that load fails (an old glibc, a
            // truncated download, a half-written file from an interrupted install) the repair
            // command would fail identically to the thing it repairs, with an error about the FFI.
            // It needs no session, no homeserver and no native library; only `args` and `updates`.
            val result = if (args.verb == UPDATE_VERB) {
                update(args, updates.checker, updates.installation)
            } else {
                // Inside the boundary, not before it: starting Koin resolves `PubkyClient`, which
                // is where a host outside the shipped matrix fails at `Native.load` — so this is
                // the last point at which such a host can still be told what is wrong with it
                // rather than about a deck that does not exist. See `requireSupportedHost`.
                requireSupportedHost()
                val koin = startCli(environment)
                dispatch(args, koin.identity(), koin, environment)
            }
            emit(args, environment, args.verb, result, updates.notice(update.await()))
            ExitCode.Ok
        } catch (error: CliError) {
            fail(args, environment, args.verb, error, updates.notice(update.await()))
            error.exitCode
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // `Throwable`, not `Exception`. An x86-64 Mac or a Windows box fails at `Native.load`
            // with `UnsatisfiedLinkError` / `ExceptionInInitializerError` — both `Error`s — and
            // catching only `Exception` let them past `fail()` to a bare exit 1 with **nothing on
            // stdout**. That breaks the "results and failures both go there as --json" contract for
            // precisely the case the README and `Platform.jvm.kt` both name as expected.
            val code = ExitCode.of(error)
            Log.e(TAG, "command failed", error)
            val message = error.message ?: error::class.simpleName.orEmpty()
            fail(args, environment, args.verb, CliError(code, message), updates.notice(update.await()))
            code
        } finally {
            // Koin may never have started, in which case stopping it throws over the failure we are
            // already reporting.
            runCatching { stopCli() }
        }
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
    // Two sinks, because they are two different things and collapsing them silenced a warning in
    // the mode an agent runs.
    //
    // `progress` is a counter — thousands of lines on a large import, and noise in a scripted run,
    // so it is suppressed under `--json` where the result carries the same numbers.
    // `note` is something the caller needs to *know*, and goes to stderr always. The README's model
    // is that stdout and stderr are separate channels, not one that switches off: an agent
    // capturing stderr for diagnostics must not get an empty file because it asked for JSON.
    val progress: (String) -> Unit = { line -> if (!args.has("json")) System.err.println(line) }
    val note: (String) -> Unit = System.err::println
    return when (val verb = args.verb) {
        "login" -> login(args, identity, environment, { line -> emitEvent(args, line) }, System.err::println)
        "logout" -> logout(identity)
        "whoami" -> whoami(identity, koin.get<PubkyClient>(), environment)

        "deck list" -> authed(identity, environment) { deckList(koin.decks()) }
        "deck show" -> authed(identity, environment) { deckShow(args, koin.decks()) }
        "deck create" -> authed(identity, environment) { session ->
            deckCreate(args, koin.decks(), session, progress)
        }
        "deck edit" -> authed(identity, environment) { deckEdit(args, koin.decks()) }
        "deck delete" -> authed(identity, environment) { deckDelete(args, koin.decks()) }
        "deck sync" -> authed(identity, environment) { deckSync(args, koin.decks(), koin.cards()) }
        "deck compact" -> authed(identity, environment) { deckCompact(args, koin.decks()) }

        "card list" -> authed(identity, environment) { cardList(args, koin.decks(), koin.cards()) }
        "card add" -> authed(identity, environment) { cardAdd(args, koin.decks(), koin.cards()) }
        "card edit" -> authed(identity, environment) { cardEdit(args, koin.decks(), koin.cards()) }
        "card rm" -> authed(identity, environment) { cardRemove(args, koin.decks()) }

        // `--dry-run` deliberately sits outside `authed`: it reads a local file and writes
        // nothing, so requiring a live session would put a sign-in between an agent and the check
        // that stops it publishing 9,000 cards of database ids (#96) or spending its whole media
        // quota on one deck.
        "import" -> if (args.has("dry-run")) {
            importDryRun(args, koin.get<ImportRepository>())
        } else {
            authed(identity, environment) { session ->
                import(
                    args = args,
                    imports = koin.get<ImportRepository>(),
                    decks = koin.decks(),
                    cards = koin.cards(),
                    media = koin.get<MediaRepository>(),
                    session = session,
                    onProgress = progress,
                    onNote = note,
                )
            }
        }

        // No session: a Nexus read is plain HTTP against a public index.
        "tag trending" -> tagTrending(args, koin.get<TagRepository>(), environment)

        // `update` is deliberately absent: it is handled in `run` before Koin starts, since it is
        // the one command that has to work on an install too broken to load the FFI. It is still
        // one function taking plain values, so the MCP-binding shape below is unaffected.

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

private fun emit(
    args: Args,
    env: CliEnvironment,
    command: String,
    result: CommandResult,
    notice: UpdateNotice,
) {
    if (args.has("json")) {
        println(successEnvelope(command, env.name, env.indexer, result.data, notice.available))
    } else if (result.text.isNotEmpty()) {
        println(result.text)
    }
    noteUpdate(notice)
}

private fun emitEvent(args: Args, line: String) {
    if (args.has("json")) println(line)
}

private fun fail(
    args: Args,
    env: CliEnvironment,
    command: String,
    error: CliError,
    notice: UpdateNotice,
) {
    if (args.has("json")) {
        // The failure envelope goes to **stdout**, like a success: a caller parsing `--json` has to
        // be able to read the error out of the same stream, and splitting the two would make the
        // machine channel say nothing at all about half the outcomes.
        println(failureEnvelope(command, env.name, env.indexer, error, notice.available))
    } else {
        System.err.println("loopky: ${error.message}")
        if (error.exitCode == ExitCode.Usage) System.err.println("\n" + USAGE)
    }
    noteUpdate(notice)
}

/**
 * The update notice: **stderr, once, whatever `--json` says** (#209).
 *
 * Never stdout — that is the machine channel, and one extra line there makes `--json` undecodable,
 * which is the rule everything else in this client follows. And not suppressed under `--json`
 * either: stdout and stderr are two channels here rather than one that switches off, so an agent
 * capturing stderr for diagnostics still learns its parser may be out of date.
 */
private fun noteUpdate(notice: UpdateNotice) {
    val update = notice.available ?: return
    System.err.println("loopky: " + updateNotice(update, notice.installation))
}

private const val TAG = "Loopky/Cli"

/** Handled in [run] rather than in [dispatch]; see the note at its call site. */
private const val UPDATE_VERB = "update"

/**
 * Two numbers that move independently, which is why both are printed.
 *
 * [CLI_VERSION] is generated from `loopkyCliVersion` in `gradle.properties` — see
 * `:cli:generateCliVersion`, and the release workflow's refusal to accept a tag that disagrees
 * with it. [SCHEMA_VERSION] is the `--json` envelope's, and it is the one a caller branches on.
 */
private val VERSION = "loopky $CLI_VERSION (schema $SCHEMA_VERSION)"

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
      deck edit <deckId> [--title T] [--description D] [--cover-url URL] [--cover-emoji E]
                  [--tag T]... [--clear-tags] [--clear-cover]
                  [--listen|--no-listen] [--speak|--no-speak]
                  [--type|--no-type] [--reverse|--no-reverse]
                  [--front-lang BCP47] [--back-lang BCP47]
                                One manifest write; cards are never read or rewritten. A flag you
                                do not pass leaves that field alone, `--description=` clears it,
                                and --tag replaces the tag set rather than appending to it.
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
      import <deck.apkg> --title T [--front-field N|name] [--back-field N|name]
                      An Anki export. Same command, same parser spine; the fields are named, so
                      --front-field/--back-field pick which two become the card. Numbers are
                      1-based, matching the labels an unnamed field is shown under.
      import <file> --dry-run [--json]
                      Report what would be published — for an .apkg, its field names with a
                      sample of each, the note count, the dropped-note breakdown and what its
                      pictures would spend. Writes nothing and needs no session.

    DISCOVERY
      tag trending [--limit N]  Read the Nexus indexer. No session, no capability.

    UPDATE
      update                    Replace this binary with the newest release, after checking the
                                digest published beside it. Refuses, with the right command, on a
                                Homebrew or .deb install, in a container, and on the jar.
      update --check            Ask without doing.

    GLOBAL
      --json                    Machine-readable output on stdout. Stable, versioned schema.
      --dry-run                 Read and report; write nothing. `import` only.
      --env staging|production  Which network to talk to. Defaults to production.
      --no-update-check         Do not look for a newer release on this invocation.
      --verbose                 Debug logging on stderr.
      --help, --version

    ENVIRONMENT
      LOOPKY_SESSION            A session secret. Read *before* the stored session, and the only
                                way in on a sandbox that has no stored one. Mint it with
                                `loopky login --export` on a machine with a human at it.
      LOOPKY_ENV                staging | production. --env wins. Defaults to production.
      LOOPKY_CONFIG_HOME        Where state lives. Defaults to ${'$'}XDG_CONFIG_HOME/loopky.
      LOOPKY_NO_UPDATE_CHECK    Set to anything to never look for a newer release. The check is
                                cached for a day, runs alongside the command, and can never fail
                                it — but a pipeline that wants no surprises can switch it off.

    CARD FILES
      TSV:   front <TAB> back <TAB> front_image_url <TAB> back_image_url   (last two optional)
      JSONL: {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
             "id" is for `card edit`; a field that is absent is left unchanged.

    CARD IMAGES
      A card picture is a URL. Nothing is uploaded and no media quota is spent — but nothing is
      fetched either, so this client cannot tell you the picture loads. It can only tell you what
      is knowably wrong, and these two account for most of it:

        https:// only.  Android and iOS both refuse cleartext, so an http:// address is a card
                        whose picture cannot render on either. Refused, not stored.

        Wikimedia serves thumbnails at 120, 250, 330, 500, 960 and 1280 px and answers 400 for
        every other width, so .../thumb/…/800px-Name.jpg is a blank card on both apps. Drop the
        /thumb/ segment and the NNNpx- prefix to get the full-size original, which is always
        served. Warned about on stderr, never fatal — the list is theirs to change.

      Beyond that, prefer a host that serves images to anyone: some refuse an unfamiliar client
      outright, and the result is the same blank card with nothing reporting it.

    EXIT CODES
      0 ok                      6 not found
      1 internal                7 storage full (507 — terminal, never retried)
      2 usage                   8 environment mismatch
      3 not signed in           9 bad input
      4 session expired        10 no build for this host
      5 network                11 update found but not applied (a managed install)

    NOTES
      Sessions are stored as a mode-0600 file, not in an OS keyring. libsecret is usually absent
      on the headless box this is built for, so a keyring default would fail exactly where the
      tool is meant to work. What is stored is a capability-scoped, expiring session — never a
      secret key, which never leaves Pubky Ring.

      This client asks Ring for /pub/loopky/:rw and nothing else. It therefore cannot post,
      follow, or edit a profile under any bug or any prompt injection, and there is no announce
      flag to pass.

      A newer release is reported on stderr and in the --json envelope's `update_available`,
      never acted on by itself. The check is one cached-for-a-day HTTPS GET against the same
      release page the installer uses, so it adds no host to an allowlist, and a check that fails
      is silent rather than fatal.

      An .apkg's pictures are the one thing this tool uploads bytes for, and it uploads them at
      full resolution — it ships no image codec, where the apps shrink every picture to 1024px
      JPEG. That is spent against a 1 GB homeserver quota nothing can read back, so `--dry-run`
      reports the total first. An .apkg's own deck description and note tags are reported and
      never adopted; pass them back as --description / --tag if they are right.
""".trimIndent()
