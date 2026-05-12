package org.bootmoder.kmp.shared.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ConnectionState
import org.bootmoder.kmp.shared.domain.entity.ScanMode

/**
 * Контракт для всех BLE-операций.
 *
 * Объединяет сканирование, GATT-подключение и UART-протокол BootModer:
 *  — строковые команды (pin / version / boot / battery)
 *  — бинарная запись прошивки и конфигурации
 *  — Nordic DFU
 *
 * Реализуется платформенно: AndroidBleRepository (Nordic BLE SDK) / IosBleRepository (CoreBluetooth).
 */
interface BleRepository {

    // ── Состояния ─────────────────────────────────────────────────────────────

    /** true, пока идёт BLE-сканирование */
    val isScanning: StateFlow<Boolean>

    /** Набор устройств, найденных за текущую сессию сканирования */
    val discoveredDevices: StateFlow<Set<BleDevice>>

    /** Текущее состояние GATT-соединения */
    val connectionState: StateFlow<ConnectionState>

    // ── Сканирование ───────────────────────────────────────────────────────────

    /** Запускает BLE-сканирование */
    suspend fun startScan(mode: ScanMode = ScanMode.BALANCED)

    /** Останавливает активное сканирование */
    fun stopScan()

    /** Горячий Flow устройств по мере их обнаружения (используется в DFU-поиске) */
    fun observeDevices(): Flow<BleDevice>

    // ── Соединение ────────────────────────────────────────────────────────────

    /** Подключается к устройству по GATT */
    suspend fun connect(device: BleDevice): Result<Unit>

    /** Разрывает текущее GATT-соединение */
    suspend fun disconnect(): Result<Unit>

    /** true, если устройство сейчас подключено */
    fun isConnected(): Boolean

    // ── UART строковые команды ────────────────────────────────────────────────

    /**
     * Отправляет строковую команду через UART Write-характеристику
     * и ждёт ответа через Notify-характеристику.
     * @return Строковый ответ устройства
     */
    suspend fun sendCommand(command: String): Result<String>

    /**
     * Отправляет бинарную команду и ждёт бинарного ответа через Notify.
     * Используется для Raw-режима (чтение/запись конфигурации).
     */
    suspend fun sendRawBytes(command: ByteArray): Result<ByteArray>

    // ── Бинарная прошивка (WCH-метод) ─────────────────────────────────────────

    /**
     * Записывает прошивку чанками через UART Write.
     * @param data     Полный бинарный образ прошивки (.bin)
     * @param fileSize Размер файла в байтах
     */
    suspend fun loadFirmware(data: ByteArray, fileSize: Int): Result<Unit>

    /**
     * Записывает конфигурацию (.dat файл) и отключается.
     */
    suspend fun loadConfiguration(data: ByteArray): Result<Unit>

    // ── Nordic DFU ────────────────────────────────────────────────────────────

    /**
     * Запускает Nordic DFU-обновление.
     * @param address  Адрес/UUID DFU-устройства, найденного сканированием
     * @param filePath Путь к .zip файлу прошивки
     */
    suspend fun loadDfu(address: String, filePath: String): Result<Unit>

    // ── Утилиты ───────────────────────────────────────────────────────────────

    /**
     * Очищает буфер необработанных BLE-уведомлений (pendingStringResponses).
     * Вызывается перед boot-последовательностью, чтобы устаревшие ответы
     * (например, от предыдущего сеанса аутентификации) не были возвращены
     * как ответ на команду "boot".
     */
    fun clearPendingBuffer()
}


