package org.bootmoder.kmp.shared.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bootmoder.kmp.shared.data.ble.BluetoothDeviceMapper
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ValidationResult
import org.bootmoder.kmp.shared.domain.repository.DeviceValidator
import org.bootmoder.kmp.shared.domain.usecase.ConnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.DisconnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadConfigurationUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadDfuUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadFirmwareUseCase
import org.bootmoder.kmp.shared.domain.usecase.ProcessFilesUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendBootModeCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendPinCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendVersionCommandUseCase
import org.bootmoder.kmp.shared.util.VersionCheckResult
import org.bootmoder.kmp.shared.util.checkFirmwareVersion
import org.bootmoder.kmp.shared.util.extractPinCode
import org.bootmoder.kmp.shared.util.extractVersionFromPath
import org.bootmoder.kmp.shared.util.toDfuAddress
import java.io.File

/**
 * Android-реализация [DeviceValidator].
 *
 * Оркестрирует полный цикл:
 *  1. Подключение к устройству
 *  2. Проверка PIN из DataMatrix
 *  3. Чтение версии прошивки
 *  4. Определение типа чипа (Nordic / WCH)
 *  5. Обновление прошивки соответствующим методом
 *  6. Отключение
 *
 * Принимает готовые файловые пути [zipFilePaths] — URI-резолюция выполняется
 * методом [resolveUriToPath] если путь начинается с "content://" или "file://".
 */
