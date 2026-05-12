package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/** Разрывает текущее GATT-соединение. */
class DisconnectDeviceUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.disconnect()
}


