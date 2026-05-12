package org.bootmoder.kmp.shared.data.ble

import org.bootmoder.kmp.shared.domain.entity.BleDevice

/**
 * Низкоуровневый Android-сервис для работы с Nordic BLE SDK.
 * Используется репозиториями androidMain.
 *
 * Инкапсулирует всю GATT-логику: подключение, MTU, notify, write, DFU.
 */
interface BleService {
    suspend fun connect(device: BleDevice): Boolean?
    suspend fun disconnect()
    fun isConnected(): Boolean
    suspend fun sendCommand(command: String): String
    suspend fun sendRawBytes(command: ByteArray): ByteArray
    suspend fun loadFirmware(data: ByteArray, fileSize: Int): Boolean
    suspend fun loadConfiguration(data: ByteArray): Boolean
    suspend fun loadDfu(address: String, filePath: String): Boolean
}

