package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every failure observed in #240 carried a doubled prefix — `"Request failed: Request failed: …"`.
 * The FFI produces it; this only stops repeating it.
 */
class RepeatedPrefixTest {

    @Test
    fun `says a doubled wrap once`() {
        assertEquals(
            "Request failed: Invalid request/URI: path contains empty segment ('//')",
            CliError(
                ExitCode.Internal,
                "Request failed: Request failed: Invalid request/URI: path contains empty segment ('//')",
            ).message,
        )
    }

    @Test
    fun `leaves a message with no repeat alone`() {
        val message = "Failed to import session: Request failed: HTTP transport error: connection reset"
        assertEquals(message, CliError(ExitCode.Network, message).message)
    }

    /** Two identical words with a frame between them are two frames, not a double wrap. */
    @Test
    fun `only collapses adjacent segments`() {
        val message = "failed: middle: failed: done"
        assertEquals(message, CliError(ExitCode.Internal, message).message)
    }

    @Test
    fun `leaves a message with no segments alone`() {
        assertEquals("plain", CliError(ExitCode.Internal, "plain").message)
    }
}
