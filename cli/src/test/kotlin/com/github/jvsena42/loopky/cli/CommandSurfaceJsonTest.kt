package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.CommandSurfaceResult
import com.github.jvsena42.loopky.cli.commands.commandSurface
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `loopky commands --json` is what an agent holding only the binary has instead of the repository
 * (#240, finding 4). These hold it against the rest of the CLI the way `CompletionTest` holds the
 * completion scripts — the surface is generated from one table, so the failure mode is not a wrong
 * answer but a stale one.
 */
class CommandSurfaceJsonTest {

    private val surface: CommandSurfaceResult =
        Json.decodeFromJsonElement(CommandSurfaceResult.serializer(), commandSurface().data)

    @Test
    fun `every command in the table is described`() {
        assertEquals(cliCommands().map { it.path }, surface.commands.map { it.path })
    }

    /** The envelope's `command` field and this `path` have to be the same string, or it is a map to nothing. */
    @Test
    fun `a path is exactly what the parser calls the verb`() {
        surface.commands.forEach {
            assertEquals(it.path, Args.parse(it.path.split(" ").toTypedArray()).verb)
        }
    }

    @Test
    fun `arity is reported, optional operands included`() {
        val edit = assertNotNull(surface.commands.firstOrNull { it.path == "card edit" })
        assertEquals(listOf("deckId", "cardId"), edit.operands.map { it.name })
        assertEquals(listOf(true, false), edit.operands.map { it.required })

        val rm = assertNotNull(surface.commands.firstOrNull { it.path == "card rm" })
        assertEquals(listOf(true, true), rm.operands.map { it.required })

        assertEquals(emptyList(), assertNotNull(surface.commands.firstOrNull { it.path == "deck list" }).operands)
    }

    /** A switch and a value-taking option parse differently, so the surface has to say which. */
    @Test
    fun `an option the parser treats as a switch is reported as one`() {
        (surface.commands.flatMap { it.options } + surface.globals).forEach {
            assertEquals(
                it.name in Args.SWITCHES,
                it.value == "switch",
                "--${it.name} is '${it.value}' here but ${if (it.name in Args.SWITCHES) "is" else "is not"} a parser switch",
            )
        }
    }

    @Test
    fun `a closed set carries its choices`() {
        val env = assertNotNull(surface.globals.firstOrNull { it.name == "env" })
        assertEquals(listOf("staging", "production"), env.choices)
        val completion = assertNotNull(surface.commands.firstOrNull { it.path == "completion" })
        assertEquals(COMPLETION_SHELLS, completion.operands.single().choices)
        assertEquals("shell", completion.operands.single().name)
    }

    @Test
    fun `the whole exit code table travels`() {
        assertEquals(ExitCode.entries.map { it.code }, surface.exitCodes.map { it.code })
        assertEquals(ExitCode.entries.map { it.json }, surface.exitCodes.map { it.name })
        assertTrue(surface.exitCodes.all { it.summary.isNotBlank() })
    }

    /**
     * The absences are the useful half. An agent reading that `tag trending` can never answer
     * `session_expired` knows not to sign in before calling it, and that `completion` can never
     * answer `network` knows it works on a box with no egress.
     */
    @Test
    fun `a command's exit codes follow from what it does`() {
        val trending = assertNotNull(surface.commands.firstOrNull { it.path == "tag trending" })
        assertTrue(ExitCode.SessionExpired.code !in trending.exitCodes)
        assertTrue(ExitCode.NotSignedIn.code !in trending.exitCodes)
        assertTrue(ExitCode.Network.code in trending.exitCodes)

        val completion = assertNotNull(surface.commands.firstOrNull { it.path == "completion" })
        assertTrue(ExitCode.Network.code !in completion.exitCodes)
        assertTrue(ExitCode.UnsupportedHost.code !in completion.exitCodes)

        val login = assertNotNull(surface.commands.firstOrNull { it.path == "login" })
        assertTrue(ExitCode.Timeout.code in login.exitCodes, "login declares --timeout")
        assertTrue(ExitCode.NotSignedIn.code !in login.exitCodes, "login is what produces a session")

        val update = assertNotNull(surface.commands.firstOrNull { it.path == "update" })
        assertTrue(ExitCode.UpdateUnsupported.code in update.exitCodes)

        val cardAdd = assertNotNull(surface.commands.firstOrNull { it.path == "card add" })
        assertTrue(ExitCode.StorageFull.code in cardAdd.exitCodes)
        assertTrue(ExitCode.NotFound.code in cardAdd.exitCodes)
        assertTrue(ExitCode.BadInput.code in cardAdd.exitCodes)

        assertTrue(surface.commands.all { ExitCode.Ok.code in it.exitCodes && ExitCode.Usage.code in it.exitCodes })
    }

    /** Every command that reads or writes a record needs one; the four that do not are named. */
    @Test
    fun `needs_session is reported`() {
        assertEquals(
            listOf("login", "tag trending", "update", "completion", "commands"),
            surface.commands.filterNot { it.needsSession }.map { it.path },
        )
    }

    @Test
    fun `it says which version and which envelope schema answered`() {
        assertEquals(SCHEMA_VERSION, surface.envelopeSchema)
        assertTrue(surface.cliVersion.isNotBlank())
    }
}
