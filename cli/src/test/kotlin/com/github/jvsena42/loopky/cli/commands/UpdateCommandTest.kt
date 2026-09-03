package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.InstallMethod
import com.github.jvsena42.loopky.cli.Installation
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `loopky update` (#209) — everything up to the point where it would fetch bytes.
 *
 * The download itself is not exercised here: it is a `HttpURLConnection` against a real release
 * asset, and the release workflow's smoke test is where an actual artifact gets to prove it. What
 * is pinned here is the set of decisions taken *before* anything is downloaded, because each one
 * of them is a way for a self-updater to do something wrong quietly.
 */
class UpdateCommandTest {

    private fun checker(body: String?, version: String = "0.8.0"): UpdateChecker {
        val fetcher = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): Result<HttpResponse> =
                body?.let { Result.success(HttpResponse(200, it)) } ?: Result.failure(IOException("offline"))
        }
        return UpdateChecker(
            configHome = Files.createTempDirectory("loopky-update-cmd"),
            fetcher = fetcher,
            currentVersion = version,
        )
    }

    private fun binary(path: String = "/home/agent/.local/bin/loopky") =
        Installation(InstallMethod.Binary, Path.of(path))

    private fun field(result: com.github.jvsena42.loopky.cli.CommandResult, name: String) =
        result.data.jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `--check reports without applying anything`() = runTest {
        val result = update(
            Args.parse(arrayOf("update", "--check")),
            checker("""{"version":"0.9.0","schema":2}"""),
            binary(),
        )
        assertEquals("0.9.0", field(result, "latest"))
        assertEquals("0.8.0", field(result, "current"))
        assertTrue(field(result, "update_available").toBoolean())
        assertTrue(field(result, "schema_changed").toBoolean())
        assertFalse(field(result, "applied").toBoolean())
        assertEquals("binary", field(result, "install"))
    }

    @Test
    fun `being current is an ordinary success, not a failure`() = runTest {
        val result = update(
            Args.parse(arrayOf("update")),
            checker("""{"version":"0.8.0","schema":1}"""),
            binary(),
        )
        assertFalse(field(result, "update_available").toBoolean())
        assertFalse(field(result, "applied").toBoolean())
        assertTrue(result.text.contains("latest release"))
    }

    /**
     * A check that could not complete changed nothing, so it exits 0. No egress and an allowlist
     * proxy are ordinary in the environments this runs in, and neither is a reason for a command
     * that touched nothing to report a fault.
     */
    @Test
    fun `an unreachable release page is reported, not thrown`() = runTest {
        val result = update(Args.parse(arrayOf("update")), checker(null), binary())
        assertFalse(field(result, "applied").toBoolean())
        assertEquals("null", result.data.jsonObject.getValue("latest").toString())
    }

    /**
     * The refusal that matters: a managed install exits **11**, never 0. An agent that asked for
     * an update and got a zero would carry on believing it had one, which is the exact failure the
     * whole check exists to prevent.
     */
    @Test
    fun `a package-managed install refuses with the right command and a non-zero code`() = runTest {
        listOf(
            InstallMethod.Homebrew to "brew upgrade",
            InstallMethod.Debian to "dpkg -i",
            InstallMethod.Container to "docker pull",
            InstallMethod.Jar to "jar distribution",
        ).forEach { (method, advice) ->
            val error = assertFailsWith<CliError> {
                update(
                    Args.parse(arrayOf("update")),
                    checker("""{"version":"0.9.0","schema":1}"""),
                    Installation(method, Path.of("/usr/bin/loopky")),
                )
            }
            assertEquals(ExitCode.UpdateUnsupported, error.exitCode, "$method must not exit 0")
            assertTrue(error.message.orEmpty().contains(advice), "$method should be told to run $advice")
        }
    }

    @Test
    fun `a binary that cannot locate itself refuses rather than writing over a guess`() = runTest {
        val error = assertFailsWith<CliError> {
            update(
                Args.parse(arrayOf("update")),
                checker("""{"version":"0.9.0","schema":1}"""),
                Installation(InstallMethod.Unknown, null),
            )
        }
        assertEquals(ExitCode.UpdateUnsupported, error.exitCode)
    }

    /** `--check` answers on a managed install too — asking is never refused, only doing. */
    @Test
    fun `--check on a managed install answers instead of refusing`() = runTest {
        val result = update(
            Args.parse(arrayOf("update", "--check")),
            checker("""{"version":"0.9.0","schema":1}"""),
            Installation(InstallMethod.Homebrew, Path.of("/opt/homebrew/Cellar/loopky/0.8.0/bin/loopky")),
        )
        assertTrue(field(result, "update_available").toBoolean())
        assertFalse(field(result, "can_self_update").toBoolean())
        assertTrue(field(result, "advice").contains("brew upgrade"))
    }
}

/**
 * The destructive half of `loopky update`, exercised without a network: what actually happens to
 * the file on disk.
 */
class ReplaceInPlaceTest {

    @Test
    fun `the digest is the one sha256sum would print`() {
        // `echo -n abc | sha256sum`. Pinned against an external tool rather than against itself,
        // because this value is compared with a digest GitHub published — a hex encoding that
        // drops a leading zero would match nothing and be invisible in a self-consistent test.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `replacing a binary leaves the new contents, executable`() {
        val dir = Files.createTempDirectory("loopky-replace")
        val target = dir.resolve("loopky")
        Files.writeString(target, "the old binary")

        replaceInPlace(target, "the new binary".toByteArray())

        assertEquals("the new binary", Files.readString(target))
        assertTrue(Files.isExecutable(target))
        assertEquals(
            listOf(target),
            Files.list(dir).use { it.toList() },
            "no temp file left behind beside it",
        )
    }

    /**
     * A read-only directory is the container layer and the root-owned `/usr/bin` case, and it has
     * to arrive as the same honest refusal as a package-managed install — not as an internal
     * error, and never as a partially written executable.
     */
    @Test
    fun `an unwritable directory refuses without touching anything`() {
        val dir = Files.createTempDirectory("loopky-replace-ro")
        val target = dir.resolve("loopky")
        Files.writeString(target, "the old binary")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val error = assertFailsWith<CliError> { replaceInPlace(target, "new".toByteArray()) }
            assertEquals(ExitCode.UpdateUnsupported, error.exitCode)
            assertEquals("the old binary", Files.readString(target))
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
