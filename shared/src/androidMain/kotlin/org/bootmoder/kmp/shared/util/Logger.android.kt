package org.bootmoder.kmp.shared.util

import android.util.Log

/**
 * Android-реализация Logger через android.util.Log.
 */
actual class Logger actual constructor(private val tag: String) {

    actual fun d(message: String) {
        Log.d(tag, message)
    }

    actual fun i(message: String) {
        Log.i(tag, message)
    }

    actual fun w(message: String) {
        Log.w(tag, message)
    }

    actual fun e(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

