package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.TerminalQr
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.eventEnvelope
import com.github.jvsena42.loopky.cli.ok
import com.github.jvsena42.loopky.cli.requireSession
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The capabilities a headless client asks Pubky Ring for, and the whole of them.
 *
 * **Not `DEFAULT_CAPABILITIES`**, which is what the two apps request
 * (`/pub/loopky/:rw,/pub/pubky.app/:rw`). An agent session therefore cannot write a post, a follow
 * or a profile edit under any bug or any prompt injection, because it was never handed the
 * capability at all — the guarantee is structural rather than a flag somebody remembered to set.
 *
 * It costs less than it looks. Deck tagging routes on the *subject*: a deck manifest's tag record
 * goes to `/pub/loopky/tags/`, and only a profile or post subject needs the pubky.app namespace
 * (Architecture.md §7.7). So `deck create --tag …` and the language tags a declared pair puts on a
 * deck both work unchanged. What is given up is exactly the pubky.app-native writes a headless
 * deck tool has no business making: announcing a deck (#39), tagging a profile, and editing
 * `profile.json`.
 *
 * One visible consequence, and it is why `whoami` reports no display name: that lives in
 * `/pub/pubky.app/profile.json`, and this client is a loopky-namespace client in both directions.
 */
const val CLI_CAPABILITIES = "/pub/loopky/:rw"

@Serializable
data class LoginResult(
    val pubky: String,
    val homeserver: String,
    val capabilities: List<String>,
    /**
     * The session secret, printed **only** for `--export`.
     *
     * It is what `LOOPKY_SESSION` takes, and the reason `login` is a local-machine command: a
     * sandbox recreated per task has no stored session and nobody at its terminal to scan a code,
     * so the secret has to be minted somewhere with a human and carried in.
     */
    val sessionSecret: String? = null,
    /** Where the session was written, or null when `--export` printed it instead of storing it. */
    val storedAt: String? = null,
)

@Serializable
data class WhoamiResult(
    val pubky: String,
    val homeserver: String,
    val capabilities: List<String>,
    /** `env` for `LOOPKY_SESSION`, `file` for the stored one. */
    val sessionSource: String,
    val configHome: String,
    val environment: String,
    val indexer: String,
    /**
     * Whether the homeserver still honours this session, right now, asked rather than assumed.
     *
     * There is no `expires_at` to report: the FFI's session payload carries a pubky, a secret, a
     * homeserver and capabilities, and no expiry — so a client cannot plan around the wall, only
     * discover it. This is the honest substitute, and it is what an agent should check before
     * starting an hour-long import rather than 40 cards in (#165).
     */
    val sessionLive: Boolean,
    /** Null, always, and deliberately — see [CLI_CAPABILITIES]. */
    val displayName: String? = null,
)

/**
 * Sign in by printing a QR code for Pubky Ring, then blocking on the relay until it is approved.
 *
 * The auth URL carries **no Ring return-callbacks**. Mobile appends `x-success`/`x-cancel` so Ring
 * can deeplink back into the app; there is no app here to return to, and a dangling `x-success`
 * pointing at `loopky://` would bounce the user into Loopky on their phone after a desktop login.
 *
 * The FFI's auth flow is a single global slot that `awaitAuthApproval` *takes*, so a failed poll
 * consumes it and there is no in-place retry: recovering means running `loopky login` again, which
 * mints a new secret and a new code to scan. Said plainly in the failure rather than papered over
 * with a retry loop that would silently invalidate the code already on screen.
 */
suspend fun login(
    args: Args,
    identity: IdentityRepository,
    environment: CliEnvironment,
    emitEvent: (String) -> Unit,
    stderr: (String) -> Unit,
): CommandResult {
    val handle = identity.beginSignIn(capabilities = CLI_CAPABILITIES, returnToApp = false)
        .getOrElse { throw asCliError(it) }

    args.option("qr-out")?.let { path ->
        TerminalQr.writePng(handle.authUrl, File(path))
        stderr("QR code written to $path")
    }
    emitEvent(eventEnvelope("login", "auth_url", buildJsonObject { put("auth_url", handle.authUrl) }))

    // The prompt goes to stderr, all of it: stdout carries the result and nothing else, and a
    // half-megabyte of block characters in front of the JSON would make it undecodable. A caller
    // that wants the URL programmatically reads the `auth_url` event on stdout under `--json`.
    //
    // `--url-only` drops the picture, for a box whose terminal cannot draw one or whose output is
    // going into a log somebody will read later.
    if (!args.has("url-only")) {
        stderr(TerminalQr.render(handle.authUrl))
    }
    stderr("Scan this with Pubky Ring, or open it there:")
    stderr(handle.authUrl)
    stderr("")
    stderr("Requesting $CLI_CAPABILITIES only — this session cannot post, follow or edit a profile.")
    stderr("Waiting for approval…")

    val session = handle.complete().getOrElse { throw asCliError(it) }
    val export = args.has("export")
    return result(
        LoginResult(
            pubky = session.identity.pubky,
            homeserver = session.homeserver,
            capabilities = session.capabilities.map { it.value },
            sessionSecret = session.sessionSecret.takeIf { export },
            storedAt = environment.configHome.toString(),
        ),
        buildString {
            appendLine("Signed in as ${session.identity.pubky}")
            appendLine("Homeserver: ${session.homeserver}")
            appendLine("Capabilities: ${session.capabilities.joinToString(", ") { it.value }}")
            appendLine("Session stored under ${environment.configHome}")
            if (export) {
                appendLine()
                appendLine("LOOPKY_SESSION=${session.sessionSecret}")
                appendLine("Treat that like a password: it authorises writes to /pub/loopky until it expires.")
            }
        }.trimEnd(),
    )
}

suspend fun whoami(
    identity: IdentityRepository,
    pubkyClient: PubkyClient,
    environment: CliEnvironment,
    env: (String) -> String? = System::getenv,
): CommandResult {
    val session: Session = requireSession(identity, environment, env)
    val live = pubkyClient.revalidateSession(session.sessionSecret).isSuccess
    val source = if (env("LOOPKY_SESSION").isNullOrBlank()) "file" else "env"
    return result(
        WhoamiResult(
            pubky = session.identity.pubky,
            homeserver = session.homeserver,
            capabilities = session.capabilities.map { it.value },
            sessionSource = source,
            configHome = environment.configHome.toString(),
            environment = environment.name,
            indexer = environment.indexer,
            sessionLive = live,
        ),
        buildString {
            appendLine("Pubky:        ${session.identity.pubky}")
            appendLine("Homeserver:   ${session.homeserver}")
            appendLine("Capabilities: ${session.capabilities.joinToString(", ") { it.value }}")
            appendLine("Environment:  ${environment.name}")
            appendLine("Indexer:      ${environment.indexer}")
            appendLine("Session from: $source")
            appendLine("Config home:  ${environment.configHome}")
            append("Session:      ${if (live) "live" else "NOT accepted by the homeserver — sign in again"}")
        },
    )
}

/**
 * Forget the stored session.
 *
 * Refuses when the session came from `LOOPKY_SESSION`: there is nothing on disk to clear, and
 * silently succeeding would tell a caller its credential had been revoked when it has not.
 */
suspend fun logout(
    identity: IdentityRepository,
    env: (String) -> String? = System::getenv,
): CommandResult {
    if (!env("LOOPKY_SESSION").isNullOrBlank()) {
        throw CliError(
            ExitCode.Usage,
            "This session comes from LOOPKY_SESSION, so there is nothing stored to clear. " +
                "Unset the variable instead.",
        )
    }
    identity.loadPersistedSession()
    // force = true: the sign-out guard exists to stop a phone destroying the only copy of a
    // locally-minted key. This client never mints one — it holds a Ring-issued session and
    // nothing else — so there is no key here for the guard to protect.
    identity.signOut(force = true).getOrElse { throw asCliError(it) }
    return ok("Signed out.")
}
