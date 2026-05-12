package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

/**
 * Сохраняет пароль/пин для BLE-устройства.
 */
class SaveDevicePasswordUseCase(private val repository: DeviceWorkingRepository) {

    operator fun invoke(deviceAddress: String, password: String) {
        repository.savePassword(deviceAddress, password)
    }
}

