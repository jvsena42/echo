package com.github.jvsena42.loopky.util

import android.util.Log as AndroidLog

actual object Log {
    @Volatile
    actual var debugEnabled: Boolean = false

    actual fun d(tag: String, message: String) {
        if (!debugEnabled) return
        AndroidLog.d(tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) AndroidLog.w(tag, message, throwable) else AndroidLog.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) AndroidLog.e(tag, message, throwable) else AndroidLog.e(tag, message)
    }
}
