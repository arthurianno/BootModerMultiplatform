package org.bootmoder.kmp.shared.util

/**
 * Платформо-независимый логгер.
 * Реализуется через expect/actual:
 * - Android → android.util.Log
 * - iOS     → NSLog / print
 *
 * Использование:
 * ```kotlin
 * private val log = Logger("MyTag")
 * log.d("Сканирование запущено")
 * log.e("Ошибка подключения", exception)
 * ```
 */
expect class Logger(tag: String) {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String)
    fun e(message: String, throwable: Throwable? = null)
}

