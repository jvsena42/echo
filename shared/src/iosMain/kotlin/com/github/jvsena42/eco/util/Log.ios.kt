package com.github.jvsena42.eco.util

import platform.Foundation.NSLog

actual object Log {
    actual fun d(tag: String, message: String) {
        NSLog("D/%@: %@", tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        NSLog("W/%@: %@ %@", tag, message, throwable?.stackTraceToString() ?: "")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        NSLog("E/%@: %@ %@", tag, message, throwable?.stackTraceToString() ?: "")
    }
}
