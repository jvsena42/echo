package com.github.jvsena42.loopky.data.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What one keychain lookup can say. [Missing] is an answer; [Failed] is the absence of one. */
internal sealed interface KeychainRead {
    data class Found(val value: String) : KeychainRead
    data object Missing : KeychainRead
    data class Failed(val message: String) : KeychainRead
}

/** One generic-password item, addressed by service and account. */
internal interface Keychain {
    /** Where this item lives, in a form a client can print — see [SecureSessionStore.location]. */
    val location: String
    fun read(): KeychainRead

    /**
     * Whether the item is there, **without moving the secret through a pipe**.
     *
     * `find-generic-password` answers the same exit codes with and without `-w`; only `-w` prints
     * the password. Two callers never wanted it — [SecureSessionStore.location], which asks
     * whether the Keychain is answering at all, and `clear()`'s gate, which asks whether there is
     * anything to fail about — and a store that exists partly so the credential is not sitting
     * where it need not be should not extract it to answer either. Null when the Keychain will
     * not say, which is a third answer rather than a false.
     */
    fun exists(): Boolean?

    fun write(value: String): Result<Unit>

    /**
     * Remove the item, reporting whether it is *gone* rather than whether a call was made.
     *
     * A `Result` because sign-out promises the credential is destroyed, and the local half of
     * that promise used to be the one nobody could observe: a keychain that refused a delete was
     * logged and swallowed, so `loopky logout` printed "Signed out." over a live bearer token.
     * An item that was already absent is a success — `clear()` promises the item is gone, not
     * that this call is what removed it.
     */
    fun delete(): Result<Unit>
}

/**
 * The macOS Keychain through `security(1)`, one subprocess per operation (#213).
 *
 * Chosen over JNA into Security.framework for two reasons, neither of them line count. A
 * `SecItemAdd` from the binary itself ties the item's ACL to *this executable*, and `loopky
 * update` replaces the executable — so the upgrade a user is told to run would start prompting
 * for a Keychain password. And `native-image` has to be able to fold whatever this uses into one
 * file; `ProcessBuilder` needs no metadata, where a new JNA surface needs registration in three
 * files and is the exact shape that has twice made the build emit a second one.
 *
 * **The password never appears in `argv`.** macOS lets any local user read another process's
 * command line, so `security add-generic-password -w <secret>` publishes the thing it is storing
 * for as long as it runs. `security -i` reads its command from *stdin* instead, which is a pipe
 * only this process holds — the whole reason the write path is shaped differently from the other
 * two. What that costs is quoting: `-i` splits its line the way a shell does, so the payload is
 * Base64 before it goes in, and [requireArgvSafe] fails the write rather than letting a value
 * that would need escaping reach it.
 *
 * `-T /usr/bin/security` is the item's trusted-application list, and it is honest about its
 * ceiling: it stops the *confirmation dialog* on every read, and anything that can run `security`
 * as this user can therefore read the item. What the Keychain buys over the 0600 file is
 * encryption at rest, a credential that goes away when the keychain locks, and a session that is
 * not sitting in a directory people tar up and attach to bug reports.
 */
