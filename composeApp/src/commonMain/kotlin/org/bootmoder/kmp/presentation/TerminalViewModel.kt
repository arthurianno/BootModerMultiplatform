package org.bootmoder.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

data class TerminalState(val responses: List<String> = emptyList())

class TerminalViewModel(
    private val bleRepository: BleRepository,
    private val deviceWorkingRepository: DeviceWorkingRepository,
    private val appState: AppState
) : ViewModel() {

    private val _terminalState = MutableStateFlow(TerminalState())
    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    val deviceInfo: StateFlow<BleDevice?> = _deviceInfo.asStateFlow()

    init {
        viewModelScope.launch {
            appState.connectedDevice.collect { device ->
                _deviceInfo.value = device
            }
        }
    }

    suspend fun sendTerminalCommand(device: BleDevice, command: String) {
        if (!bleRepository.isConnected()) {
            _terminalState.update { it.copy(responses = it.responses + "Connecting to device...") }
            val connected = bleRepository.connect(device).isSuccess
            if (!connected) {
                _terminalState.update { it.copy(responses = it.responses + "Connection failed") }
                return
            }
            val savedPin = deviceWorkingRepository.getPassword(device.address)
            if (savedPin != null) {
                _terminalState.update { it.copy(responses = it.responses + "Sending PIN...") }
                val pinResponse = bleRepository.sendCommand("pin.$savedPin").getOrDefault("")
                when {
                    pinResponse.contains("pin.error") -> {
                        _terminalState.update { it.copy(responses = it.responses + "PIN error - command aborted") }
                        bleRepository.disconnect()
                        return
                    }
                    pinResponse.contains("pin.ok") ->
                        _terminalState.update { it.copy(responses = it.responses + "PIN accepted") }
                }
            }
        }
        _terminalState.update { it.copy(responses = it.responses + "> $command") }
        val response = bleRepository.sendCommand(command).getOrDefault("No response")
        _terminalState.update { it.copy(responses = it.responses + response) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(responses = emptyList()) }
    }

    /** Разрыв соединения и сброс данных для возврата к сканированию. */
    fun disconnectAndReset() {
        viewModelScope.launch {
            bleRepository.disconnect()
            appState.clearAll()
            clearTerminal()
        }
    }
}

