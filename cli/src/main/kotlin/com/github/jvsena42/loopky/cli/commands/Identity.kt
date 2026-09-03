package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.TerminalQr
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.eventEnvelope
import com.github.jvsena42.loopky.cli.requireSession
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.SerialName
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
    @SerialName("session_secret") val sessionSecret: String? = null,
    /**
     * Where the session was written.
     *
     * Always set: `--export` *also* prints the secret, it does not print it instead of storing it.
     * A caller reading this to decide whether a credential reached the disk gets the truth.
     */
    @SerialName("stored_at") val storedAt: String? = null,
)

@Serializable
data class WhoamiResult(
    val pubky: String,
    val homeserver: String,
    val capabilities: List<String>,
    /** `env` for `LOOPKY_SESSION`, `file` for the stored one. */
    @SerialName("session_source") val sessionSource: String,
    @SerialName("config_home") val configHome: String,
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
    @SerialName("session_live") val sessionLive: Boolean,
    /** Null, always, and deliberately — see [CLI_CAPABILITIES]. */
    @SerialName("display_name") val displayName: String? = null,
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

    val qrFile = args.option("qr-out")?.let { path ->
        File(path).also {
            TerminalQr.writePng(handle.authUrl, it)
            stderr("QR code written to $path (owner-readable only; deleted when this command ends)")
        }
    }
    // A `finally` is not enough on its own. `login` blocks on the relay for as long as it takes
    // somebody to reach for their phone, so the ordinary way it ends is **^C** — which is a signal,
    // not an exception, and takes the JVM down without unwinding. Without a hook the live
    // credential simply stays on disk, which is the failure the 0600 mode is only half of.
    val sweep = qrFile?.let { file -> Thread { file.delete() } }
    sweep?.let { Runtime.getRuntime().addShutdownHook(it) }
    emitEvent(eventEnvelope("login", "auth_url", buildJsonObject { put("auth_url", handle.authUrl) }))

    // The prompt goes to stderr, all of it: stdout carries the result and nothing else, and a
    // half-megabyte of block characters in front of the JSON would make it undecodable. A caller
    // that wants the URL programmatically reads the `auth_url` event on stdout under `--json`.
    //
    // **The plaintext URL is printed only under `--url-only`**, where it is the deliverable. It
    // carries the client secret the auth token is encrypted to, so leaking it turns the relay's
    // encrypted blob back into a usable session — and stderr is precisely the stream an agent
    // harness captures into a transcript that may be logged, uploaded or pasted into an issue.
    // A user who scans the code gets no benefit from having it in their scrollback as well.
    if (args.has("url-only")) {
        stderr("This URL is a credential until you approve it in Ring — treat it like a password.")
        stderr(handle.authUrl)
    } else {
        stderr(TerminalQr.render(handle.authUrl))
        stderr("Scan this with Pubky Ring. `--url-only` prints the link instead of the code.")
    }
    stderr("")
    stderr("Requesting $CLI_CAPABILITIES only — this session cannot post, follow or edit a profile.")
    stderr("Waiting for approval…")

    val session = try {
        handle.complete().getOrElse { throw asCliError(it) }
    } finally {
        // Whether approval landed or not: the URL in that file is either spent or dead, and
        // leaving a live one on disk is the point. Best-effort — a file the user cannot delete is
        // not a reason to fail a sign-in that worked.
        qrFile?.let { file -> runCatching { file.delete() } }
        sweep?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
    }
    val export = args.has("export")
    if (export) {
        stderr("")
        stderr("--export prints a live session secret below. It is also stored under ${environment.configHome}.")
    }
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

@Serializable
data class LogoutResult(
    /** True when the homeserver confirmed the session is dead, rather than merely forgotten here. */
    val revoked: Boolean,
    /** False for a `LOOPKY_SESSION`, which was never written to this machine in the first place. */
    @SerialName("cleared_locally") val clearedLocally: Boolean,
)

/**
 * End the session — on the homeserver, and on this machine where there is one.
 *
 * The `LOOPKY_SESSION` case used to be refused outright, on the grounds that there is nothing
 * stored to clear. True, and beside the point: sign-out does **two** things, and only the local
 * half was missing. The result was that a secret minted with `login --export` and carried into a
 * sandbox could never be withdrawn from this tool — `logout` refused, unsetting the variable
 * changed nothing server-side, and the session stayed live until it expired on its own. For a
 * credential whose whole story is "hand this to an ephemeral agent box", revocation is the wrong
 * end to be missing.
 *
 * So an injected session is *revoked* rather than cleared, and the result says which happened. It
 * goes through `revokeSession` rather than `signOut` for a reason worth keeping: on a developer's
 * machine both credentials can exist at once, and `signOut` would revoke the injected one while
 * wiping the stored one.
 */
suspend fun logout(
    identity: IdentityRepository,
    env: (String) -> String? = System::getenv,
): CommandResult {
    val injected = env("LOOPKY_SESSION")?.trim()?.takeIf { it.isNotEmpty() }
    if (injected != null) {
        identity.revokeSession(injected).getOrElse { throw asCliError(it, injected = true) }
        return result(
            LogoutResult(revoked = true, clearedLocally = false),
            "Revoked the session from LOOPKY_SESSION. Nothing was stored on this machine, so " +
                "there is nothing here to clear — unset the variable too.",
        )
    }

    identity.loadPersistedSession()
    // force = true: the sign-out guard exists to stop a phone destroying the only copy of a
    // locally-minted key. This client never mints one — it holds a Ring-issued session and
    // nothing else — so there is no key here for the guard to protect.
    val outcome = identity.signOut(force = true).getOrElse { throw asCliError(it) }
    return result(
        LogoutResult(revoked = outcome.revokedRemotely, clearedLocally = true),
        if (outcome.revokedRemotely) {
            "Signed out."
        } else {
            // Said plainly rather than folded into a success: the token is still live, and a user
            // told "signed out" would reasonably believe otherwise.
            "Cleared this machine's session, but the homeserver did not confirm it was revoked — " +
                "the session may still be usable until it expires. Try again when you are online."
        },
    )
}
