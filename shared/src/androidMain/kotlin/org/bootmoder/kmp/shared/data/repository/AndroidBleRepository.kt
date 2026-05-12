package org.bootmoder.kmp.shared.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.bootmoder.kmp.shared.data.ble.BleService
import org.bootmoder.kmp.shared.data.ble.BluetoothDeviceMapper
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ConnectionState
import org.bootmoder.kmp.shared.domain.entity.ScanMode
import org.bootmoder.kmp.shared.domain.repository.BleRepository

/**
 * Android-реализация [BleRepository].
 * — Сканирование:            Android BLE Scanner (BluetoothLeScanner)
 * — Соединение и команды:   [BleService] (Nordic BLE SDK / BleServiceImpl)
 */
@SuppressLint("MissingPermission")
class AndroidBleRepository(
    private val context: Context,
    private val bleService: BleService,
    private val deviceMapper: BluetoothDeviceMapper
) : BleRepository {

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<Set<BleDevice>>(emptySet())
    override val discoveredDevices: StateFlow<Set<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Дедупликация по MAC-адресу — последний RSSI побеждает. */
    private val _devicesMap = mutableMapOf<String, BleDevice>()

    private var activeScanCallback: ScanCallback? = null

    // ── Сканирование ──────────────────────────────────────────────────────────

    override suspend fun startScan(mode: ScanMode) {
        if (_isScanning.value) return
        _discoveredDevices.value = emptySet()
        _devicesMap.clear()
        _isScanning.value = true

        val scanner = getLeScanner() ?: run {
            Log.e(TAG, "BluetoothLeScanner недоступен")
            _isScanning.value = false
            return
        }

        activeScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                // Принимаем рабочие устройства SatelliteOnlineNNNN и Nordic DFU bootloader DfuNNNN.
                if (!isSupportedAdvertisedName(name)) return
                val address = result.device.address
                val device = BleDevice(address = address, name = name, rssi = result.rssi)
                // Дедупликация по MAC — обновляем RSSI, не плодим дубликаты
                _devicesMap[address] = device
                _discoveredDevices.value = LinkedHashSet(_devicesMap.values)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
                _isScanning.value = false
            }
        }
        scanner.startScan(activeScanCallback)
        Log.i(TAG, "BLE scan started")
    }

    override fun stopScan() {
        getLeScanner()?.stopScan(activeScanCallback)
        activeScanCallback = null
        _isScanning.value = false
        Log.i(TAG, "BLE scan stopped")
    }

    override fun observeDevices(): Flow<BleDevice> = callbackFlow {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(deviceMapper.toDomainDevice(result.device, result.rssi))
            }
        }
        getLeScanner()?.startScan(cb)
        awaitClose { getLeScanner()?.stopScan(cb) }
    }

    // ── Соединение ────────────────────────────────────────────────────────────

    override suspend fun connect(device: BleDevice): Result<Unit> {
        _connectionState.value = ConnectionState.CONNECTING
        return try {
            val ok = bleService.connect(device) == true
            _connectionState.value = if (ok) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            if (ok) Result.success(Unit)
            else Result.failure(IllegalStateException("Не удалось подключиться к ${device.address}"))
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        _connectionState.value = ConnectionState.DISCONNECTING
        return try {
            bleService.disconnect()
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }

    override fun isConnected(): Boolean = bleService.isConnected()

    // ── UART команды ──────────────────────────────────────────────────────────

    override suspend fun sendCommand(command: String): Result<String> = try {
        Result.success(bleService.sendCommand(command))
    } catch (e: Exception) {
        Log.e(TAG, "sendCommand error: $command", e)
        Result.failure(e)
    }

    override suspend fun sendRawBytes(command: ByteArray): Result<ByteArray> = try {
        Result.success(bleService.sendRawBytes(command))
    } catch (e: Exception) {
        Log.e(TAG, "sendRawBytes error", e)
        Result.failure(e)
    }

    // ── Прошивка ──────────────────────────────────────────────────────────────

    override suspend fun loadFirmware(data: ByteArray, fileSize: Int): Result<Unit> = try {
        if (bleService.loadFirmware(data, fileSize)) Result.success(Unit)
        else Result.failure(IllegalStateException("Ошибка записи прошивки"))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun loadConfiguration(data: ByteArray): Result<Unit> = try {
        if (bleService.loadConfiguration(data)) Result.success(Unit)
        else Result.failure(IllegalStateException("Ошибка записи конфигурации"))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun loadDfu(address: String, filePath: String): Result<Unit> = try {
        if (bleService.loadDfu(address, filePath)) Result.success(Unit)
        else Result.failure(IllegalStateException("Nordic DFU завершился с ошибкой"))
    } catch (e: Exception) { Result.failure(e) }

    override fun clearPendingBuffer() {
        // Android BleService использует callback-модель — буфер не требуется
        Log.d(TAG, "clearPendingBuffer: no-op on Android")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun getLeScanner() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private fun isSupportedAdvertisedName(name: String): Boolean =
        name.startsWith("SatelliteOnline", ignoreCase = true) ||
            name.startsWith("Dfu", ignoreCase = true)

    companion object {
        private const val TAG = "AndroidBleRepository"
    }
}
