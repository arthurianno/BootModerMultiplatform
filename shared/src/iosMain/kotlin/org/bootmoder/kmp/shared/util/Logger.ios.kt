package org.bootmoder.kmp.shared.util

import platform.Foundation.NSLog

/**
 * iOS-реализация Logger через NSLog.
 */
actual class Logger actual constructor(private val tag: String) {

    actual fun d(message: String) {
        NSLog("[D][$tag] $message")
    }

    actual fun i(message: String) {
        NSLog("[I][$tag] $message")
    }

    actual fun w(message: String) {
        NSLog("[W][$tag] $message")
    }

    actual fun e(message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[E][$tag] $message | ${throwable.message}")
        } else {
            NSLog("[E][$tag] $message")
        }
    }
}

