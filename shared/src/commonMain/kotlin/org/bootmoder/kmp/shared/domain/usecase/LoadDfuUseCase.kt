package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Запускает Nordic DFU-обновление.
 * @param address  DFU MAC-адрес (основной адрес + 1)
 * @param filePath Путь к .zip файлу прошивки
 */
class LoadDfuUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(address: String, filePath: String): Result<Unit> =
        repository.loadDfu(address, filePath)
}

