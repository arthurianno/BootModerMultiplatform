package org.bootmoder.kmp.shared.domain.entity

/**
 * Результат валидации BLE-устройства через DataMatrix-код.
 *
 * @param isValid  true — устройство прошло проверку и прошивка обновлена
 * @param version  Версия прошивки после проверки (hw/sw строка)
 * @param message  Сообщение для пользователя (успех или описание ошибки)
 */
data class ValidationResult(
    val isValid: Boolean,
    val version: String?,
    val message: String = ""
)

