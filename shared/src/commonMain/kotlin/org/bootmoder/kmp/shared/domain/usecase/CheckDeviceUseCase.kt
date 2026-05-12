package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ValidationResult
import org.bootmoder.kmp.shared.domain.repository.DeviceValidator
import org.bootmoder.kmp.shared.util.Logger

/**
 * Оркестрирует полный цикл валидации устройства через DataMatrix-код.
 *
 * Делегирует в [DeviceValidator], реализованный платформенно
 * (Android — DeviceValidatorImpl с Uri-резолюцией файлов).
 *
 * @param zipFilePaths Абсолютные пути к zip-файлам (URI → Path разрешается на платформе)
 */
class CheckDeviceUseCase(private val validator: DeviceValidator) {

    private val log = Logger("CheckDeviceUseCase")

    suspend operator fun invoke(
        device: BleDevice,
        dataMatrixValue: String,
        zipFilePaths: List<String>
    ): ValidationResult {
        return try {
            log.i("Запуск валидации: ${device.address}, dataMatrix=$dataMatrixValue")
            validator.validateDevice(device, dataMatrixValue, zipFilePaths)
        } catch (e: Exception) {
            log.e("Ошибка валидации устройства", e)
            ValidationResult(
                isValid = false,
                version = null,
                message = "Произошла непредвиденная ошибка. Попробуйте ещё раз"
            )
        }
    }
}

