package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Запрашивает версию прошивки устройства.
 * Ответ формата: "hw:1.0 sw:4.2.1 ..."
 */
class SendVersionCommandUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(device: BleDevice): String =
        repository.sendCommand("version").getOrDefault("")
}

