package org.bootmoder.kmp.shared.util

/**
 * Константы BLE-протокола BootModer.
 * Чистый Kotlin — совместим с commonMain.
 */
object BluetoothConstants {
    /** Максимальный размер чанка при записи прошивки (WCH-метод) */
    const val CHUNK_SIZE = 128

    /** Размер блока конфигурации в байтах */
    const val CONFIGURATION_SIZE = 16

    /** Стартовый байт пакета BootModer-команды */
    const val BOOT_MODE_START: Byte = 0x24

    /** Команда записи чанка прошивки */
    const val FIRMWARE_CHUNK_CMD: Byte = 0x01

    /** Команда записи конфигурации */
    const val CONFIGURATION_CMD: Byte = 0x04

    // ── UART Service UUIDs ────────────────────────────────────────────────────
    const val UART_SERVICE_UUID            = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val UART_RX_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val UART_TX_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

    // ── MTU ───────────────────────────────────────────────────────────────────
    const val REQUEST_MTU = 247
}
