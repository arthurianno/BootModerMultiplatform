package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Отправляет PIN-команду устройству.
 * Формат: "pin.XXX", где XXX — вычисленный 3-значный код из DataMatrix.
 * @return true если ответ устройства == "pin.ok"
 */
class SendPinCommandUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(device: BleDevice, pin: String): Boolean =
        repository.sendCommand(pin).getOrNull() == "pin.ok"
}

