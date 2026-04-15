package com.github.jvsena42.eco.util

import android.util.Log as AndroidLog

actual object Log {
    actual fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) AndroidLog.w(tag, message, throwable) else AndroidLog.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) AndroidLog.e(tag, message, throwable) else AndroidLog.e(tag, message)
    }
}
