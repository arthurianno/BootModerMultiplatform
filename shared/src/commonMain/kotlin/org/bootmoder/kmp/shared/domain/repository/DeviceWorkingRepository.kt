package org.bootmoder.kmp.shared.domain.repository

import org.bootmoder.kmp.shared.domain.entity.DeviceInfo

/**
 * Контракт для локального хранения данных устройства:
 * пароли/PIN-коды и кэш DeviceInfo.
 *
 * Реализуется через multiplatform-settings:
 *  — Android: SharedPreferences
 *  — iOS:     NSUserDefaults
 */
interface DeviceWorkingRepository {

    /** Сохраняет PIN/пароль для устройства по его адресу */
    fun savePassword(deviceAddress: String, password: String)

    /** Возвращает сохранённый PIN/пароль, или null */
    fun getPassword(deviceAddress: String): String?

    /** Сохраняет информацию об устройстве в локальный кэш */
    fun saveDeviceInfo(info: DeviceInfo)

    /** Возвращает закэшированный DeviceInfo, или null если не было сохранено */
    fun getCachedDeviceInfo(address: String): DeviceInfo?

    /**
     * Читает DeviceInfo — из кэша.
     * Реальное чтение из GATT выполняется через BleRepository.sendCommand.
     */
    suspend fun getDeviceInfo(address: String): Result<DeviceInfo>
}
