package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/** Записывает конфигурационный блок (.dat) и разрывает соединение. */
class LoadConfigurationUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(data: ByteArray): Result<Unit> =
        repository.loadConfiguration(data)
}

