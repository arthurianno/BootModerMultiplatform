package org.bootmoder.kmp.shared.data.repository

import com.russhwolf.settings.Settings
import org.bootmoder.kmp.shared.domain.entity.DeviceInfo
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository
import org.bootmoder.kmp.shared.util.Logger

/**
 * Android-реализация [DeviceWorkingRepository].
 * Хранение через multiplatform-settings (SharedPreferences под капотом).
 */
class AndroidDeviceWorkingRepository(
    private val settings: Settings
) : DeviceWorkingRepository {

    private val log = Logger("AndroidDeviceWorkingRepository")

    // ── Пароли / PIN ──────────────────────────────────────────────────────────

    override fun savePassword(deviceAddress: String, password: String) {
        log.d("savePassword: address=$deviceAddress")
        settings.putString(passwordKey(deviceAddress), password)
    }

    override fun getPassword(deviceAddress: String): String? =
        settings.getStringOrNull(passwordKey(deviceAddress))

    // ── DeviceInfo кэш ────────────────────────────────────────────────────────

    override fun saveDeviceInfo(info: DeviceInfo) {
        log.d("saveDeviceInfo: address=${info.address}")
        settings.putString(fwKey(info.address), info.firmwareVersion)
        settings.putString(hwKey(info.address), info.hardwareVersion)
        settings.putString(modelKey(info.address), info.modelName)
        settings.putInt(batteryKey(info.address), info.batteryLevel)
    }

    override fun getCachedDeviceInfo(address: String): DeviceInfo? {
        val fw = settings.getStringOrNull(fwKey(address)) ?: return null
        return DeviceInfo(
            address = address,
            firmwareVersion = fw,
            hardwareVersion = settings.getStringOrNull(hwKey(address)) ?: "",
            modelName = settings.getStringOrNull(modelKey(address)) ?: "",
            batteryLevel = settings.getIntOrNull(batteryKey(address)) ?: -1
        )
    }

    override suspend fun getDeviceInfo(address: String): Result<DeviceInfo> {
        val cached = getCachedDeviceInfo(address)
        return if (cached != null) Result.success(cached)
        else Result.failure(NoSuchElementException("Нет кэша DeviceInfo для $address"))
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    private fun passwordKey(a: String) = "pwd_$a"
    private fun fwKey(a: String)       = "di_fw_$a"
    private fun hwKey(a: String)       = "di_hw_$a"
    private fun modelKey(a: String)    = "di_model_$a"
    private fun batteryKey(a: String)  = "di_battery_$a"
}
