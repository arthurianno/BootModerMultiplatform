package org.bootmoder.kmp.shared.domain.repository

import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ValidationResult

/**
 * Контракт полного цикла валидации BLE-устройства через DataMatrix-код.
 *
 * Оркестрирует: подключение → пин → версия → обновление прошивки → отключение.
 *
 * На Android использует [android.net.Uri] (разрешается в файловый путь до вызова).
 * Интерфейс намеренно принимает готовые файловые пути [zipFilePaths],
 * чтобы оставаться KMP-совместимым без зависимости на android.net.Uri.
 */
interface DeviceValidator {

    /**
     * Запускает полный процесс валидации устройства.
     *
     * @param device           BLE-устройство для валидации
     * @param dataMatrixValue  Значение, считанное из DataMatrix-кода
     * @param zipFilePaths     Список абсолютных путей к zip-файлам прошивки
     *                         (URI-резолюция выполняется на платформенном слое)
     */
    suspend fun validateDevice(
        device: BleDevice,
        dataMatrixValue: String,
        zipFilePaths: List<String>
    ): ValidationResult
}

