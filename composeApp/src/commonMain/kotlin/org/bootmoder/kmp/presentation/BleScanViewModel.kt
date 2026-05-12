package org.bootmoder.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ScanMode
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.domain.usecase.ConnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.GetDevicePasswordUseCase
import org.bootmoder.kmp.shared.domain.usecase.SaveDevicePasswordUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase
import org.bootmoder.kmp.shared.util.Logger

class BleScanViewModel(
    private val bleRepository: BleRepository,
    private val scanUseCase: ScanDevicesUseCase,
    private val stopScanUseCase: StopScanUseCase,
    private val connectDeviceUseCase: ConnectDeviceUseCase,
    private val savePasswordUseCase: SaveDevicePasswordUseCase,
    private val getPasswordUseCase: GetDevicePasswordUseCase,
    private val appState: AppState
) : ViewModel() {

    private val log = Logger("BleScanViewModel")

    val devices: StateFlow<Set<BleDevice>> = bleRepository.discoveredDevices
    val isScanning: StateFlow<Boolean> = bleRepository.isScanning

    private val _connectingDevice = MutableStateFlow<BleDevice?>(null)
    val connectingDevice: StateFlow<BleDevice?> = _connectingDevice.asStateFlow()

    private val _connectionInProgress = MutableStateFlow(false)
    val connectionInProgress: StateFlow<Boolean> = _connectionInProgress.asStateFlow()

    private val _navigateToConnected = MutableStateFlow(false)
    val navigateToConnected: StateFlow<Boolean> = _navigateToConnected.asStateFlow()

    private val _currentPin = MutableStateFlow("")
    val currentPin: StateFlow<String> = _currentPin.asStateFlow()

    private val _scanMode = MutableStateFlow(ScanMode.BALANCED)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private var scanJob: Job? = null

    fun onNavigatedToConnected() {
        _navigateToConnected.value = false
    }

    fun resetPinError() {
        _pinError.value = null
    }

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
        if (isScanning.value) {
            viewModelScope.launch {
                stopScan()
                startScan()
            }
        }
    }

    fun resetConnectionState() {
        _connectionInProgress.value = false
        _connectingDevice.value = null
        _navigateToConnected.value = false
        _currentPin.value = ""
    }

    fun updatePin(pin: String) {
        _currentPin.value = pin
    }

    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanUseCase(_scanMode.value)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        stopScanUseCase()
    }

    fun connectToDevice(device: BleDevice, pin: String) {
        if (_connectionInProgress.value) return
        stopScan()
        resetConnectionState()

        _connectingDevice.value = device
        _connectionInProgress.value = true
        resetPinError()

        log.d("connectToDevice: ${device.name} (${device.address}), pin length=${pin.length}")

        viewModelScope.launch {
            try {
                // 1. BLE-подключение
                val isConnected = connectDeviceUseCase(device)
                log.d("connectDeviceUseCase: $isConnected")
                if (!isConnected) {
                    _pinError.value = "Connection failed. Please try again."
                    _connectionInProgress.value = false
                    _connectingDevice.value = null
                    return@launch
                }

                // 2. Авторизация PIN
                val pinResponse = bleRepository.sendCommand("pin.$pin").getOrDefault("")
                log.d("pinResponse: '$pinResponse'")

                when {
                    // Успешная авторизация — принимаем "pin.ok" в любом регистре
                    pinResponse.contains("pin.ok", ignoreCase = true) -> {
                        savePasswordUseCase(device.address, pin)

                        // 3. Версия прошивки
                        val versionResponse = bleRepository.sendCommand("version").getOrDefault("")
                        log.d("versionResponse: '$versionResponse'")

                        // 4. Серийный номер
                        val serialResponse = bleRepository.sendCommand("serial").getOrDefault("")
                        log.d("serialResponse: '$serialResponse'")

                        val serial = serialResponse.removePrefix("ser.").trim()
                        val model = determineModel(versionResponse)
                        val updatedDevice = device.copy(version = versionResponse)
                        appState.setConnectedDevice(updatedDevice, serial, model)
                        log.d("navigate → ConnectedScreen: serial='$serial' model='$model'")
                        _navigateToConnected.value = true
                    }
                    pinResponse.contains("pin.error", ignoreCase = true) -> {
                        log.w("PIN rejected: '$pinResponse'")
                        _pinError.value = "Invalid PIN code. Please try again."
                        _connectionInProgress.value = false
                        _connectingDevice.value = null
                    }
                    pinResponse.isBlank() -> {
                        log.e("PIN response is blank (timeout?)")
                        _pinError.value = "No response from device. Check connection and try again."
                        _connectionInProgress.value = false
                        _connectingDevice.value = null
                    }
                    else -> {
                        log.e("Unexpected PIN response: '$pinResponse'")
                        _pinError.value = "Unexpected response: '$pinResponse'"
                        _connectionInProgress.value = false
                        _connectingDevice.value = null
                    }
                }
            } catch (e: Exception) {
                log.e("connectToDevice error: ${e.message}", e)
                _pinError.value = "Error: ${e.message ?: "Unknown error"}"
                _connectionInProgress.value = false
                _connectingDevice.value = null
            } finally {
                if (_pinError.value == null && !_navigateToConnected.value) {
                    _connectionInProgress.value = false
                    _connectingDevice.value = null
                }
            }
        }
    }

    private fun determineModel(versionResponse: String): String {
        return try {
            val versionString = versionResponse.substringAfter("sw:").trim()
            val versionParts = versionString.split(".").map { it.toIntOrNull() ?: 0 }
            if (versionParts.size >= 3) {
                val v = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
                when (v) {
                    in 4_001_000..4_004_999 -> "NORDIC"
                    in 4_005_000..4_009_999 -> "WCH"
                    else -> "UNKNOWN"
                }
            } else "UNKNOWN"
        } catch (_: Exception) { "UNKNOWN" }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
