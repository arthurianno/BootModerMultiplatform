package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Отправляет произвольную строковую команду через UART-характеристику
 * и возвращает строковый ответ устройства.
 */
class SendCommandUseCase(private val repository: BleRepository) {
    suspend operator fun invoke(command: String): Result<String> =
        repository.sendCommand(command)
}