internal class SecurityCliKeychain(
    private val service: String = SESSION_SERVICE_NAME,
    private val account: String = SESSION_STORAGE_KEY,
    private val security: Path = SECURITY_BIN,
    /**
     * Injectable so a test can prove the bound *fires*. It was unreachable for a while and
     * nothing said so, because the only thing that had ever exercised this path was a `security`
     * that answers immediately.
     */
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : Keychain {

    override val location: String = "the macOS Keychain (service $service)"

    override fun read(): KeychainRead {
        val outcome = run(listOf("find-generic-password", "-s", service, "-a", account, "-w"))
        return when {
            outcome.exitCode == 0 -> KeychainRead.Found(outcome.stdout.trim())
            outcome.exitCode == ITEM_NOT_FOUND -> KeychainRead.Missing
            else -> KeychainRead.Failed(outcome.describe())
        }
    }

    override fun exists(): Boolean? {
        val outcome = run(listOf("find-generic-password", "-s", service, "-a", account))
        return when (outcome.exitCode) {
            0 -> true
            ITEM_NOT_FOUND -> false
            else -> null
        }
    }

    override fun write(value: String): Result<Unit> {
        // L4: a `Result` return whose guard threw would take the caller's `onFailure` with it —
        // `save()`'s fallback to the file most of all — so the guard answers on the same path as
        // every other write failure.
        if (!isArgvSafe(value)) {
            return Result.failure(
                IllegalArgumentException(
                    "a keychain value has to survive `security -i` line splitting unquoted; " +
                        "this one would not",
                ),
            )
        }
        // `-U` so a second sign-in replaces the item rather than failing on a duplicate; `-l`/`-D`
        // so Keychain Access shows a person something they can recognise and delete by hand.
        val command = listOf(
            "add-generic-password", "-U",
            "-s", service,
            "-a", account,
            "-l", service,
            // Quoted because it has a space in it, and `-i` splits its line the way a shell does.
            // Every other token here is argv-safe by construction — [requireArgvSafe] for the
            // value, and constants for the rest.
            "-D", "\"Loopky session\"",
            "-T", security.toString(),
            "-w", value,
        ).joinToString(" ")
        val outcome = run(emptyList(), stdin = "$command\n")
        return if (outcome.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(outcome.describe()))
        }
    }

    override fun delete(): Result<Unit> {
        val outcome = run(listOf("delete-generic-password", "-s", service, "-a", account))
        return if (outcome.exitCode == 0 || outcome.exitCode == ITEM_NOT_FOUND) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(outcome.describe()))
        }
    }

    /**
     * Run one `security` subcommand under a wall-clock bound.
     *
     * **Both pipes are drained on their own threads, and that is what makes the timeout real.**
     * Reading them inline is the obvious shape and it silently disarms the bound: `readText()`
     * returns at EOF, EOF arrives when the child exits, so `waitFor` is only ever reached by a
     * process that has already finished and can never report a timeout. The hang it exists for is
     * not hypothetical — `find-generic-password` on an item whose ACL is not satisfied blocks on
     * an unlock prompt that never arrives over SSH or under `launchd`, and a wedged `securityd`
     * does the same. Unbounded, `loopky whoami` simply never returns, which is the failure an
     * agent can least recover from.
     *
     * Off-thread rather than `redirectOutput` to a file, because the thing on stdout *is* the
     * session: a temp file would put the credential on disk for the length of every read, which
     * is the posture this store exists to improve on.
     */
    private fun run(args: List<String>, stdin: String? = null): Outcome {
        val command = listOf(security.toString()) + (if (stdin == null) args else listOf("-i") + args)
        return runCatching {
            val process = ProcessBuilder(command).start()
            try {
                val out = process.inputStream.drainOffThread()
                val err = process.errorStream.drainOffThread()
                process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
                if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    // Null rather than "" when a drain somehow outlives the process it was
                    // reading: an empty stdout is a *valid* answer shape here, and letting a
                    // truncated read pass as one turns a lost byte into "there is no session".
                    val text = out.text()
                    if (text == null) {
                        Outcome(NOT_RUN, "", "security exited but its output never arrived")
                    } else {
                        Outcome(process.exitValue(), text, err.text().orEmpty())
                    }
                } else {
                    Outcome(TIMED_OUT, "", "no answer in ${timeoutSeconds}s — a locked keychain, or a confirmation prompt nobody can see")
                }
            } finally {
                // A no-op once it has exited, and the only thing that unblocks the drain threads
                // on the timeout path — or after a failed write to its stdin, which used to leave
                // the process running.
                process.destroyForcibly()
            }
        }.getOrElse { Outcome(NOT_RUN, "", it.message ?: it::class.simpleName.orEmpty()) }
    }

    private fun InputStream.drainOffThread(): FutureTask<String> {
        val task = FutureTask { runCatching { bufferedReader().use { it.readText() } }.getOrDefault("") }
        Thread(task, "loopky-keychain-drain").apply { isDaemon = true }.start()
        return task
    }

    /** The drained text, or null if it never arrived — the caller must not read that as empty. */
    private fun FutureTask<String>.text(): String? =
        runCatching { get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull()

    private data class Outcome(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "security exited $exitCode: ${stderr.trim().ifEmpty { "no message" }}"
    }

    private companion object {
        /** `errSecItemNotFound` as `security(1)` reports it. An answer, not a failure. */
        const val ITEM_NOT_FOUND = 44
        const val TIMED_OUT = -1
        const val NOT_RUN = -2
        const val TIMEOUT_SECONDS = 20L
        const val DRAIN_TIMEOUT_SECONDS = 2L
    }
}

internal val SECURITY_BIN: Path = Paths.get("/usr/bin/security")

/** True when this host has the tool the Keychain is reached through. */
internal fun keychainToolPresent(security: Path = SECURITY_BIN): Boolean = Files.isExecutable(security)

/**
 * Whether `security -i`'s shell-like line splitting carries this value through intact.
 *
 * The Base64 alphabet is inside this set, so it is always true for a session — which is the point:
 * it keeps "no quoting needed" a checked fact rather than a comment, if something later stores a
 * value that is not Base64.
 */
private fun isArgvSafe(value: String): Boolean =
    value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "+/=_-." }
