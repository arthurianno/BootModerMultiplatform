package org.bootmoder.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ConnectionState
import org.bootmoder.kmp.shared.domain.entity.ScanMode
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.util.BluetoothConstants
import org.bootmoder.kmp.shared.util.Logger
import org.bootmoder.kmp.shared.util.buildConfigWritePacket
import org.bootmoder.kmp.shared.util.buildDataWritePacket
import org.bootmoder.kmp.shared.util.parseBootWriteResponse
import org.bootmoder.kmp.shared.util.validateBootWriteResponse
import org.bootmoder.kmp.shared.domain.usecase.ConnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.GetDevicePasswordUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadConfigurationUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadDfuUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadFirmwareUseCase
import org.bootmoder.kmp.shared.domain.usecase.ProcessFilesUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase

// ── Типы прошивки ──────────────────────────────────────────────────────────────

enum class FirmwareType { NORDIC, WCH }

data class FirmwareFile(
    val type: FirmwareType,
    val version: String,
    val fileName: String,
    val filePath: String
)

sealed class FirmwareUpdateState {
    object Idle : FirmwareUpdateState()
    data class Updating(val step: String = "Подготовка...") : FirmwareUpdateState()
    object Success : FirmwareUpdateState()
    data class Error(val message: String) : FirmwareUpdateState()
    data class BootError(val message: String) : FirmwareUpdateState()
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

class BootModeViewModel(
    private val appState: AppState,
    private val bleRepository: BleRepository,
    private val sendCommandUseCase: SendCommandUseCase,
    private val connectDeviceUseCase: ConnectDeviceUseCase,
    private val getPasswordUseCase: GetDevicePasswordUseCase,
    private val processFilesUseCase: ProcessFilesUseCase,
    private val loadFirmwareUseCase: LoadFirmwareUseCase,
    private val loadConfigurationUseCase: LoadConfigurationUseCase,
    private val loadDfuUseCase: LoadDfuUseCase,
    private val scanUseCase: ScanDevicesUseCase,
    private val stopScanUseCase: StopScanUseCase
) : ViewModel() {

    private val log = Logger("BootModeViewModel")

    val deviceInfo: StateFlow<BleDevice?> = appState.connectedDevice
    val serialNumber: StateFlow<String?> = appState.serialNumber
    val deviceModel: StateFlow<String?> = appState.deviceModel

    private val _selectedFirmware = MutableStateFlow<FirmwareFile?>(null)
    val selectedFirmware: StateFlow<FirmwareFile?> = _selectedFirmware.asStateFlow()

    private val _fileValidationError = MutableStateFlow<String?>(null)
    val fileValidationError: StateFlow<String?> = _fileValidationError.asStateFlow()

    private val _firmwareUpdateState = MutableStateFlow<FirmwareUpdateState>(FirmwareUpdateState.Idle)
    val firmwareUpdateState: StateFlow<FirmwareUpdateState> = _firmwareUpdateState.asStateFlow()

    private val _isUpdateButtonEnabled = MutableStateFlow(true)
    val isUpdateButtonEnabled: StateFlow<Boolean> = _isUpdateButtonEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            appState.firmwarePath.collect { path ->
                val name = appState.firmwareFileName.value
                if (path != null && name != null) {
                    processSelectedFirmware(path, name)
                }
            }
        }
    }

    fun clearSelectedFirmware() {
        _selectedFirmware.value = null
        _fileValidationError.value = null
        appState.clearFirmware()
    }

    fun resetUpdateState() {
        _firmwareUpdateState.value = FirmwareUpdateState.Idle
    }

    /** Разрыв соединения и сброс данных для возврата к сканированию. */
    fun disconnectAndReset() {
        viewModelScope.launch {
            log.d("disconnectAndReset: clearing session and AppState")
            bleRepository.disconnect()
            appState.clearAll()
            resetUpdateState()
        }
    }

    fun startFirmwareUpdate() {
        if (!_isUpdateButtonEnabled.value) {
            log.w("startFirmwareUpdate ignored: update already in progress")
            return
        }
        val firmware = _selectedFirmware.value ?: return
        log.d("startFirmwareUpdate: type=${firmware.type} ver=${firmware.version} file=${firmware.fileName}")
        viewModelScope.launch {
            _isUpdateButtonEnabled.value = false
            _firmwareUpdateState.value = FirmwareUpdateState.Updating("Проверка соединения...")
            try {
                // Убедиться что подключены и авторизованы
                if (!ensureConnectedAndAuthorized(firmware)) return@launch

                // ── Шаг 1: читаем ZIP-файл ДО отправки boot-команды ──────────
                // (файл во временной папке Inbox может исчезнуть если устройство
                //  перезагружается в boot-режиме до завершения чтения)
                val payload = when (firmware.type) {
                    FirmwareType.WCH -> {
                        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Чтение ZIP-файла...")
                        processFilesUseCase(firmware.filePath).getOrElse { e ->
                            log.e("processFilesUseCase failed: ${e.message}")
                            _firmwareUpdateState.value = FirmwareUpdateState.Error(
                                "Ошибка чтения ZIP: ${e.message ?: "Файл не найден или повреждён"}"
                            )
                            return@launch
                        }
                    }
                    FirmwareType.NORDIC -> null  // Nordic использует DFU по пути файла
                }

                // ── Шаг 2: сбросить буфер устаревших BLE-уведомлений ─────────
                // Ответы аутентификации (pin.ok, version.xxx, ser.xxx) могут
                // остаться в буфере. Без сброса sendCommand("boot") вернёт
                // стale-ответ вместо реального "boot.ok".
                bleRepository.clearPendingBuffer()
                log.d("pendingBuffer cleared before boot sequence")

                // ── Шаг 3: отправить команду "boot" ──────────────────────────
                _firmwareUpdateState.value = FirmwareUpdateState.Updating("Переход в boot-режим...")
                val bootResult = sendCommandUseCase("boot")
                val bootResp = bootResult.getOrDefault("")
                log.d("boot response: '$bootResp' (success=${bootResult.isSuccess})")
                if (!bootResp.contains("boot.ok", ignoreCase = true)) {
                    val reason = if (bootResult.isFailure)
                        bootResult.exceptionOrNull()?.message ?: "Таймаут"
                    else
                        "Неожиданный ответ: '$bootResp'"
                    _firmwareUpdateState.value = FirmwareUpdateState.BootError(
                        "Не удалось войти в boot-режим: $reason"
                    )
                    return@launch
                }
                log.d("boot.ok received, waiting for disconnect")

                // ── Шаг 4: запустить обновление ──────────────────────────────
                val success = when (firmware.type) {
                    FirmwareType.WCH    -> {
                        if (!connectToBootloaderAfterBoot()) return@launch
                        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Загрузка прошивки...")
                        updateWchFirmware(payload!!)
                    }
                    FirmwareType.NORDIC -> updateNordicFirmware(firmware)
                }

                if (success) {
                    log.d("update success, starting post-update reconnect")
                    _firmwareUpdateState.value = FirmwareUpdateState.Success
                    val originalDevice = appState.connectedDevice.value
                    val serial = appState.serialNumber.value
                    reconnectAfterUpdate(originalDevice, serial)
                }
            } catch (e: Exception) {
                log.e("startFirmwareUpdate error: ${e.message}")
                _firmwareUpdateState.value = FirmwareUpdateState.Error(e.message ?: "Unknown error")
            } finally {
                _isUpdateButtonEnabled.value = true
            }
        }
    }

    // ── Подключение и авторизация ─────────────────────────────────────────────

    private suspend fun ensureConnectedAndAuthorized(firmware: FirmwareFile): Boolean {
        if (bleRepository.isConnected()) return true

        val device = appState.connectedDevice.value ?: run {
            _firmwareUpdateState.value = FirmwareUpdateState.Error("No connected device")
            return false
        }
        val connected = connectDeviceUseCase(device)
        if (!connected) {
            _firmwareUpdateState.value = FirmwareUpdateState.Error("Connection failed")
            return false
        }

        val pinCommand = when (firmware.type) {
            FirmwareType.NORDIC -> {
                val savedPin = getPasswordUseCase(device.address)
                if (savedPin.isNullOrBlank()) {
                    _firmwareUpdateState.value = FirmwareUpdateState.Error("Device PIN is missing")
                    return false
                }
                "pin.$savedPin"
            }
            FirmwareType.WCH -> "pin.master"
        }

        val pinResp = sendCommandUseCase(pinCommand).getOrDefault("")
        if (!pinResp.contains("pin.ok")) {
            _firmwareUpdateState.value = FirmwareUpdateState.Error("PIN authorization failed")
            return false
        }
        return true
    }

    // ── WCH-обновление ────────────────────────────────────────────────────────

    private suspend fun updateWchFirmware(payload: Pair<ByteArray, ByteArray>): Boolean {
        val (binData, datData) = payload
        log.d("updateWchFirmware: bin=${binData.size}B  dat=${datData.size}B")
        log.d("firmware zip parsed: bin size=${binData.size}, dat size=${datData.size}")

        if (binData.isEmpty()) {
            return failUpdate("Firmware .bin is empty")
        }
        if (datData.size != BluetoothConstants.CONFIGURATION_SIZE) {
            return failUpdate("Файл .dat должен быть ровно ${BluetoothConstants.CONFIGURATION_SIZE} байт")
        }

        var address = 0
        var position = 0

        while (position < binData.size) {
            val originalSize = minOf(BluetoothConstants.CHUNK_SIZE, binData.size - position)
            var chunk = binData.copyOfRange(position, position + originalSize)
            if (chunk.size % 4 != 0) {
                val paddedSize = ((chunk.size + 3) / 4) * 4
                log.d("padding last chunk: address=${address.hex8()}, size=${chunk.size} -> $paddedSize with 0xFF")
                chunk = chunk.copyOf(paddedSize)
                for (i in originalSize until paddedSize) chunk[i] = 0xFF.toByte()
            }

            val progress = ((position + originalSize) * 100 / binData.size).coerceIn(0, 100)
            _firmwareUpdateState.value = FirmwareUpdateState.Updating("Отправка прошивки $progress%")
            log.d("sending data chunk: address=${address.hex8()}, size=${chunk.size}, progress=$progress")

            val packet = try {
                buildDataWritePacket(address, chunk)
            } catch (e: IllegalArgumentException) {
                return failUpdate(e.message ?: "Invalid firmware chunk")
            }

            val chunkResult = sendBootWritePacketWithRetry(
                packet = packet,
                expectedCommand = BluetoothConstants.FIRMWARE_CHUNK_CMD.toInt() and 0xFF,
                expectedAddress = address,
                expectedNum = chunk.size
            )
            if (chunkResult.isFailure) {
                return failUpdate("Ошибка передачи прошивки: ${chunkResult.exceptionOrNull()?.message}")
            }

            position += originalSize
            address += chunk.size
        }

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Отправка конфигурации...")
        log.d("config write started")
        val configPacket = try {
            buildConfigWritePacket(datData)
        } catch (e: IllegalArgumentException) {
            return failUpdate(e.message ?: "Invalid config")
        }
        val configResult = sendBootWritePacketWithRetry(
            packet = configPacket,
            expectedCommand = BluetoothConstants.CONFIGURATION_CMD.toInt() and 0xFF,
            expectedAddress = 0,
            expectedNum = BluetoothConstants.CONFIGURATION_SIZE
        )
        if (configResult.isFailure) {
            return failUpdate("Ошибка передачи конфигурации: ${configResult.exceptionOrNull()?.message}")
        }
        log.d("config write success")

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Ожидание перезагрузки...")
        log.d("waiting for final reboot/disconnect")
        if (waitForDisconnectAfterBoot(timeoutMs = 15_000L)) {
            log.d("final reboot/disconnect observed")
        } else {
            log.w("final reboot/disconnect was not observed in timeout after successful config write")
        }
        return true
    }

    private suspend fun connectToBootloaderAfterBoot(): Boolean {
        val originalDevice = appState.connectedDevice.value ?: return failUpdate("No connected device")

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Ожидание bootloader...")
        val disconnected = waitForDisconnectAfterBoot(timeoutMs = 10_000L)
        if (disconnected) {
            log.d("disconnected after boot command")
        } else {
            log.d("boot command did not disconnect; continuing on current BootMode connection")
            return true
        }

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Поиск bootloader...")
        log.d("scanning for bootloader")
        val bootloaderDevice = findBootloaderDevice(
            originalDevice = originalDevice,
            serial = appState.serialNumber.value
        )
        if (bootloaderDevice == null) {
            return failUpdate("Bootloader device not found")
        }

        log.d("selected bootloader peripheral: name=${bootloaderDevice.name}, rssi=${bootloaderDevice.rssi}, uuid=${bootloaderDevice.address}")
        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Подключение к bootloader...")
        val connected = connectDeviceUseCase(bootloaderDevice)
        if (!connected) {
            return failUpdate("Bootloader connection failed")
        }
        log.d("connected to bootloader: name=${bootloaderDevice.name}, uuid=${bootloaderDevice.address}")
        return true
    }

    private suspend fun waitForDisconnectAfterBoot(timeoutMs: Long): Boolean {
        val delayMs = 100L
        val attempts = maxOf(1, (timeoutMs / delayMs).toInt())
        repeat(attempts) {
            if (!bleRepository.isConnected() || bleRepository.connectionState.value == ConnectionState.DISCONNECTED) {
                return true
            }
            delay(delayMs)
        }
        return !bleRepository.isConnected() || bleRepository.connectionState.value == ConnectionState.DISCONNECTED
    }

    private suspend fun findBootloaderDevice(
        originalDevice: BleDevice,
        serial: String?,
        timeoutMs: Long = 30_000L
    ): BleDevice? {
        val suffix = serialSuffix(serial) ?: serialSuffix(originalDevice.name)
        val loggedCandidates = mutableSetOf<String>()
        var bestFallback: BleDevice? = null
        var ambiguityLogged = false

        // TODO: для точного matching желательно использовать manufacturer data
        // или bootloader-specific service UUID, если устройство начнёт их рекламировать.
        return try {
            scanUseCase(ScanMode.BALANCED)
            val delayMs = 250L
            val attempts = maxOf(1, (timeoutMs / delayMs).toInt())
            repeat(attempts) { attempt ->
                val candidates = bleRepository.discoveredDevices.value
                    .filter { it.name?.startsWith("SatelliteOnline", ignoreCase = true) == true }

                candidates.forEach { candidate ->
                    if (loggedCandidates.add(candidate.address)) {
                        log.d("bootloader candidate found: name=${candidate.name}, rssi=${candidate.rssi}, uuid=${candidate.address}, advertisement info=see platform scan log if available")
                    }
                }

                val exact = candidates.firstOrNull { candidate ->
                    val name = candidate.name.orEmpty()
                    candidate.address == originalDevice.address ||
                        name == originalDevice.name ||
                        (suffix != null && name.contains(suffix))
                }
                if (exact != null) return exact

                if (candidates.isNotEmpty()) {
                    val sorted = candidates.sortedByDescending { it.rssi }
                    bestFallback = sorted.first()
                    if (candidates.size > 1 && !ambiguityLogged) {
                        log.w("bootloader match is ambiguous; fallback will use closest RSSI. candidates=${candidates.joinToString { "${it.name}/${it.rssi}/${it.address}" }}")
                        ambiguityLogged = true
                    }
                    if (attempt >= BOOTLOADER_FALLBACK_AFTER_ATTEMPTS) {
                        log.d("selected bootloader peripheral by RSSI fallback: name=${bestFallback?.name}, rssi=${bestFallback?.rssi}, uuid=${bestFallback?.address}")
                        return bestFallback
                    }
                }

                delay(delayMs)
            }
            bestFallback?.also {
                log.d("selected bootloader peripheral after timeout by RSSI fallback: name=${it.name}, rssi=${it.rssi}, uuid=${it.address}")
            }
        } catch (e: Exception) {
            log.e("findBootloaderDevice failed: ${e.message}", e)
            null
        } finally {
            stopScanUseCase()
        }
    }

    private suspend fun sendBootWritePacketWithRetry(
        packet: ByteArray,
        expectedCommand: Int,
        expectedAddress: Int,
        expectedNum: Int
    ): Result<Unit> {
        repeat(BOOT_WRITE_MAX_BUSY_RETRIES + 1) { attempt ->
            val bytes = bleRepository.sendRawBytes(packet).getOrElse { e ->
                return Result.failure(Exception("No write response: ${e.message}"))
            }
            val response = try {
                parseBootWriteResponse(bytes)
            } catch (e: IllegalArgumentException) {
                return Result.failure(e)
            }
            log.d("response: flag=${response.flag.hex2()}, cmd=${response.command.hex2()}, address=${response.address.hex8()}, num=${response.num}")

            try {
                validateBootWriteResponse(response, expectedCommand, expectedAddress, expectedNum)
            } catch (e: IllegalArgumentException) {
                return Result.failure(e)
            }

            when (response.flag) {
                0x00 -> return Result.success(Unit)
                0x01 -> {
                    if (attempt == BOOT_WRITE_MAX_BUSY_RETRIES) {
                        return Result.failure(Exception("Device busy after $BOOT_WRITE_MAX_BUSY_RETRIES retries"))
                    }
                    log.d("retry busy: address=${expectedAddress.hex8()}, attempt=${attempt + 1}")
                    delay(100L)
                }
                0x02 -> return Result.failure(Exception("write failed"))
                0xFF -> return Result.failure(Exception("invalid command"))
                else -> return Result.failure(Exception("Unexpected response flag ${response.flag.hex2()}"))
            }
        }
        return Result.failure(Exception("Device busy"))
    }

    private fun failUpdate(reason: String): Boolean {
        log.e("update failed with reason: $reason")
        _firmwareUpdateState.value = FirmwareUpdateState.Error(reason)
        return false
    }

    private fun serialSuffix(value: String?): String? =
        value
            ?.filter { it.isDigit() }
            ?.takeLast(4)
            ?.takeIf { it.length == 4 }

    private fun Int.hex2(): String =
        "0x" + (this and 0xFF).toString(16).uppercase().padStart(2, '0')

    private fun Int.hex8(): String =
        "0x" + toString(16).uppercase().padStart(8, '0')

    private companion object {
        const val BOOT_WRITE_MAX_BUSY_RETRIES = 20
        const val BOOTLOADER_FALLBACK_AFTER_ATTEMPTS = 20 // 5 seconds at 250 ms
    }

    // ── Nordic DFU-обновление ─────────────────────────────────────────────────

    private suspend fun updateNordicFirmware(firmware: FirmwareFile): Boolean {
        val originalDevice = appState.connectedDevice.value
        val deviceAddress = originalDevice?.address
        if (originalDevice == null || deviceAddress.isNullOrBlank()) {
            _firmwareUpdateState.value = FirmwareUpdateState.Error("Device address unavailable")
            return false
        }

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Ожидание DFU bootloader...")
        val disconnected = waitForDisconnectAfterBoot(timeoutMs = 10_000L)
        if (disconnected) {
            log.d("disconnected after boot command")
        } else {
            log.w("disconnect before Nordic DFU scan was not observed in timeout; scanning anyway")
        }

        val dfuAddress = deviceAddress.toDfuAddressOrNull()
        val serialSuffix = serialSuffix(appState.serialNumber.value) ?: serialSuffix(originalDevice.name)
        val expectedDfuName = serialSuffix?.let { "Dfu$it" }
        log.d("scanning for Nordic DFU bootloader: expectedName=$expectedDfuName, fallbackAddress=$dfuAddress")
        val dfuDevice = findDeviceForDFU(
            expectedAddress = dfuAddress,
            expectedName = expectedDfuName,
            serialSuffix = serialSuffix
        )
        if (dfuDevice == null) {
            _firmwareUpdateState.value = FirmwareUpdateState.Error(
                "DFU device (${expectedDfuName ?: dfuAddress ?: "DfuNNNN"}) not found in 30s"
            )
            return false
        }
        log.d("selected Nordic DFU peripheral: name=${dfuDevice.name}, rssi=${dfuDevice.rssi}, address=${dfuDevice.address}")

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Загрузка DFU...")
        val dfuResult = loadDfuUseCase(dfuDevice.address, firmware.filePath)
        if (dfuResult.isFailure) {
            val reason = dfuResult.exceptionOrNull()?.message ?: "Unknown Nordic DFU error"
            log.e("update failed with reason: DFU failed: $reason")
            _firmwareUpdateState.value = FirmwareUpdateState.Error("DFU failed: $reason")
            return false
        }
        return true
    }

    /** Сканирует 30 секунд и возвращает Nordic DFU bootloader DfuNNNN. */
    private suspend fun findDeviceForDFU(
        expectedAddress: String?,
        expectedName: String?,
        serialSuffix: String?,
        timeoutMs: Long = 30_000L
    ): BleDevice? {
        val loggedCandidates = mutableSetOf<String>()
        var bestFallback: BleDevice? = null
        var ambiguityLogged = false

        return try {
            scanUseCase(ScanMode.BALANCED)
            val delayMs = 250L
            val attempts = maxOf(1, (timeoutMs / delayMs).toInt())
            repeat(attempts) { attempt ->
                val candidates = bleRepository.discoveredDevices.value
                    .filter { device ->
                        val name = device.name.orEmpty()
                        name.startsWith("Dfu", ignoreCase = true) ||
                            expectedAddress?.let { device.address.uppercase() == it.uppercase() } == true
                    }

                candidates.forEach { candidate ->
                    if (loggedCandidates.add(candidate.address)) {
                        log.d("bootloader candidate found: name=${candidate.name}, rssi=${candidate.rssi}, uuid=${candidate.address}, advertisement info=see platform scan log if available")
                    }
                }

                val exact = candidates.firstOrNull { candidate ->
                    val name = candidate.name.orEmpty()
                    expectedAddress?.let { candidate.address.uppercase() == it.uppercase() } == true ||
                        expectedName?.let { name.equals(it, ignoreCase = true) } == true ||
                        (serialSuffix != null &&
                            name.startsWith("Dfu", ignoreCase = true) &&
                            name.contains(serialSuffix))
                }
                if (exact != null) return exact

                val dfuCandidates = candidates.filter { it.name?.startsWith("Dfu", ignoreCase = true) == true }
                if (dfuCandidates.isNotEmpty()) {
                    val sorted = dfuCandidates.sortedByDescending { it.rssi }
                    bestFallback = sorted.first()
                    if (dfuCandidates.size > 1 && !ambiguityLogged) {
                        log.w("Nordic DFU match is ambiguous; fallback will use closest RSSI. candidates=${dfuCandidates.joinToString { "${it.name}/${it.rssi}/${it.address}" }}")
                        ambiguityLogged = true
                    }
                    if (attempt >= BOOTLOADER_FALLBACK_AFTER_ATTEMPTS) {
                        log.d("selected Nordic DFU peripheral by RSSI fallback: name=${bestFallback?.name}, rssi=${bestFallback?.rssi}, uuid=${bestFallback?.address}")
                        return bestFallback
                    }
                }

                delay(delayMs)
            }
            bestFallback?.also {
                log.d("selected Nordic DFU peripheral after timeout by RSSI fallback: name=${it.name}, rssi=${it.rssi}, uuid=${it.address}")
            }
        } catch (e: Exception) {
            log.e("findDeviceForDFU failed: ${e.message}", e)
            null
        } finally {
            stopScanUseCase()
        }
    }

    // ── Вспомогательные функции ───────────────────────────────────────────────

    /** MAC-адрес +1 на последнем байте (DFU-адрес для Nordic). На iOS CBPeripheral UUID не преобразуется. */
    private fun String.toDfuAddressOrNull(): String? {
        val tokens = split(":")
        if (tokens.size < 2) return null
        val lastByte = tokens.last().toIntOrNull(16) ?: return null
        val newLast = ((lastByte + 1) and 0xFF).toString(16).padStart(2, '0').uppercase()
        return tokens.dropLast(1).joinToString(":") + ":" + newLast
    }

    private fun firmwareTypeFromVersion(version: String): FirmwareType? {
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return null
        val value = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        return when (value) {
            in 4_001_000..4_004_999 -> FirmwareType.NORDIC
            in 4_005_000..4_009_999 -> FirmwareType.WCH
            else -> null
        }
    }

    private fun processSelectedFirmware(path: String, name: String) {
        log.d("processSelectedFirmware: name='$name' path='$path'")
        log.d("deviceModel='${appState.deviceModel.value}'")

        if (!name.lowercase().endsWith(".zip")) {
            log.e("Неверный формат файла: '$name'")
            _fileValidationError.value = "Неверный формат: $name. Ожидается .zip"
            return
        }
        val base = name.removeSuffix(".zip").removeSuffix(".ZIP")

        // Версия: берём последний сегмент после '_' или '-', иначе Unknown
        val version = base.substringAfterLast("_")
            .takeIf { it.isNotEmpty() && it != base }
            ?: base.substringAfterLast("-")
                .takeIf { it.isNotEmpty() && it != base }
            ?: "Unknown"
        val versionFirmwareType = firmwareTypeFromVersion(version)

        // 1. Пробуем определить тип по имени файла
        // 2. Если в имени есть версия — используем диапазоны протокола
        // 3. Если версии нет — используем модель подключённого устройства
        // 4. Последний fallback — WCH (наиболее распространённый тип)
        val firmwareType = when {
            base.contains("nordic", ignoreCase = true) -> FirmwareType.NORDIC
            base.contains("wch",    ignoreCase = true) -> FirmwareType.WCH
            versionFirmwareType != null -> versionFirmwareType
            else -> when (appState.deviceModel.value?.uppercase()) {
                "NORDIC" -> FirmwareType.NORDIC
                "WCH"    -> FirmwareType.WCH
                else     -> FirmwareType.WCH   // по умолчанию WCH
            }
        }

        log.d("firmwareType=$firmwareType  version=$version")
        _selectedFirmware.value = FirmwareFile(
            type    = firmwareType,
            version = version,
            fileName = name,
            filePath = path
        )
        _fileValidationError.value = null
    }

    private suspend fun reconnectAfterUpdate(originalDevice: BleDevice?, serial: String?) {
        log.d("reconnectAfterUpdate: waiting for reboot")
        delay(3000L) // Даем устройству время на перезагрузку

        _firmwareUpdateState.value = FirmwareUpdateState.Updating("Поиск устройства...")
        
        val suffix = serialSuffix(serial) ?: serialSuffix(originalDevice?.name)
        log.d("reconnecting: suffix=$suffix")

        val device = findNormalDevice(suffix, timeoutMs = 20_000L)
        if (device != null) {
            _firmwareUpdateState.value = FirmwareUpdateState.Updating("Подключение...")
            val connected = connectDeviceUseCase(device)
            if (connected) {
                log.d("reconnected successfully")
                // Пытаемся авторизоваться, чтобы пользователь сразу мог работать
                val pin = getPasswordUseCase(device.address)
                if (!pin.isNullOrBlank()) {
                    log.d("auto-authorizing with pin")
                    sendCommandUseCase("pin.$pin")
                }
                _firmwareUpdateState.value = FirmwareUpdateState.Success
            } else {
                log.w("reconnect failed")
                _firmwareUpdateState.value = FirmwareUpdateState.Error("Обновление завершено, но не удалось переподключиться автоматически. Выполните Scan вручную.")
            }
        } else {
            log.w("device not found after update")
            _firmwareUpdateState.value = FirmwareUpdateState.Error("Обновление завершено, но устройство не найдено после перезагрузки. Выполните Scan вручную.")
        }
    }

    private suspend fun findNormalDevice(suffix: String?, timeoutMs: Long): BleDevice? {
        log.d("scanning for normal device: suffix=$suffix")
        return try {
            scanUseCase(ScanMode.BALANCED)
            val delayMs = 500L
            val attempts = (timeoutMs / delayMs).toInt()
            repeat(attempts) {
                val candidates = bleRepository.discoveredDevices.value
                val match = candidates.firstOrNull { candidate ->
                    val name = candidate.name.orEmpty()
                    name.startsWith("SatelliteOnline", ignoreCase = true) &&
                        (suffix == null || name.contains(suffix))
                }
                if (match != null) {
                    log.d("found normal device match: ${match.name} (${match.address})")
                    return match
                }
                delay(delayMs)
            }
            null
        } catch (e: Exception) {
            log.e("findNormalDevice failed: ${e.message}")
            null
        } finally {
            stopScanUseCase()
        }
    }
}
