package org.bootmoder.kmp.shared.domain.repository

/**
 * Контракт для работы с файлами прошивки.
 *
 * Android-реализация использует java.util.zip.ZipFile.
 * iOS-реализация — добавить при Этапе 11.
 */
interface FileRepository {

    /**
     * Извлекает .bin и .dat файлы из zip-архива прошивки.
     *
     * @param zipPath Абсолютный путь к zip-файлу на устройстве
     * @return Pair(binData, datData) — бинарный образ и файл конфигурации
     */
    suspend fun processZipFiles(zipPath: String): Result<Pair<ByteArray, ByteArray>>
}

