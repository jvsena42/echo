package com.github.jvsena42.eco.util

/**
 * Minimal cross-platform logger. Android uses `android.util.Log`, iOS uses `NSLog`.
 * Keep this intentionally tiny — swap for kermit (or similar) once we need structured logs.
 */
expect object Log {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
