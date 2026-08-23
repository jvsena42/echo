package com.github.jvsena42.loopky.util

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The gate that keeps 114 debug lines out of a shipped build.
 *
 * There is no way to observe what `android.util.Log`/`println` did from a common test, so what is
 * pinned here is the part that actually matters and that a refactor could silently break: the
 * default is **off**, so a build that never sets the flag stays quiet rather than logging
 * everything. The wrong default is exactly how this would ship broken and look fine in debug.
 */
class LogTest {

    private val original = Log.debugEnabled

    @AfterTest
    fun restore() {
        Log.debugEnabled = original
    }

    @Test
    fun debugLoggingIsOffUntilSomethingTurnsItOn() {
        assertFalse(original, "Log.debugEnabled must default to false; LoopkyApp opts a debug build in")
    }

    @Test
    fun aDisabledDebugCallIsInertRatherThanThrowing() {
        Log.debugEnabled = false
        Log.d("Loopky/Test", "must not reach the platform logger")
    }

    @Test
    fun warningsAndErrorsAreNotGated() {
        // Deliberate: a production error is worth keeping. If these ever start honouring the flag,
        // a release stops reporting anything at all.
        Log.debugEnabled = false
        Log.w("Loopky/Test", "still logged")
        Log.e("Loopky/Test", "still logged", IllegalStateException("boom"))
    }
}
