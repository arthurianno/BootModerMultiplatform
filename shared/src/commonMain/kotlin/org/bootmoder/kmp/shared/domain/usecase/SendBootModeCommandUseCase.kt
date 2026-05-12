package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Переключает устройство в режим обновления прошивки (Boot Mode).
 * @return строковый ответ устройства ("boot.ok" при успехе)
 */
class SendBootModeCommandUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(device: BleDevice): String =
        repository.sendCommand("boot").getOrDefault("")
}

