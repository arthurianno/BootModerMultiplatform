package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Запрашивает уровень заряда батареи устройства.
 * Ответ формата: "...bX..." где X — цифра уровня заряда.
 * @return Уровень заряда (0-9) или null если не удалось прочитать
 */
class SendBatteryCheckCommandUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(device: BleDevice): Int? {
        val response = repository.sendCommand("battery").getOrNull() ?: return null
        return Regex("b(\\d)").find(response)?.groupValues?.get(1)?.toIntOrNull()
    }
}

