package org.bootmoder.kmp.shared.domain.entity

/**
 * Состояние GATT-соединения с BLE-устройством.
 */
enum class ConnectionState {
    /** Устройство не подключено */
    DISCONNECTED,
    /** Идёт установка соединения */
    CONNECTING,
    /** Устройство подключено, сервисы обнаружены */
    CONNECTED,
    /** Идёт разрыв соединения */
    DISCONNECTING
}

