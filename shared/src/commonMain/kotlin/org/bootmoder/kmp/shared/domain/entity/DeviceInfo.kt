package org.bootmoder.kmp.shared.domain.entity

/**
 * Информация о подключённом BLE-устройстве (читается из GATT-характеристик).
 *
 * @param address         MAC-адрес / UUID устройства
 * @param firmwareVersion Версия прошивки (Device Information Service → Firmware Revision String)
 * @param hardwareVersion Версия аппаратной части (Hardware Revision String)
 * @param modelName       Модель устройства (Model Number String)
 * @param batteryLevel    Уровень заряда в процентах (Battery Service), -1 если не поддерживается
 */
data class DeviceInfo(
    val address: String,
    val firmwareVersion: String = "",
    val hardwareVersion: String = "",
    val modelName: String = "",
    val batteryLevel: Int = -1
)

