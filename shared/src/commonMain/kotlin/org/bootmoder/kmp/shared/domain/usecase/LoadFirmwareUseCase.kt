package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/** Записывает бинарный образ прошивки (.bin) чанками через UART Write (WCH-метод). */
class LoadFirmwareUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(data: ByteArray, fileSize: Int): Result<Unit> =
        repository.loadFirmware(data, fileSize)
}

