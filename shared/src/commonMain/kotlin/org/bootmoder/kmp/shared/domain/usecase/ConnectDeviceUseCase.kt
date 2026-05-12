package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.util.Logger

/** Подключается к BLE-устройству. */
class ConnectDeviceUseCase(private val repository: BleRepository) {

    private val log = Logger("ConnectDeviceUseCase")

    suspend operator fun invoke(device: BleDevice): Boolean {
        return try {
            repository.connect(device).isSuccess
        } catch (e: Exception) {
            log.e("Ошибка подключения к устройству ${device.address}", e)
            false
        }
    }
}


