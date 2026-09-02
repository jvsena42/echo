package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.ConfigHome
import com.github.jvsena42.loopky.di.initKoinJvm
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform
import java.nio.file.Path

/**
 * Which network this invocation talks to, and where its state lives.
 *
 * The apps read the network off the build type — debug is staging, release is production, pinned
 * and un-overridable (#42). A binary has neither a build variant nor a Settings screen, and the
 * sandbox it runs in has no `local.properties`, so it has to be told: `--env` or `LOOPKY_ENV`,
 * **defaulting to production**. An unrecognised value resolves to production for the same reason
 * `PubkyEnvironment.fromNameOrProduction` does — a confused invocation must not quietly point at a
 * network its user does not publish to.
 *
 * One value, never three flags. `PubkyEnvironment` exists precisely so the Homegate, the default
 * homeserver and the indexer cannot be configured apart, and there is no `--nexus-url` here on
 * purpose.
 */
class CliEnvironment(val pubky: PubkyEnvironment, val configHome: Path) {
    val name: String get() = pubky.name.lowercase()
    val indexer: String get() = pubky.nexusBaseUrl

    companion object {
        fun resolve(args: Args, env: (String) -> String? = System::getenv): CliEnvironment {
            val requested = args.option("env") ?: env("LOOPKY_ENV")
            return CliEnvironment(
                pubky = PubkyEnvironment.fromNameOrProduction(requested),
                configHome = ConfigHome.resolve(env),
            )
        }
    }
}

/** Starts Koin for this invocation and hands back the graph. */
fun startCli(environment: CliEnvironment): Koin {
    initKoinJvm(pubkyEnvironment = environment.pubky, configHome = environment.configHome)
    return KoinPlatform.getKoin()
}

fun stopCli() = stopKoin()

/**
 * The session this invocation runs as.
 *
 * `LOOPKY_SESSION` is read **before** the stored one, and that ordering is the point rather than a
 * convenience: a cloud agent's sandbox is recreated per task, so `$XDG_CONFIG_HOME/loopky` is gone
 * every run, there is no "log in once", and nobody is watching that terminal to scan a QR code.
 * The variable is the only way in on such a box, and it has to win over a file that might also
 * exist on a developer's machine.
 *
 * The secret alone is not a session — see `IdentityRepository.adoptSession`, which trades it for
 * the real thing and proves it is still live in the same round trip.
 */
suspend fun requireSession(
    identity: IdentityRepository,
    environment: CliEnvironment,
    env: (String) -> String? = System::getenv,
): Session {
    val injected = env("LOOPKY_SESSION")?.trim()?.takeIf { it.isNotEmpty() }
    val session = if (injected != null) {
        Log.d(TAG, "using the session from LOOPKY_SESSION")
        identity.adoptSession(injected).getOrElse { throw asCliError(it, injected = true) }
    } else {
        identity.loadPersistedSession()
            ?: throw CliError(
                ExitCode.NotSignedIn,
                "Not signed in. Run `loopky login`, or set LOOPKY_SESSION to a session secret.",
            )
    }
    checkEnvironmentAgrees(session, environment)
    return session
}

/**
 * Refuse to run when the session and the requested environment disagree.
 *
 * An error rather than a warning, and checked before any command does anything. A session minted
 * on staging still publishes fine to its own homeserver — pkarr resolves both networks — so the
 * homeserver half would work and only the indexer-backed reads would be wrong, *silently*: Nexus
 * answers a mismatched query successfully with an empty result. An agent that writes a deck tag,
 * reads it back and gets `[]` concludes the write failed and retries. That is the one failure mode
 * this design cannot absorb.
 *
 * The check is deliberately not "does the session's homeserver equal
 * [PubkyEnvironment.defaultHomeserver]" — that would refuse a legitimate self-hosted homeserver.
 * It only fires when the session sits on the *other* environment's known default, which is a fact,
 * not an inference.
 *
 * It therefore **fails open**: a self-hosted homeserver, or one reported in a form these constants
 * do not match, passes. That is the right way round — the alternative refuses accounts that are
 * perfectly valid — but it means this catches the common mistake rather than every mistake.
 */
internal fun checkEnvironmentAgrees(session: Session, environment: CliEnvironment) {
    val other = PubkyEnvironment.entries.firstOrNull {
        it != environment.pubky && it.defaultHomeserver == session.homeserver
    } ?: return
    throw CliError(
        ExitCode.EnvironmentMismatch,
        "This session is on ${other.name.lowercase()} (homeserver ${session.homeserver}) but " +
            "--env/LOOPKY_ENV says ${environment.name}. Reads from the ${environment.name} " +
            "indexer would come back empty rather than wrong, so this is refused. " +
            "Re-run with --env ${other.name.lowercase()}.",
    )
}

/**
 * A shared-layer failure as something the process can exit with.
 *
 * [injected] changes only the advice: `loopky login` is not the fix on a box whose session came
 * from an environment variable, and telling an agent to run it there sends it at a QR code nobody
 * is looking at.
 */
fun asCliError(error: Throwable, injected: Boolean = false): CliError {
    val code = ExitCode.of(error)
    val hint = when {
        code != ExitCode.SessionExpired -> ""
        injected -> " LOOPKY_SESSION is no longer valid; mint a new one with `loopky login --export`."
        else -> " Run `loopky login` again."
    }
    return CliError(code, (error.message ?: error::class.simpleName ?: "failed") + hint)
}

private const val TAG = "Loopky/Cli"