class AndroidDeviceValidatorImpl(
    private val context: Context,
    private val connectUseCase: ConnectDeviceUseCase,
    private val disconnectUseCase: DisconnectDeviceUseCase,
    private val sendPinUseCase: SendPinCommandUseCase,
    private val sendVersionUseCase: SendVersionCommandUseCase,
    private val sendBootModeUseCase: SendBootModeCommandUseCase,
    private val processFilesUseCase: ProcessFilesUseCase,
    private val loadFirmwareUseCase: LoadFirmwareUseCase,
    private val loadConfigurationUseCase: LoadConfigurationUseCase,
    private val loadDfuUseCase: LoadDfuUseCase,
    private val scanDevicesUseCase: ScanDevicesUseCase
) : DeviceValidator {

    override suspend fun validateDevice(
        device: BleDevice,
        dataMatrixValue: String,
        zipFilePaths: List<String>
    ): ValidationResult = runCatching {

        // 1. Подключение
        val connected = connectUseCase(device)
        if (!connected) return ValidationResult(
            isValid = false, version = null,
            message = "Не удалось подключиться к устройству. Убедитесь, что оно включено и находится рядом"
        )

        // 2. Проверка PIN
        val pin = dataMatrixValue.extractPinCode()
        val pinValid = sendPinUseCase(device, "pin.$pin")
        if (!pinValid) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false, version = null,
                message = "Неверный серийный номер. Проверьте DataMatrix код на устройстве"
            )
        }

        // 3. Чтение версии
        val version = sendVersionUseCase(device)
        if (!version.contains("hw:") || !version.contains("sw:")) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false, version = null,
                message = "Не удалось прочитать версию прошивки устройства"
            )
        }

        val firmwareInfo = mutableMapOf<String, String>()

        // 4. Определение типа чипа и обновление
        val isValid = when (version.checkFirmwareVersion()) {
            is VersionCheckResult.NordicDfu -> {
                Log.i(TAG, "Chip: Nordic DFU")
                val filePath = findFilePath(zipFilePaths, "DfuAppOnline")
                val fileVersion = extractVersionFromPath(filePath)
                val deviceVersion = extractVersionFromPath(version)

                if (fileVersion != null && fileVersion == deviceVersion) {
                    firmwareInfo["message"] = "Устройство уже содержит актуальную версию прошивки"
                    true
                } else {
                    updateNordic(device, filePath, version, firmwareInfo)
                }
            }

            is VersionCheckResult.WchUart -> {
                Log.i(TAG, "Chip: WCH UART")
                val filePath = findFilePath(zipFilePaths, "AppOnline")
                val fileVersion = extractVersionFromPath(filePath)
                val deviceVersion = extractVersionFromPath(version)

                if (fileVersion != null && fileVersion == deviceVersion) {
                    firmwareInfo["message"] = "Устройство уже содержит актуальную версию прошивки"
                    true
                } else {
                    updateWch(device, filePath, version, firmwareInfo)
                }
            }

            is VersionCheckResult.UnsupportedVersion -> {
                firmwareInfo["message"] = "Версия прошивки не поддерживается: $version"
                false
            }

            is VersionCheckResult.InvalidFormat -> {
                firmwareInfo["message"] = "Не удалось определить версию прошивки"
                false
            }
        }.also { disconnectUseCase() }

        ValidationResult(
            isValid = isValid,
            version = firmwareInfo["updatedVersion"] ?: version,
            message = firmwareInfo["message"]
                ?: if (isValid) "Устройство успешно проверено и обновлено" else "Проверка завершилась с ошибкой"
        )

    }.onFailure { e ->
        Log.e(TAG, "Validation error", e)
        runCatching { disconnectUseCase() }
    }.getOrDefault(
        ValidationResult(isValid = false, version = null, message = "Произошла непредвиденная ошибка. Попробуйте ещё раз")
    )

    // ── Nordic DFU update ─────────────────────────────────────────────────────

    private suspend fun updateNordic(
        device: BleDevice,
        filePath: String,
        version: String,
        info: MutableMap<String, String>
    ): Boolean = runCatching {
        val bootResponse = sendBootModeUseCase(device)
        if (!bootResponse.contains("boot.ok")) {
            info["message"] = "Устройство не перешло в режим обновления. Попробуйте ещё раз"
            return false
        }

        disconnectUseCase()

        val dfuAddress = device.address.toDfuAddress()
        Log.i(TAG, "Scanning for DFU device: $dfuAddress")
        val dfuDevice = findDfuDevice(dfuAddress)

        if (dfuDevice == null) {
            info["message"] = "Устройство не появилось в режиме обновления. Попробуйте ещё раз"
            scanDevicesUseCase.observe() // stop
            return false
        }

        scanDevicesUseCase  // stop scan implicitly via flow cancel

        val result = loadDfuUseCase(dfuDevice.address, filePath)
        if (result.isSuccess) {
            val hwPart = "hw:\\S+".toRegex().find(version)?.value ?: ""
            val newVersion = extractVersionFromPath(filePath)
            info["updatedVersion"] = if (hwPart.isNotEmpty() && newVersion != null) "$hwPart sw:$newVersion" else version
            info["message"] = "Прошивка успешно обновлена"
            true
        } else {
            info["message"] = "Не удалось обновить прошивку. Попробуйте ещё раз"
            false
        }
    }.getOrElse { e ->
        info["message"] = "Ошибка при обновлении (Nordic): ${e.message}"
        Log.e(TAG, "Nordic update error", e)
        false
    }

    // ── WCH UART update ───────────────────────────────────────────────────────

    private suspend fun updateWch(
        device: BleDevice,
        filePath: String,
        version: String,
        info: MutableMap<String, String>
    ): Boolean = runCatching {
        val bootResponse = sendBootModeUseCase(device)
        if (!bootResponse.contains("boot.ok")) {
            info["message"] = "Устройство не перешло в режим обновления. Попробуйте ещё раз"
            return false
        }

        val filesResult = processFilesUseCase(filePath)
        if (filesResult.isFailure) {
            info["message"] = "Ошибка чтения файла прошивки. Проверьте установочный файл"
            disconnectUseCase()
            return false
        }

        val (binData, datData) = filesResult.getOrThrow()
        Log.i(TAG, "WCH firmware: bin=${binData.size}b dat=${datData.size}b")

        val firmwareOk = loadFirmwareUseCase(binData, binData.size).isSuccess
        if (!firmwareOk) {
            info["message"] = "Не удалось записать прошивку на устройство. Попробуйте ещё раз"
            disconnectUseCase()
            return false
        }

        val configOk = loadConfigurationUseCase(datData).isSuccess
        if (configOk) {
            val hwPart = "hw:\\S+".toRegex().find(version)?.value ?: ""
            val newVersion = extractVersionFromPath(filePath)
            info["updatedVersion"] = if (hwPart.isNotEmpty() && newVersion != null) "$hwPart sw:$newVersion" else version
            info["message"] = "Прошивка успешно обновлена"
            true
        } else {
            info["message"] = "Прошивка записана, но не удалось применить конфигурацию"
            false
        }
    }.getOrElse { e ->
        info["message"] = "Ошибка при обновлении (WCH): ${e.message}"
        Log.e(TAG, "WCH update error", e)
        false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun findDfuDevice(dfuAddress: String): BleDevice? =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(30_000) {
                    var found: BleDevice? = null
                    scanDevicesUseCase()
                    scanDevicesUseCase.observe().collect { device ->
                        Log.d(TAG, "Scanning: ${device.address}")
                        if (device.address == dfuAddress) {
                            found = device
                            return@collect
                        }
                    }
                    found
                }
            }.getOrNull()
        }

    /**
     * Находит нужный файл из переданных путей по частичному имени.
     * Поддерживает content:// URI (копирует в кэш) и file:// пути.
     */
    private fun findFilePath(paths: List<String>, partialName: String): String {
        Log.i(TAG, "Ищем файл '$partialName' в: $paths")

        // Сначала ищем среди переданных путей
        for (path in paths) {
            val resolved = resolveToFilePath(path, partialName)
            if (resolved != null) {
                Log.i(TAG, "Найден: $resolved")
                return resolved
            }
        }

        // Фолбэк: берём из assets
        Log.w(TAG, "Файл '$partialName' не найден в переданных путях, ищем в assets")
        return copyAssetToCache(partialName)
    }

    private fun resolveToFilePath(path: String, partialName: String): String? {
        return when {
            path.startsWith("content://") -> resolveContentUri(Uri.parse(path), partialName)
            path.startsWith("file://")    -> resolveFileUri(Uri.parse(path), partialName)
            else -> {
                // Обычный файловый путь
                val file = File(path)
                if (file.exists() && file.name.contains(partialName, ignoreCase = true)) path
                else null
            }
        }
    }

    private fun resolveContentUri(uri: Uri, partialName: String): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val fileName = cursor.getString(nameIndex)
                if (fileName.contains(partialName, ignoreCase = true)) {
                    val tempFile = File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempFile.absolutePath
                } else null
            } else null
        }
    }.getOrNull()

    private fun resolveFileUri(uri: Uri, partialName: String): String? {
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists() && file.name.contains(partialName, ignoreCase = true)) path else null
    }

    private fun copyAssetToCache(partialName: String): String {
        val assetFiles = context.assets.list("") ?: emptyArray()
        val matchingAsset = assetFiles.find { it.contains(partialName, ignoreCase = true) }
            ?: throw IllegalStateException("Asset '$partialName' not found")

        val cacheFile = File(context.cacheDir, matchingAsset)
        if (!cacheFile.exists()) {
            context.assets.open(matchingAsset).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return cacheFile.absolutePath
    }

    companion object {
        private const val TAG = "AndroidDeviceValidator"
    }
}

