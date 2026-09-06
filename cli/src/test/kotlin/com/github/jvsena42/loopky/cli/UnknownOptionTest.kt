package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.requireImageCheckOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A flag a command does not take is refused, **by name**.
 *
 * The parser accepts any `--long` it sees, so a flag carried over from a sibling command was
 * dropped on the floor and the command failed for a second, unrelated reason — followed by sixty
 * lines of manual. Nothing in that output said which flag was wrong (#257, item 5).
 */
class UnknownOptionTest {

    private fun parse(vararg argv: String) = Args.parse(arrayOf(*argv))

    /** The exact command from the issue. */
    @Test
    fun `a flag from a sibling command is named, and so is where it belongs`() {
        val error = assertFailsWith<CliError> {
            parse("import", "--dry-run", "--check-images", "--from-file", "deck.tsv").requireKnownOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        val message = error.message.orEmpty()
        assertContains(message, "--from-file")
        assertContains(message, "import")
        assertContains(message, "deck create")
        // The substitution, spelled out: `import` takes its file as an operand.
        assertContains(message, "loopky import <file>")
    }

    /** And the message ends by saying what the command *does* take, which is what to act on. */
    @Test
    fun `the message lists the options the command has`() {
        val error = assertFailsWith<CliError> { parse("deck", "list", "--limit", "3").requireKnownOptions() }

        assertContains(error.message.orEmpty(), "--json")
        assertContains(error.message.orEmpty(), "card list")
    }

    @Test
    fun `every flag a command reads is in the table`() {
        listOf(
            arrayOf("import", "f.tsv", "--title", "t", "--cover-emoji", "x", "--cover-url", "https://a.test/b.png"),
            arrayOf("import", "f.tsv", "--title", "t", "--resume", "--no-listen", "--no-reverse"),
            arrayOf("deck", "create", "--title", "t", "--dry-run", "--check-images-concurrency", "4"),
            arrayOf("card", "add", "d1", "--from-file", "f.tsv", "--dry-run"),
            arrayOf("card", "edit", "d1", "--from-file", "f.jsonl", "--check-images"),
            arrayOf("deck", "edit", "d1", "--clear-tags", "--clear-cover", "--no-speak"),
            arrayOf("card", "list", "d1", "--limit", "10", "--cursor", "0:1", "--has-image"),
            arrayOf("login", "--export", "--url-only", "--qr-out", "q.png", "--timeout", "30"),
            arrayOf("batch", "f.jsonl", "--stop-on-error"),
            arrayOf("update", "--check"),
        ).forEach { argv -> Args.parse(argv).requireKnownOptions() }
    }

    /** The globals are global: refusing one on a command would be the same bug pointing the other way. */
    @Test
    fun `the global options are accepted everywhere`() {
        parse("deck", "list", "--json", "--env", "staging", "--verbose", "--no-update-check").requireKnownOptions()
    }

    /**
     * `--yes` and `--force` are taken and ignored on purpose (see [UNOFFERED_SWITCHES]); refusing
     * them here would break a caller in the habit of passing one, which is what they exist for.
     */
    @Test
    fun `the switches this client accepts and ignores stay accepted`() {
        parse("deck", "delete", "d1", "--yes", "--force").requireKnownOptions()
    }

    /**
     * `--check-images-concurrency` is read only from behind `--check-images`, so on its own it was
     * accepted and silently ignored — and its value went unchecked with it (#261 review,
     * finding 3). Silently doing something other than what was asked is the failure the flag
     * exists to stop.
     */
    @Test
    fun `the image-check dial is refused where it can do nothing`() {
        val error = assertFailsWith<CliError> {
            parse("card", "add", "d1", "--from-file", "f.tsv", "--check-images-concurrency", "9")
                .requireImageCheckOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "--check-images")
    }

    @Test
    fun `an out-of-range dial is refused even before the probe would run`() {
        val error = assertFailsWith<CliError> {
            parse("import", "f.tsv", "--title", "t", "--check-images", "--check-images-concurrency", "99")
                .requireImageCheckOptions()
        }

        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `the pair together, and neither, are both fine`() {
        parse("import", "f.tsv", "--title", "t", "--check-images", "--check-images-concurrency", "4")
            .requireImageCheckOptions()
        parse("import", "f.tsv", "--title", "t", "--check-images").requireImageCheckOptions()
        parse("import", "f.tsv", "--title", "t").requireImageCheckOptions()
    }

    /** An unknown *verb* has a better message of its own, so this says nothing about it. */
    @Test
    fun `a command that is not in the table is left to the dispatcher`() {
        parse("teleport", "--nowhere", "x").requireKnownOptions()
    }
}
