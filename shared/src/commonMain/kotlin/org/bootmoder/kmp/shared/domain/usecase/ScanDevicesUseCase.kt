package org.bootmoder.kmp.shared.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ScanMode
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/** Запускает BLE-сканирование в указанном режиме. */
class ScanDevicesUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(mode: ScanMode = ScanMode.BALANCED) =
        repository.startScan(mode)

    /** Горячий Flow устройств по мере обнаружения — для DFU-поиска. */
    fun observe(): Flow<BleDevice> = repository.observeDevices()
}


