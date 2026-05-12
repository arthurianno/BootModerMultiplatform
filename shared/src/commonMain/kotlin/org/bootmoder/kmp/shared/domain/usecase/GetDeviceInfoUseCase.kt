package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.DeviceInfo
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

/**
 * Получает информацию об устройстве (читает Device Information Service через GATT).
 * При успехе автоматически сохраняет результат в локальный кэш.
 */
class GetDeviceInfoUseCase(private val repository: DeviceWorkingRepository) {

    suspend operator fun invoke(address: String): Result<DeviceInfo> {
        return repository.getDeviceInfo(address).onSuccess { info ->
            repository.saveDeviceInfo(info)
        }
    }
}

