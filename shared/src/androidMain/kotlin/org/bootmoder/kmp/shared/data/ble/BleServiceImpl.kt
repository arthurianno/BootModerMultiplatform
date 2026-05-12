package org.bootmoder.kmp.shared.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.util.BluetoothConstants
import org.bootmoder.kmp.shared.util.BluetoothConstants.BOOT_MODE_START
import org.bootmoder.kmp.shared.util.BluetoothConstants.CHUNK_SIZE
import org.bootmoder.kmp.shared.util.BluetoothConstants.CONFIGURATION_CMD
import org.bootmoder.kmp.shared.util.BluetoothConstants.CONFIGURATION_SIZE
import org.bootmoder.kmp.shared.util.BluetoothConstants.FIRMWARE_CHUNK_CMD
import org.bootmoder.kmp.shared.util.buildCommandPacket
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

/**
 * Android-реализация [BleService] на базе Nordic BLE SDK.
 *
 * Архитектура:
 *  — [BleWorkManager] (inner class) extends [BleManager] — управляет GATT-соединением.
 *  — [responseChannel] — Channel для передачи UART-ответов из Notify-коллбэка в корутины.
 */
@SuppressLint("MissingPermission")
class BleServiceImpl(
    private val context: Context,
    private val deviceMapper: BluetoothDeviceMapper
) : BleService {

    private val bleManager = BleWorkManager(context)

    /** Буферизованный канал для UART-ответов устройства */
    private var responseChannel = Channel<String>(Channel.BUFFERED)

    /** Канал для бинарных ответов (Raw Mode) */
    private var rawResponseChannel = Channel<ByteArray>(Channel.BUFFERED)

    /** Флаг: ожидаем бинарный ответ вместо строкового */
    @Volatile private var expectRawResponse = false

    // ── Соединение ────────────────────────────────────────────────────────────

    override suspend fun connect(device: BleDevice): Boolean? = withContext(Dispatchers.IO) {
        val bluetoothDevice = deviceMapper.toBluetoothDevice(device) ?: return@withContext false
        try {
            bleManager.connect(bluetoothDevice)
                .retry(3, 100)
                .useAutoConnect(false)
                .await()
            Log.i(TAG, "Connected to ${device.address}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}", e)
            false
        }
    }

    override suspend fun disconnect() {
        bleManager.disconnect().await()
    }

    override fun isConnected(): Boolean = bleManager.isConnected

    // ── UART команды ──────────────────────────────────────────────────────────

    override suspend fun sendCommand(command: String): String {
        bleManager.sendCommand(command)
        return receiveResponse()
    }

    override suspend fun sendRawBytes(command: ByteArray): ByteArray {
        return bleManager.sendRawBytes(command)
    }

    private suspend fun receiveResponse(): String = responseChannel.receive()

    // ── Прошивка (WCH-метод) ──────────────────────────────────────────────────

    override suspend fun loadFirmware(data: ByteArray, fileSize: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (!bleManager.isConnected) {
                Log.e(TAG, "loadFirmware: device not connected")
                return@withContext false
            }

            var position = 0
            var remaining = fileSize

            while (remaining > 0) {
                val chunkSize = min(CHUNK_SIZE, remaining)
                val chunk = data.copyOfRange(position, position + chunkSize)
                bleManager.writeFirmwareChunk(chunk, position)
                position += chunkSize
                remaining -= chunkSize
                Log.d(TAG, "loadFirmware: remaining=$remaining bytes")
            }
            true
        }

    override suspend fun loadConfiguration(data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val buffer = data.copyOf(CONFIGURATION_SIZE)
            val result = bleManager.writeConfiguration(buffer)
            Log.i(TAG, "loadConfiguration result=$result")
            result
        }

    // ── Nordic DFU ────────────────────────────────────────────────────────────

    override suspend fun loadDfu(address: String, filePath: String): Boolean {
        return try {
            suspendCoroutine { continuation ->
                val listener = object : DfuLogger() {
                    override fun onDfuCompleted(deviceAddress: String) {
                        super.onDfuCompleted(deviceAddress)
                        DfuServiceListenerHelper.unregisterProgressListener(context, this)
                        continuation.resume(true)
                    }
                    override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                        super.onError(deviceAddress, error, errorType, message)
                        DfuServiceListenerHelper.unregisterProgressListener(context, this)
                        continuation.resume(false)
                    }
                }
                try {
                    DfuServiceListenerHelper.registerProgressListener(context, listener)
                    DfuServiceInitiator(address).apply {
                        setDeviceName("Dfu")
                        setKeepBond(false)
                        setForceDfu(true)
                        setNumberOfRetries(1)
                        setForceScanningForNewAddressInLegacyDfu(false)
                        setPrepareDataObjectDelay(400L)
                        setRebootTime(0)
                        setScanTimeout(2000)
                        setZip(filePath)
                    }.start(context, BooterDfuService::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "DFU start error: ${e.message}", e)
                    DfuServiceListenerHelper.unregisterProgressListener(context, listener)
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DFU error: ${e.message}", e)
            false
        }
    }

    // ── BleWorkManager — Nordic BleManager (GATT) ─────────────────────────────

    private inner class BleWorkManager(context: Context) : BleManager(context) {

        private var rxCharacteristic: BluetoothGattCharacteristic? = null
        private var txCharacteristic: BluetoothGattCharacteristic? = null

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            gatt.getService(UUID.fromString(BluetoothConstants.UART_SERVICE_UUID))?.let { service ->
                rxCharacteristic = service.getCharacteristic(UUID.fromString(BluetoothConstants.UART_RX_CHARACTERISTIC_UUID))
                txCharacteristic = service.getCharacteristic(UUID.fromString(BluetoothConstants.UART_TX_CHARACTERISTIC_UUID))
            }
            return rxCharacteristic != null && txCharacteristic != null
        }

        override fun initialize() {
            super.initialize()
            requestMtu(BluetoothConstants.REQUEST_MTU).enqueue()
            setNotificationCallback(txCharacteristic)
                .with { _, data ->
                    val response = data.value ?: ByteArray(0)
                    if (expectRawResponse) {
                        Log.d(TAG, "RAW response: ${response.size} bytes")
                        rawResponseChannel.trySend(response)
                    } else {
                        Log.d(TAG, "UART response: ${String(response)}")
                        responseChannel.trySend(String(response))
                    }
                }
            enableNotifications(txCharacteristic).enqueue()
        }

        override fun onServicesInvalidated() {
            rxCharacteristic = null
            txCharacteristic = null
            close()
        }

        suspend fun sendCommand(command: String) = withContext(Dispatchers.IO + SupervisorJob()) {
            check(isConnected && rxCharacteristic != null) { "Device not connected" }
            writeCharacteristic(
                rxCharacteristic,
                command.toByteArray(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).enqueue()
        }

        suspend fun sendRawBytes(command: ByteArray): ByteArray = withContext(Dispatchers.IO + SupervisorJob()) {
            check(isConnected && rxCharacteristic != null) { "Device not connected" }
            expectRawResponse = true
            rawResponseChannel = Channel<ByteArray>(Channel.BUFFERED)
            writeCharacteristic(
                rxCharacteristic,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).await()
            try {
                withTimeoutOrNull(5_000) { rawResponseChannel.receive() } ?: ByteArray(0)
            } finally {
                expectRawResponse = false
            }
        }

        fun writeFirmwareChunk(data: ByteArray, address: Int) {
            if (!isConnected) { Log.e(TAG, "writeFirmwareChunk: not connected"); return }
            val packet = buildCommandPacket(BOOT_MODE_START, FIRMWARE_CHUNK_CMD, address, data)
            writeCharacteristic(rxCharacteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
        }

        fun writeConfiguration(buffer: ByteArray): Boolean {
            if (!isConnected) { Log.e(TAG, "writeConfiguration: not connected"); return false }
            var success = false
            setNotificationCallback(txCharacteristic).with { _, _ -> success = true }
            enableNotifications(txCharacteristic).enqueue()
            val packet = buildCommandPacket(BOOT_MODE_START, CONFIGURATION_CMD, 0, buffer)
            writeCharacteristic(rxCharacteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).await()
            return success
        }
    }

    companion object {
        private const val TAG = "BleServiceImpl"
    }
}

