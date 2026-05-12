package org.bootmoder.kmp.shared.domain.entity

/**
 * BLE-устройство, обнаруженное при сканировании.
 *
 * @param address  MAC-адрес (Android) или UUID периферийного устройства (iOS)
 * @param name     Имя устройства из рекламного пакета (может быть null)
 * @param rssi     Уровень сигнала в дБм (чем ближе к 0 — тем сильнее сигнал)
 * @param isValid  Результат валидации устройства (null — валидация не проводилась)
 * @param version  Версия прошивки устройства (заполняется после sendVersionCommand)
 * @param message  Дополнительное сообщение о статусе (ошибка, подсказка)
 */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int = 0,
    val isValid: Boolean? = null,
    val version: String? = null,
    val message: String? = null
) {
    /** Отображаемое имя — «имя» или «Неизвестное устройство», если имя не передаётся */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "Unknown device"
}

