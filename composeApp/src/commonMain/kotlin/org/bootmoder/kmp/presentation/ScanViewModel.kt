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
import org.bootmoder.kmp.shared.domain.usecase.CheckDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase

/**
 * ViewModel экрана сканирования.
 *
 * Жизненный цикл данных:
 *  1. Пользователь добавляет устройство по DataMatrix → [processScanResult]
 *  2. Нажимает «Сканировать» → [startScan]
 *  3. BLE-обнаруживает устройства → для каждого запускается [checkDevice]
 *  4. Результат валидации отражается в [devices]
 */
class ScanViewModel(
    private val scanUseCase: ScanDevicesUseCase,
    private val stopScanUseCase: StopScanUseCase,
    private val checkDeviceUseCase: CheckDeviceUseCase,
    val systemChecker: SystemChecker,
    private val bleRepository: BleRepository
) : ViewModel() {

    // ── Список устройств (добавленных через DataMatrix + обновляемых после валидации) ──
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    // ── Состояние сканирования из репозитория ─────────────────────────────────
    val isScanning: StateFlow<Boolean> = bleRepository.isScanning

    // ── Адрес (DataMatrix) устройства, которое сейчас проверяется ─────────────
    private val _processingDevice = MutableStateFlow<String?>(null)
    val processingDevice: StateFlow<String?> = _processingDevice.asStateFlow()

    // ── Пути к выбранным zip-файлам прошивки ──────────────────────────────────
    private val _zipFilePaths = MutableStateFlow<List<String>>(emptyList())
    val zipFilePaths: StateFlow<List<String>> = _zipFilePaths.asStateFlow()

    /** true — есть устройства и сканирование не идёт (можно очистить список) */
    val canClear: Boolean
        get() = _devices.value.isNotEmpty() && !isScanning.value

    private var scanJob: Job? = null

    // ── Публичные команды ─────────────────────────────────────────────────────

    fun setZipFilePaths(paths: List<String>) {
        _zipFilePaths.value = paths
    }

    /**
     * Добавляет устройство в список по DataMatrix-коду.
     * Если устройство с таким адресом уже есть — игнорируется (idempotent).
     */
    fun processScanResult(code: String) {
        val current = _devices.value
        if (current.none { it.address == code }) {
            _devices.value = current + BleDevice(address = code, name = null)
        }
    }

    /** Очищает список устройств (доступно только когда не идёт сканирование). */
    fun clearAll() {
        if (!isScanning.value) {
            _devices.value = emptyList()
        }
    }

    /**
     * Запускает BLE-сканирование и валидацию найденных устройств.
     * Для каждого обнаруженного BLE-устройства перебираются незавершённые DataMatrix-записи.
     */
    fun startScan() {
        scanJob?.cancel()
        viewModelScope.launch { scanUseCase(ScanMode.BALANCED) }
        scanJob = viewModelScope.launch {
            scanUseCase.observe().collect { discoveredDevice ->
                val pending = _devices.value.firstOrNull {
                    it.isValid == null && it.address != _processingDevice.value
                }
                if (pending != null) {
                    checkDevice(bleDevice = discoveredDevice, dataMatrix = pending.address)
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        stopScanUseCase()
    }

    // ── Приватная логика ──────────────────────────────────────────────────────

    private suspend fun checkDevice(bleDevice: BleDevice, dataMatrix: String) {
        // Отмечаем устройство как «обрабатывается» (по DataMatrix-адресу)
        _processingDevice.value = dataMatrix

        val result = checkDeviceUseCase(
            device = bleDevice,
            dataMatrixValue = dataMatrix,
            zipFilePaths = _zipFilePaths.value
        )

        // Обновляем запись в списке: имя из BLE + результат валидации
        _devices.value = _devices.value.map { device ->
            if (device.address == dataMatrix) {
                device.copy(
                    name = bleDevice.name,
                    isValid = result.isValid,
                    version = result.version,
                    message = result.message
                )
            } else device
        }

        _processingDevice.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}

