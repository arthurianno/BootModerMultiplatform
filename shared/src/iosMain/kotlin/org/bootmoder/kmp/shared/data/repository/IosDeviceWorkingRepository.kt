package org.bootmoder.kmp.shared.data.repository

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSUserDefaults
import org.bootmoder.kmp.shared.domain.entity.DeviceInfo
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

/**
 * iOS-реализация [DeviceWorkingRepository].
 * Хранение — NSUserDefaults через multiplatform-settings.
 */
class IosDeviceWorkingRepository : DeviceWorkingRepository {

    private val settings: Settings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)

    override fun savePassword(deviceAddress: String, password: String) {
        settings.putString("pwd_$deviceAddress", password)
    }

    override fun getPassword(deviceAddress: String): String? =
        settings.getStringOrNull("pwd_$deviceAddress")

    override fun saveDeviceInfo(info: DeviceInfo) {
        settings.putString("di_fw_${info.address}", info.firmwareVersion)
        settings.putString("di_hw_${info.address}", info.hardwareVersion)
        settings.putString("di_model_${info.address}", info.modelName)
        settings.putInt("di_battery_${info.address}", info.batteryLevel)
    }

    override fun getCachedDeviceInfo(address: String): DeviceInfo? {
        val fw = settings.getStringOrNull("di_fw_$address") ?: return null
        return DeviceInfo(
            address = address,
            firmwareVersion = fw,
            hardwareVersion = settings.getStringOrNull("di_hw_$address") ?: "",
            modelName = settings.getStringOrNull("di_model_$address") ?: "",
            batteryLevel = settings.getIntOrNull("di_battery_$address") ?: -1
        )
    }

    override suspend fun getDeviceInfo(address: String): Result<DeviceInfo> {
        val cached = getCachedDeviceInfo(address)
        return if (cached != null) Result.success(cached)
        else Result.failure(NoSuchElementException("Нет кэша DeviceInfo для $address"))
    }
}
