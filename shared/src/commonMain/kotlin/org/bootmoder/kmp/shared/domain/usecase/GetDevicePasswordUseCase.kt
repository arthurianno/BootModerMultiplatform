package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

/**
 * Получает сохранённый пароль/пин для BLE-устройства.
 * @return Пароль или null если не был сохранён
 */
class GetDevicePasswordUseCase(private val repository: DeviceWorkingRepository) {

    operator fun invoke(deviceAddress: String): String? {
        return repository.getPassword(deviceAddress)
    }
}

