package com.github.jvsena42.loopky.util

/**
 * Minimal cross-platform logger. Android uses `android.util.Log`, iOS uses `println`.
 * Keep this intentionally tiny — swap for kermit (or similar) once we need structured logs.
 */
expect object Log {
    /**
     * Whether [d] does anything. **Off by default, so a release that forgets to set it stays
     * quiet** rather than the other way round.
     *
     * The app sets it once at start-up from its own debug flag (`LoopkyApp.onCreate`), which is
     * the only place that knows. `commonMain` cannot read `BuildConfig`, and `:shared` does not
     * generate one — a plain flag is cheaper than making it, and it keeps the decision in the one
     * file that already gates `initLogging()` the same way.
     *
     * Debug lines carry deck ids, author pubkys, follow actions and chunk urls. Credentials are
     * redacted before they ever get here (`AuthUrlRedaction`), so this is metadata, not secrets —
     * but metadata that reaches logcat, bug reports and anyone with the device on a cable, for no
     * benefit to a shipped build. [w] and [e] stay on: a production error is worth keeping.
     */
    var debugEnabled: Boolean

    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
