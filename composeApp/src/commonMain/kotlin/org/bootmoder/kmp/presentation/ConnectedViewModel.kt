package org.bootmoder.kmp.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.bootmoder.kmp.shared.domain.repository.BleRepository

class ConnectedViewModel(
    private val appState: AppState,
    private val bleRepository: BleRepository
) : ViewModel() {
    val connectedDevice: StateFlow<org.bootmoder.kmp.shared.domain.entity.BleDevice?> = appState.connectedDevice
    val serialNumber: StateFlow<String?> = appState.serialNumber
    val deviceModel: StateFlow<String?> = appState.deviceModel

    /** Разрыв соединения и сброс данных для возврата к сканированию. */
    fun disconnectAndReset() {
        viewModelScope.launch {
            bleRepository.disconnect()
            appState.clearAll()
        }
    }
}

