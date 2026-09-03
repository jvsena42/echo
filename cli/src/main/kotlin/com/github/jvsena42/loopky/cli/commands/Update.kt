package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.Installation
import com.github.jvsena42.loopky.cli.SupportedHost
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.cli.hostSupport
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.unsupportedHostMessage
import com.github.jvsena42.loopky.cli.updateAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * What `loopky update` reports, in `--json`.
 *
 * One shape for `--check` and for a real run, because a caller should not have to branch on which
 * flag it passed to find out what happened: [applied] is the answer either way, and it is false
 * for `--check`, for "already current", and for an installation this command may not touch.
 */
@Serializable
data class UpdateResult(
    val current: String,
    val latest: String?,
    @SerialName("update_available") val updateAvailable: Boolean,
    val schema: Int,
    @SerialName("latest_schema") val latestSchema: Int?,
    @SerialName("schema_changed") val schemaChanged: Boolean,
    /** How this copy was installed: `binary`, `homebrew`, `deb`, `container`, `jar`, `unknown`. */
    val install: String,
    val path: String?,
    @SerialName("can_self_update") val canSelfUpdate: Boolean,
    val applied: Boolean,
    /** True only when a downloaded file's SHA-256 matched the digest published beside it. */
    val verified: Boolean,
    /** What to run next, in words. Empty when there is nothing to do. */
    val advice: String,
)

/**
 * Replace this binary with the newest release (#209).
 *
 * The shape of the command is set by one fact: **a self-updater is the only command that fetches
 * an executable and then runs it as the user.** So it verifies the download against the digest
 * published beside it and refuses on a mismatch rather than warning — where `cli/install.sh`
 * degrades to "digest NOT checked" on a host with no `sha256sum`, because there its alternative is
 * a plain `curl` with no check at all, here the alternative is simply not updating.
 *
 * It also refuses, with the right command, wherever the file is not ours to replace — a Homebrew
 * Cellar file, a `dpkg`-owned `/usr/bin/loopky`, a container layer, the jar distribution's
 * directory. See [com.github.jvsena42.loopky.cli.InstallMethod]. That refusal exits
 * [ExitCode.UpdateUnsupported] rather than 0: an agent that asked for an update and got a zero
 * would carry on believing it had one.
 */
suspend fun update(
    args: Args,
    checker: UpdateChecker,
    installation: Installation,
): CommandResult {
    // Uncached: `--check` is a direct question and answering it out of a day-old file is the one
    // behaviour that would make this command less trustworthy than the ambient notice it exists
    // to act on.
    val manifest = checker.latest(force = true)
    val available = checker.available(manifest)
    val checkOnly = args.has("check")

    val advice = when {
        available == null -> ""
        else -> updateAdvice(installation, available.version)
    }

    fun report(applied: Boolean, verified: Boolean, text: String) = result(
        UpdateResult(
            current = checker.currentVersion,
            latest = manifest?.version,
            updateAvailable = available != null,
            schema = checker.currentSchema,
            latestSchema = manifest?.schema,
            schemaChanged = available?.schemaChanged ?: false,
            install = installation.method.json,
            path = installation.path?.toString(),
            canSelfUpdate = installation.canSelfUpdate,
            applied = applied,
            verified = verified,
            advice = advice,
        ),
        text,
    )

    if (manifest == null) {
        // Not an error. No egress, an allowlist proxy and a release page with no manifest yet all
        // land here, and none of them is a reason to exit non-zero on a command that changed
        // nothing.
        return report(applied = false, verified = false, text = "Could not reach the release page. Nothing changed.")
    }
    if (available == null) {
        return report(
            applied = false,
            verified = false,
            text = "loopky ${checker.currentVersion} is the latest release.",
        )
    }
    if (checkOnly) {
        return report(applied = false, verified = false, text = "loopky ${available.version} is available. $advice")
    }
    if (!installation.canSelfUpdate) {
        throw CliError(ExitCode.UpdateUnsupported, "loopky ${available.version} is available, but $advice")
    }

    val host = hostSupport() ?: throw CliError(ExitCode.UnsupportedHost, unsupportedHostMessage())
    val target = requireNotNull(installation.path) { "canSelfUpdate implies a path" }
    val binary = fetchVerifiedBinary(checker, available.version, host)
    replaceInPlace(target, binary)
    return report(
        applied = true,
        verified = true,
        text = "Updated loopky ${checker.currentVersion} -> ${available.version} at $target.",
    )
}

