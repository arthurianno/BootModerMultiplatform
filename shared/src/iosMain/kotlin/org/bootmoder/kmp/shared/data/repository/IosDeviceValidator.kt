package org.bootmoder.kmp.shared.data.repository

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ScanMode
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
import org.bootmoder.kmp.shared.domain.usecase.SendCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendPinCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendVersionCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase
import org.bootmoder.kmp.shared.util.FirmwareProtocol
import org.bootmoder.kmp.shared.util.Logger
import org.bootmoder.kmp.shared.util.ParsedVersionResponse
import org.bootmoder.kmp.shared.util.classifyFirmwareProtocol
import org.bootmoder.kmp.shared.util.extractPinCode
import org.bootmoder.kmp.shared.util.extractVersionFromPath
import org.bootmoder.kmp.shared.util.isSupportedSatelliteName
import org.bootmoder.kmp.shared.util.parseSerialResponse
import org.bootmoder.kmp.shared.util.parseVersionResponse
import org.bootmoder.kmp.shared.util.serialSuffix

class IosDeviceValidator(
    private val connectUseCase: ConnectDeviceUseCase,
    private val disconnectUseCase: DisconnectDeviceUseCase,
    private val sendPinUseCase: SendPinCommandUseCase,
    private val sendVersionUseCase: SendVersionCommandUseCase,
    private val sendBootModeUseCase: SendBootModeCommandUseCase,
    private val sendCommandUseCase: SendCommandUseCase,
    private val processFilesUseCase: ProcessFilesUseCase,
    private val loadFirmwareUseCase: LoadFirmwareUseCase,
    private val loadConfigurationUseCase: LoadConfigurationUseCase,
    private val loadDfuUseCase: LoadDfuUseCase,
    private val scanDevicesUseCase: ScanDevicesUseCase,
    private val stopScanUseCase: StopScanUseCase
) : DeviceValidator {

    private val log = Logger("IosDeviceValidator")

    override suspend fun validateDevice(
        device: BleDevice,
        dataMatrixValue: String,
        zipFilePaths: List<String>
    ): ValidationResult = runCatching {
        val deviceName = device.name.orEmpty()
        if (!isSupportedSatelliteName(deviceName)) {
            return ValidationResult(
                isValid = false,
                version = null,
                message = "Неподдерживаемое устройство: ${device.displayName}"
            )
        }

        val connected = connectUseCase(device)
        if (!connected) {
            return ValidationResult(
                isValid = false,
                version = null,
                message = "Не удалось подключиться к устройству. Убедитесь, что оно включено и находится рядом"
            )
        }

        val pin = dataMatrixValue.extractPinCode()
        val pinValid = sendPinUseCase(device, "pin.$pin")
        if (!pinValid) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false,
                version = null,
                message = "Неверный серийный номер. Проверьте DataMatrix код на устройстве"
            )
        }

        val versionResponse = sendVersionUseCase(device)
        val parsedVersion = parseVersionResponse(versionResponse)
        if (parsedVersion == null) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false,
                version = null,
                message = "Не удалось прочитать версию прошивки устройства"
            )
        }
        log.d("version parsed: hw=${parsedVersion.hardwareRevision}, sw=${parsedVersion.softwareVersion}")

        val serialResponse = sendCommandUseCase("serial").getOrDefault("")
        val serial = parseSerialResponse(serialResponse)
        if (serial.isNullOrBlank()) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false,
                version = versionResponse,
                message = "Не удалось прочитать серийный номер устройства"
            )
        }
        log.d("serial parsed: $serial")

        val serialSuffix = serialSuffix(serial)
        val nameSuffix = serialSuffix(deviceName)
        if (serialSuffix != null && nameSuffix != null && serialSuffix != nameSuffix) {
            disconnectUseCase()
            return ValidationResult(
                isValid = false,
                version = versionResponse,
                message = "Серийный номер не совпадает с BLE-именем устройства"
            )
        }

        val info = mutableMapOf<String, String>()
        val isValid = when (classifyFirmwareProtocol(parsedVersion.softwareVersion)) {
            FirmwareProtocol.NRF52_NORDIC_DFU -> {
                log.d("Chip: Nordic DFU")
                val filePath = findFilePath(zipFilePaths, "DfuAppOnline")
                updateIfNeeded(
                    filePath = filePath,
                    deviceVersion = parsedVersion.softwareVersion,
                    versionResponse = versionResponse,
                    info = info
                ) {
                    updateNordic(device, filePath, parsedVersion, serialSuffix, info)
                }
            }
            FirmwareProtocol.WCH58X_BOOT_MODE -> {
                log.d("Chip: WCH BootMode")
                val filePath = findFilePath(zipFilePaths, "AppOnline")
                updateIfNeeded(
                    filePath = filePath,
                    deviceVersion = parsedVersion.softwareVersion,
                    versionResponse = versionResponse,
                    info = info
                ) {
                    updateWch(device, filePath, parsedVersion, info)
                }
            }
            FirmwareProtocol.UNKNOWN -> {
                info["message"] = "Версия прошивки не поддерживается: $versionResponse"
                false
            }
        }.also {
            disconnectUseCase()
        }

        ValidationResult(
            isValid = isValid,
            version = info["updatedVersion"] ?: versionResponse,
            message = info["message"]
                ?: if (isValid) "Устройство успешно проверено и обновлено" else "Проверка завершилась с ошибкой"
        )
    }.onFailure { e ->
        log.e("Validation error", e)
        runCatching { disconnectUseCase() }
    }.getOrElse { e ->
        ValidationResult(
            isValid = false,
            version = null,
            message = e.message ?: "Произошла непредвиденная ошибка. Попробуйте ещё раз"
        )
    }

    private suspend fun updateIfNeeded(
        filePath: String,
        deviceVersion: String,
        versionResponse: String,
        info: MutableMap<String, String>,
        updater: suspend () -> Boolean
    ): Boolean {
        val fileVersion = extractVersionFromPath(filePath)
        if (fileVersion != null && fileVersion == deviceVersion) {
            info["message"] = "Устройство уже содержит актуальную версию прошивки"
            return true
        }
        return updater().also { ok ->
            if (ok) {
                val hwPart = Regex("""hw:\S+""").find(versionResponse)?.value.orEmpty()
                info["updatedVersion"] = if (hwPart.isNotEmpty() && fileVersion != null) {
                    "$hwPart sw:$fileVersion"
                } else {
                    versionResponse
                }
            }
        }
    }

    private suspend fun updateNordic(
        device: BleDevice,
        filePath: String,
        parsedVersion: ParsedVersionResponse,
        serialSuffix: String?,
        info: MutableMap<String, String>
    ): Boolean = runCatching {
        val bootResponse = sendBootModeUseCase(device)
        if (!bootResponse.contains("boot.ok", ignoreCase = true)) {
            info["message"] = "Устройство не перешло в режим обновления. Попробуйте ещё раз"
            return false
        }

        disconnectUseCase()

        val expectedName = serialSuffix?.let { "Dfu$it" }
        log.d("Scanning for Nordic DFU device: expectedName=$expectedName")
        val dfuDevice = findDfuDevice(expectedName, serialSuffix)
        if (dfuDevice == null) {
            info["message"] = "Устройство не появилось в режиме обновления. Попробуйте ещё раз"
            return false
        }

        val result = loadDfuUseCase(dfuDevice.address, filePath)
        if (result.isSuccess) {
            info["message"] = "Прошивка успешно обновлена"
            true
        } else {
            info["message"] = "Не удалось обновить прошивку. Попробуйте ещё раз"
            false
        }
    }.getOrElse { e ->
        info["message"] = "Ошибка при обновлении (Nordic ${parsedVersion.softwareVersion}): ${e.message}"
        log.e("Nordic update error", e)
        false
    }

    private suspend fun updateWch(
        device: BleDevice,
        filePath: String,
        parsedVersion: ParsedVersionResponse,
        info: MutableMap<String, String>
    ): Boolean = runCatching {
        val bootResponse = sendBootModeUseCase(device)
        if (!bootResponse.contains("boot.ok", ignoreCase = true)) {
            info["message"] = "Устройство не перешло в режим обновления. Попробуйте ещё раз"
            return false
        }

        val filesResult = processFilesUseCase(filePath)
        if (filesResult.isFailure) {
            info["message"] = "Ошибка чтения файла прошивки. Проверьте установочный файл"
            return false
        }

        val (binData, datData) = filesResult.getOrThrow()
        log.d("WCH firmware: bin=${binData.size}b dat=${datData.size}b")

        val firmwareOk = loadFirmwareUseCase(binData, binData.size).isSuccess
        if (!firmwareOk) {
            info["message"] = "Не удалось записать прошивку на устройство. Попробуйте ещё раз"
            return false
        }

        val configOk = loadConfigurationUseCase(datData).isSuccess
        if (configOk) {
            info["message"] = "Прошивка успешно обновлена"
            true
        } else {
            info["message"] = "Прошивка записана, но не удалось применить конфигурацию"
            false
        }
    }.getOrElse { e ->
        info["message"] = "Ошибка при обновлении (WCH ${parsedVersion.softwareVersion}): ${e.message}"
        log.e("WCH update error", e)
        false
    }

    private suspend fun findDfuDevice(expectedName: String?, serialSuffix: String?): BleDevice? =
        try {
            scanDevicesUseCase(ScanMode.BALANCED)
            withTimeoutOrNull(30_000L) {
                scanDevicesUseCase.observe().firstOrNull { device ->
                    val name = device.name.orEmpty()
                    val matched = name.startsWith("Dfu", ignoreCase = true) &&
                        (expectedName?.let { name.equals(it, ignoreCase = true) } == true ||
                            serialSuffix?.let { name.contains(it) } == true ||
                            expectedName == null)
                    if (name.startsWith("Dfu", ignoreCase = true)) {
                        log.d("DFU candidate: name=${device.name}, rssi=${device.rssi}, uuid=${device.address}, matched=$matched")
                    }
                    matched
                }
            }
        } finally {
            stopScanUseCase()
        }

    private fun findFilePath(paths: List<String>, partialName: String): String {
        log.d("Searching firmware file '$partialName' in: $paths")
        val normalized = paths.map { normalizePath(it) }
        normalized.firstOrNull { path ->
            path.substringAfterLast('/').contains(partialName, ignoreCase = true) &&
                path.endsWith(".zip", ignoreCase = true)
        }?.let { return it }

        if (normalized.size == 1 && normalized.first().endsWith(".zip", ignoreCase = true)) {
            log.w("Firmware file '$partialName' not found by name, using the only provided zip")
            return normalized.first()
        }

        throw IllegalStateException("Файл прошивки '$partialName' не найден")
    }

    private fun normalizePath(path: String): String =
        if (path.startsWith("file://", ignoreCase = true)) {
            path.removePrefix("file://").substringBefore("?")
        } else {
            path
        }
}
