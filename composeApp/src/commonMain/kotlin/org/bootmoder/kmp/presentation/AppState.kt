package org.bootmoder.kmp.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bootmoder.kmp.shared.domain.entity.BleDevice

/** Глобальное состояние приложения — разделяется между экранами. */
class AppState {

    // ── Подключённое устройство ────────────────────────────────────────────────
    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _serialNumber = MutableStateFlow<String?>(null)
    val serialNumber: StateFlow<String?> = _serialNumber.asStateFlow()

    private val _deviceModel = MutableStateFlow<String?>(null)
    val deviceModel: StateFlow<String?> = _deviceModel.asStateFlow()

    fun setConnectedDevice(device: BleDevice?, serial: String? = null, model: String? = null) {
        _connectedDevice.value = device
        _serialNumber.value = serial
        _deviceModel.value = model
    }

    // ── Выбранный файл прошивки (для BootMode) ─────────────────────────────────
    private val _firmwarePath = MutableStateFlow<String?>(null)
    val firmwarePath: StateFlow<String?> = _firmwarePath.asStateFlow()

    private val _firmwareFileName = MutableStateFlow<String?>(null)
    val firmwareFileName: StateFlow<String?> = _firmwareFileName.asStateFlow()

    fun setFirmware(path: String, name: String) {
        // StateFlow игнорирует одинаковые значения — сначала сбрасываем путь до null,
        // чтобы повторный выбор того же файла всегда вызывал collector в BootModeViewModel.
        // Порядок важен: имя устанавливаем ДО пути, чтобы collector уже видел корректное имя.
        _firmwarePath.value = null
        _firmwareFileName.value = name
        _firmwarePath.value = path
    }

    fun clearFirmware() {
        _firmwarePath.value = null
        _firmwareFileName.value = null
    }

    /** Полный сброс состояния для подключения нового устройства. */
    fun clearAll() {
        _connectedDevice.value = null
        _serialNumber.value = null
        _deviceModel.value = null
        _firmwarePath.value = null
        _firmwareFileName.value = null
    }
}

