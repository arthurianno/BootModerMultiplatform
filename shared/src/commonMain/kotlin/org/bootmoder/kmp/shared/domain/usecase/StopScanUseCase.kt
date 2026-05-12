package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Останавливает активное BLE-сканирование.
 */
class StopScanUseCase(private val repository: BleRepository) {

    operator fun invoke() {
        repository.stopScan()
    }
}

