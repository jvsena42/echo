package com.github.jvsena42.loopky.util

/**
 * iOS logging via [println] (routed to the Xcode console / stderr).
 *
 * Do NOT use `NSLog("%@", msg)` here: a Kotlin `String` is not an `NSString*` in the C varargs
 * ABI, so `%@` sends a selector to a non-object pointer and crashes with EXC_BAD_ACCESS in
 * `objc_opt_respondsToSelector`. [println] formats on the Kotlin side and is crash-safe.
 */
actual object Log {
    actual fun d(tag: String, message: String) {
        println("D/$tag: $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("W/$tag: $message ${throwable?.stackTraceToString() ?: ""}")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("E/$tag: $message ${throwable?.stackTraceToString() ?: ""}")
    }
}