/** Download the release asset for [host] and refuse it unless its digest is the published one. */
private suspend fun fetchVerifiedBinary(
    checker: UpdateChecker,
    version: String,
    host: SupportedHost,
): ByteArray = withContext(Dispatchers.IO) {
    val url = checker.assetUrl(version, host.asset)
    val binary = download(url)
    // A missing digest is a refusal, not a warning. The release workflow publishes one beside
    // every binary, so its absence means the release is malformed or something is answering for
    // github.com that should not be — and this is the one command where "carry on anyway" means
    // executing whatever came back.
    val published = runCatching { download("$url.sha256").decodeToString() }.getOrElse {
        throw CliError(
            ExitCode.Internal,
            "no published checksum at $url.sha256, so the download was not verified and has been " +
                "discarded. Nothing was replaced.",
        )
    }
    val expected = published.trim().substringBefore(' ').lowercase()
    val actual = sha256(binary)
    if (expected != actual) {
        throw CliError(
            ExitCode.Internal,
            "checksum mismatch for ${host.asset}: expected $expected, got $actual. The download has " +
                "been discarded and nothing was replaced.",
        )
    }
    binary
}

/**
 * A plain GET for **bytes**.
 *
 * Not [com.github.jvsena42.loopky.data.nexus.HttpFetcher], which decodes a body as UTF-8 — that is
 * right for the JSON everything else here fetches and would silently corrupt a 60 MB executable.
 * Redirects are followed because `releases/download/…` is a 302 into GitHub's object store; both
 * hops are HTTPS, which is the only kind [HttpURLConnection] will follow.
 */
private fun download(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code !in SUCCESS) {
            throw CliError(ExitCode.Network, "HTTP $code fetching $url")
        }
        val bytes = connection.inputStream.use { it.readNBytes(MAX_DOWNLOAD_BYTES + 1) }
        if (bytes.size > MAX_DOWNLOAD_BYTES) {
            throw CliError(ExitCode.Internal, "$url is larger than ${MAX_DOWNLOAD_BYTES / MB} MB — refusing it.")
        }
        return bytes
    } finally {
        connection.disconnect()
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Write [bytes] over [target], whole or not at all.
 *
 * A temp file in the same directory and then an atomic rename, so a process killed mid-write
 * cannot leave a half-written executable under a name the user's next command will try to run —
 * and so a `loopky` already running from that path keeps its open file. Same directory rather than
 * the system temp because `ATOMIC_MOVE` cannot cross a filesystem, and `~/.local/bin` and `/tmp`
 * frequently are two.
 */
internal fun replaceInPlace(target: Path, bytes: ByteArray) {
    val temp = runCatching { Files.createTempFile(target.parent, target.fileName.toString(), ".new") }
        .getOrElse { throw notWritable(target, it) }
    runCatching {
        Files.write(temp, bytes)
        // Best-effort, like every other 0600/0755 in this codebase: a filesystem with no POSIX
        // mode cannot express it, and failing here would leave the user on the old binary for a
        // guarantee that host was never going to give.
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString(MODE)) }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
        Files.deleteIfExists(temp)
        throw notWritable(target, it)
    }
}

private fun notWritable(target: Path, cause: Throwable) = CliError(
    ExitCode.UpdateUnsupported,
    "could not replace $target (${cause.message}). The file or its directory is not writable by " +
        "this user — a read-only layer, or an install that needs the owner. Nothing was changed.",
)

private const val MODE = "rwxr-xr-x"
private const val MB = 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 256 * MB
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private val SUCCESS = 200..299
