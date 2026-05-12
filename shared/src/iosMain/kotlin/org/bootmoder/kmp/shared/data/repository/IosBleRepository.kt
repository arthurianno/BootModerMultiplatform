package org.bootmoder.kmp.shared.data.repository

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.entity.ConnectionState
import org.bootmoder.kmp.shared.domain.entity.ScanMode
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.util.BluetoothConstants
import org.bootmoder.kmp.shared.util.Logger
import org.bootmoder.kmp.shared.util.buildConfigWritePacket
import org.bootmoder.kmp.shared.util.buildCommandPacket
import org.bootmoder.kmp.shared.util.buildDataWritePacket
import org.bootmoder.kmp.shared.util.parseManufacturerData
import org.bootmoder.kmp.shared.util.parseBootWriteResponse
import org.bootmoder.kmp.shared.util.validateBootWriteResponse
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS-реализация [BleRepository] через CoreBluetooth.
 *
 * Сканирование → CBCentralManager.scanForPeripheralsWithServices
 * Подключение  → connectPeripheral → discoverServices → discoverCharacteristics → setNotifyValue
 * UART Write   → writeValue(rxCharacteristic, CBCharacteristicWriteWithResponse)
 * UART Read    → уведомление txCharacteristic (notify)
 *
 * Nordic DFU   → CoreBluetooth Secure DFU (FE59, Control Point / Packet).
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleRepository : BleRepository {

    private val log = Logger("IosBleRepository")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── State ────────────────────────────────────────────────────────────────────

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<Set<BleDevice>>(emptySet())
    override val discoveredDevices: StateFlow<Set<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ── Hot stream of newly discovered devices ───────────────────────────────────

    private val _deviceFlow = MutableSharedFlow<BleDevice>(extraBufferCapacity = 128)
    private val seenAddresses = mutableSetOf<String>()

    /** Дедупликация по UUID — последний RSSI побеждает. */
    private val _devicesMap = mutableMapOf<String, BleDevice>()

    // ── UART characteristics ─────────────────────────────────────────────────────

    /** Characteristic для записи команд (UART RX на стороне устройства). */
    private var rxCharacteristic: CBCharacteristic? = null

    /** Characteristic для получения ответов через notifications (UART TX на стороне устройства). */
    private var txCharacteristic: CBCharacteristic? = null

    /** Secure DFU Control Point (Nordic FE59). */
    private var dfuControlPointCharacteristic: CBCharacteristic? = null

    /** Secure DFU Packet characteristic (Nordic FE59). */
    private var dfuPacketCharacteristic: CBCharacteristic? = null

    /** true, пока текущее подключение используется для Nordic Secure DFU. */
    private var dfuMode = false

    /** true, если мы ожидаем финальный дисконнект после выполнения DFU (reboot). */
    private var waitingForFinalDfuDisconnect = false

    // ── Active peripheral ────────────────────────────────────────────────────────

    private var connectedPeripheral: CBPeripheral? = null

    /** Кэш всех обнаруженных при сканировании периферийных устройств (uuid → CBPeripheral). */
    private val peripheralCache = mutableMapOf<String, CBPeripheral>()

    // ── Suspending callbacks ─────────────────────────────────────────────────

    private var connectCallback: ((Result<Unit>) -> Unit)? = null
    private var commandCallback: ((Result<String>) -> Unit)? = null
    private var writeCallback: ((Result<Unit>) -> Unit)? = null
    private var rawBytesCallback: ((Result<ByteArray>) -> Unit)? = null
    private var dfuConnectCallback: ((Result<Unit>) -> Unit)? = null
    private var dfuWriteCallback: ((Result<Unit>) -> Unit)? = null
    private var dfuNotificationCallback: ((Result<ByteArray>) -> Unit)? = null

    /** Буфер уведомлений, пришедших до установки commandCallback (race-condition). */
    private val pendingStringResponses = ArrayDeque<String>()

    // ── UART response accumulator ─────────────────────────────────────────────────

    private val responseBuffer = StringBuilder()

    // ── CBCentralManagerDelegate ─────────────────────────────────────────────────

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            log.d("BT state=${central.state} (poweredOn=${CBManagerStatePoweredOn})")
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val uuid = didDiscoverPeripheral.identifier.UUIDString
            val name = didDiscoverPeripheral.name ?: ""
            val advertisementInfo = advertisementSummary(advertisementData)
            val manufacturerBytes = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)
                ?.toKotlinByteArray()
            val manufacturerInfo = manufacturerBytes?.let { parseManufacturerData(it) }

            // Принимаем рабочие устройства SatelliteOnlineNNNN и Nordic DFU bootloader DfuNNNN.
            if (!isSupportedAdvertisedName(name)) {
                return
            }

            peripheralCache[uuid] = didDiscoverPeripheral

            val device = BleDevice(address = uuid, name = name, rssi = RSSI.intValue)
            // Дедупликация по UUID — если RSSI изменился, обновляем запись, но не плодим дубликаты
            _devicesMap[uuid] = device
            _discoveredDevices.value = LinkedHashSet(_devicesMap.values)

            if (seenAddresses.add(uuid)) {
                scope.launch { _deviceFlow.emit(device) }
            }
            val manufacturerLog = manufacturerBytes?.let {
                ", manufacturerData=${it.toHexString()}, manufacturerInfo=$manufacturerInfo"
            }.orEmpty()
            log.d("discovered: name=$name rssi=$RSSI uuid=$uuid advertisement info=$advertisementInfo$manufacturerLog")
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            log.d("didConnect: ${didConnectPeripheral.identifier.UUIDString}")
            _connectionState.value = ConnectionState.CONNECTING
            val serviceUuid = if (dfuMode) NORDIC_DFU_SERVICE_UUID else BluetoothConstants.UART_SERVICE_UUID
            didConnectPeripheral.discoverServices(listOf(CBUUID.UUIDWithString(serviceUuid)))
        }

        // ObjCSignatureOverride: обе функции имеют (CBCentralManager, CBPeripheral, NSError?) в Kotlin
        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            log.e("didFailToConnect: ${error?.localizedDescription}")
            _connectionState.value = ConnectionState.DISCONNECTED
            val result = Result.failure<Unit>(Exception(error?.localizedDescription ?: "Connection failed"))
            connectCallback?.invoke(result)
            connectCallback = null
            dfuConnectCallback?.invoke(result)
            dfuConnectCallback = null
            dfuMode = false
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            val reason = error?.localizedDescription ?: "clean"
            log.d("didDisconnect: $reason (dfuMode=$dfuMode, waitingForFinalDfuDisconnect=$waitingForFinalDfuDisconnect)")

            if (waitingForFinalDfuDisconnect) {
                log.d("Expected disconnect during DFU reboot - treating as success")
                // Вызываем коллбеки с успехом, если они еще висят
                dfuWriteCallback?.invoke(Result.success(Unit))
                dfuNotificationCallback?.invoke(Result.success(ByteArray(0)))
            } else if (error != null) {
                // Внезапная ошибка
                dfuWriteCallback?.invoke(Result.failure(Exception("Disconnected: $reason")))
                dfuNotificationCallback?.invoke(Result.failure(Exception("Disconnected: $reason")))
                commandCallback?.invoke(Result.failure(Exception("Disconnected: $reason")))
                rawBytesCallback?.invoke(Result.failure(Exception("Disconnected: $reason")))
            }

            // Очистка состояний
            cleanupDfuSession("didDisconnect")
            resetConnectionState("didDisconnect")
        }
    }

    // ── CBPeripheralDelegate ─────────────────────────────────────────────────────

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                log.e("didDiscoverServices error: ${didDiscoverServices.localizedDescription}")
                val result = Result.failure<Unit>(Exception(didDiscoverServices.localizedDescription))
                connectCallback?.invoke(result)
                connectCallback = null
                dfuConnectCallback?.invoke(result)
                dfuConnectCallback = null
                return
            }
            if (dfuMode) {
                val service = peripheral.services
                    ?.filterIsInstance<CBService>()
                    ?.firstOrNull { uuidMatches(it.UUID.UUIDString, NORDIC_DFU_SERVICE_UUID) }

                if (service == null) {
                    log.e("Nordic Secure DFU service not found")
                    dfuConnectCallback?.invoke(Result.failure(Exception("Nordic Secure DFU service FE59 not found")))
                    dfuConnectCallback = null
                    return
                }
                peripheral.discoverCharacteristics(
                    listOf(
                        CBUUID.UUIDWithString(NORDIC_DFU_CONTROL_POINT_UUID),
                        CBUUID.UUIDWithString(NORDIC_DFU_PACKET_UUID)
                    ),
                    forService = service
                )
                return
            }

            val service = peripheral.services
                ?.filterIsInstance<CBService>()
                ?.firstOrNull { it.UUID.UUIDString.uppercase() == BluetoothConstants.UART_SERVICE_UUID.uppercase() }

            if (service == null) {
                log.e("UART service not found")
                connectCallback?.invoke(Result.failure(Exception("UART service not found")))
                connectCallback = null
                return
            }
            // characteristicUUIDs — первый позиционный параметр (ObjC: discoverCharacteristics:forService:)
            peripheral.discoverCharacteristics(
                listOf(
                    CBUUID.UUIDWithString(BluetoothConstants.UART_RX_CHARACTERISTIC_UUID),
                    CBUUID.UUIDWithString(BluetoothConstants.UART_TX_CHARACTERISTIC_UUID)
                ),
                forService = service
            )
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                val result = Result.failure<Unit>(Exception(error.localizedDescription))
                connectCallback?.invoke(result)
                connectCallback = null
                dfuConnectCallback?.invoke(result)
                dfuConnectCallback = null
                return
            }
            val chars = didDiscoverCharacteristicsForService.characteristics?.filterIsInstance<CBCharacteristic>()
            if (dfuMode) {
                dfuControlPointCharacteristic = chars?.firstOrNull {
                    uuidMatches(it.UUID.UUIDString, NORDIC_DFU_CONTROL_POINT_UUID)
                }
                dfuPacketCharacteristic = chars?.firstOrNull {
                    uuidMatches(it.UUID.UUIDString, NORDIC_DFU_PACKET_UUID)
                }

                val controlPoint = dfuControlPointCharacteristic
                val packet = dfuPacketCharacteristic
                if (controlPoint == null || packet == null) {
                    val message = "Nordic DFU characteristics not found: controlPoint=${controlPoint?.UUID?.UUIDString}, packet=${packet?.UUID?.UUIDString}"
                    log.e(message)
                    dfuConnectCallback?.invoke(Result.failure(Exception(message)))
                    dfuConnectCallback = null
                    return
                }

                log.d("Nordic DFU characteristics discovered — CP=${controlPoint.UUID.UUIDString}, Packet=${packet.UUID.UUIDString}")
                peripheral.setNotifyValue(true, forCharacteristic = controlPoint)
                return
            }

            rxCharacteristic = chars?.firstOrNull {
                it.UUID.UUIDString.uppercase() == BluetoothConstants.UART_RX_CHARACTERISTIC_UUID.uppercase()
            }
            txCharacteristic = chars?.firstOrNull {
                it.UUID.UUIDString.uppercase() == BluetoothConstants.UART_TX_CHARACTERISTIC_UUID.uppercase()
            }

            val tx = txCharacteristic
            if (tx == null) {
                log.e("TX characteristic not found")
                connectCallback?.invoke(Result.failure(Exception("TX characteristic not found")))
                connectCallback = null
                return
            }
            // Подписываемся на уведомления от устройства (UART ответы)
            peripheral.setNotifyValue(true, forCharacteristic = tx)
        }

        // ObjCSignatureOverride: три peripheral(CBPeripheral, CBCharacteristic, NSError?) сливаются в одну Kotlin-сигнатуру
        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                val result = Result.failure<Unit>(Exception(error.localizedDescription))
                connectCallback?.invoke(result)
                connectCallback = null
                dfuConnectCallback?.invoke(result)
                dfuConnectCallback = null
                return
            }
            if (dfuMode) {
                _connectionState.value = ConnectionState.CONNECTED
                val withResponseMax = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
                val withoutResponseMax = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                log.d("Nordic Secure DFU ready — maximumWriteValueLength withResponse=$withResponseMax, withoutResponse=$withoutResponseMax")
                dfuConnectCallback?.invoke(Result.success(Unit))
                dfuConnectCallback = null
                return
            }

            // Подписка OK → соединение полностью готово
            _connectionState.value = ConnectionState.CONNECTED
            val withResponseMax = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
            val withoutResponseMax = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
            log.d("UART/DFU characteristics discovered — RX=${rxCharacteristic?.UUID?.UUIDString}, TX=${txCharacteristic?.UUID?.UUIDString}")
            log.d("maximumWriteValueLength: withResponse=$withResponseMax, withoutResponse=$withoutResponseMax, boot payload=${BluetoothConstants.CHUNK_SIZE}")
            log.d("connection interval 10..15 ms requested by spec; iOS CoreBluetooth manages connection interval automatically")
            connectCallback?.invoke(Result.success(Unit))
            connectCallback = null
        }

        /** Получен фрагмент ответа от устройства через TX-notifications. */
        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                log.e("didUpdateValue error: ${error.localizedDescription}")
                if (dfuMode) {
                    dfuNotificationCallback?.invoke(Result.failure(Exception(error.localizedDescription)))
                    dfuNotificationCallback = null
                    return
                }
                rawBytesCallback?.invoke(Result.failure(Exception(error.localizedDescription)))
                rawBytesCallback = null
                commandCallback?.invoke(Result.failure(Exception(error.localizedDescription)))
                commandCallback = null
                return
            }
            val rawData = didUpdateValueForCharacteristic.value ?: run {
                log.w("didUpdateValue: value is null, skipping")
                return
            }

            if (dfuMode && uuidMatches(didUpdateValueForCharacteristic.UUID.UUIDString, NORDIC_DFU_CONTROL_POINT_UUID)) {
                val bytes = rawData.toKotlinByteArray() ?: ByteArray(0)
                log.d("DFU notification: ${bytes.toHexString()}")
                val cb = dfuNotificationCallback
                if (cb != null) {
                    dfuNotificationCallback = null
                    cb.invoke(Result.success(bytes))
                } else {
                    log.d("DFU notification ignored: no active DFU command")
                }
                return
            }

            // Если ждём бинарный ответ — возвращаем сразу сырые байты
            if (rawBytesCallback != null) {
                val bytes = rawData.toKotlinByteArray() ?: ByteArray(0)
                log.d("didUpdateValue [binary]: ${bytes.size} bytes")
                rawBytesCallback?.invoke(Result.success(bytes))
                rawBytesCallback = null
                return
            }

            // Строковый ответ — доставляем немедленно (устройство шлёт каждый ответ
            // как отдельный BLE-пакет без \n, аналогично поведению Android Nordic SDK).
            val chunk = rawData.toKotlinString() ?: run {
                log.w("didUpdateValue: toKotlinString() returned null")
                return
            }
            responseBuffer.append(chunk)
            val response = responseBuffer.toString().trim()
            responseBuffer.clear()
            log.d("didUpdateValue [string]: '$response' (commandCallback=${commandCallback != null})")
            val cb = commandCallback
            if (cb != null) {
                commandCallback = null
                cb.invoke(Result.success(response))
            } else {
                // Уведомление пришло раньше, чем coroutine установила commandCallback
                // (race-condition при доставке через dispatch_async). Кладём в буфер.
                log.d("didUpdateValue: buffered '$response' (no active command)")
                pendingStringResponses.addLast(response)
            }
        }

        /** Подтверждение записи (WriteWithResponse). */
        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            val result = if (error == null) Result.success(Unit)
            else Result.failure<Unit>(Exception(error.localizedDescription))
            if (dfuMode &&
                (uuidMatches(didWriteValueForCharacteristic.UUID.UUIDString, NORDIC_DFU_CONTROL_POINT_UUID) ||
                    uuidMatches(didWriteValueForCharacteristic.UUID.UUIDString, NORDIC_DFU_PACKET_UUID))
            ) {
                dfuWriteCallback?.invoke(result)
                dfuWriteCallback = null
                return
            }
            writeCallback?.invoke(result)
            writeCallback = null
        }
    }

    // ── CBCentralManager ─────────────────────────────────────────────────────────

    /** Инициализируется на главном потоке (queue = null → главная очередь). */
    private val centralManager = CBCentralManager(delegate = centralDelegate, queue = null)

    // ── BleRepository ────────────────────────────────────────────────────────────

    override suspend fun startScan(mode: ScanMode) {
        log.d("startScan: mode=$mode")
        _discoveredDevices.value = emptySet()
        _devicesMap.clear()
        seenAddresses.clear()
        peripheralCache.clear()
        _isScanning.value = true
        // serviceUUIDs = null → сканируем ВСЕ BLE-устройства.
        // Устройства SatelliteOnlineNNNN могут не включать UART UUID в advertisement-пакет,
        // поэтому фильтр по имени делается в didDiscoverPeripheral.
        centralManager.scanForPeripheralsWithServices(
            serviceUUIDs = null,
            options = null
        )
    }

    override fun stopScan() {
        log.d("stopScan")
        centralManager.stopScan()
        _isScanning.value = false
    }

    override fun observeDevices(): Flow<BleDevice> = _deviceFlow.asSharedFlow()

    override suspend fun connect(device: BleDevice): Result<Unit> {
        val cbPeripheral = peripheralCache[device.address]
            ?: return Result.failure(Exception("Peripheral ${device.address} не найден в кэше — сначала запустите сканирование"))
        stopScan()
        connectedPeripheral = cbPeripheral
        cbPeripheral.delegate = peripheralDelegate
        return suspendCancellableCoroutine { cont ->
            connectCallback = { cont.resume(it) }
            centralManager.connectPeripheral(cbPeripheral, options = null)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        val p = connectedPeripheral ?: return Result.success(Unit)
        _connectionState.value = ConnectionState.DISCONNECTING
        centralManager.cancelPeripheralConnection(p)
        return Result.success(Unit)
    }

    override fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    override suspend fun sendCommand(command: String): Result<String> {
        val rx = rxCharacteristic ?: return Result.failure(Exception("Не подключено: rxCharacteristic=null"))
        val p = connectedPeripheral ?: return Result.failure(Exception("Не подключено: peripheral=null"))
        log.d("sendCommand → '$command'")
        val data = command.encodeToByteArray().toNSData()
        responseBuffer.clear()

        // Если устройство уже прислало ответ до того, как мы запросили его
        // (race-condition: notify через dispatch_async пришёл раньше coroutine),
        // возвращаем из буфера без отправки нового write.
        if (pendingStringResponses.isNotEmpty()) {
            val buffered = pendingStringResponses.removeFirst()
            log.d("sendCommand ← '$buffered' (from pending buffer, skipping write)")
            return Result.success(buffered)
        }

        val result = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                commandCallback = { cont.resume(it) }
                p.writeValue(data, forCharacteristic = rx, type = CBCharacteristicWriteWithResponse)
            }
        }
        if (result == null) {
            commandCallback = null
            log.e("sendCommand TIMEOUT: no response for '$command' in 10s")
            return Result.failure(Exception("Timeout: no response for '$command'"))
        }
        log.d("sendCommand ← '${result.getOrNull()}'")
        return result
    }

    override suspend fun loadFirmware(data: ByteArray, fileSize: Int): Result<Unit> {
        if (rxCharacteristic == null || connectedPeripheral == null) {
            return Result.failure(Exception("Не подключено"))
        }
        if (fileSize <= 0 || data.isEmpty()) {
            return Result.failure(Exception("Firmware .bin is empty"))
        }
        log.d("loadFirmware: size=$fileSize, chunk=${BluetoothConstants.CHUNK_SIZE}")
        var address = 0
        var position = 0
        var index = 0
        while (position < fileSize) {
            val originalSize = minOf(BluetoothConstants.CHUNK_SIZE, fileSize - position)
            var chunk = data.copyOfRange(position, position + originalSize)
            if (chunk.size % 4 != 0) {
                val paddedSize = ((chunk.size + 3) / 4) * 4
                log.d("loadFirmware: padding last chunk addr=${address.hex8()} size=${chunk.size} -> $paddedSize with 0xFF")
                chunk = chunk.copyOf(paddedSize)
                for (i in originalSize until paddedSize) chunk[i] = 0xFF.toByte()
            }

            val packet = try {
                buildDataWritePacket(address, chunk)
            } catch (e: IllegalArgumentException) {
                return Result.failure(e)
            }

            log.d("sending data chunk: address=${address.hex8()}, size=${chunk.size}, progress=${((position + originalSize) * 100) / fileSize}%")
            val result = writeBootPacketWithRetry(
                packet = packet,
                expectedCommand = BluetoothConstants.FIRMWARE_CHUNK_CMD.toInt() and 0xFF,
                expectedAddress = address,
                expectedNum = chunk.size
            )
            if (result.isFailure) {
                log.e("loadFirmware chunk $index (addr=$address) failed: ${result.exceptionOrNull()?.message}")
                return result
            }
            position += originalSize
            address += chunk.size
            index++
        }
        log.d("loadFirmware: done (${position} firmware bytes, lastAddress=${address.hex8()})")
        return Result.success(Unit)
    }

    override suspend fun loadConfiguration(data: ByteArray): Result<Unit> {
        if (rxCharacteristic == null || connectedPeripheral == null) {
            return Result.failure(Exception("Не подключено"))
        }
        val packet = try {
            buildConfigWritePacket(data)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        log.d("config write started: size=${data.size}")
        return writeBootPacketWithRetry(
            packet = packet,
            expectedCommand = BluetoothConstants.CONFIGURATION_CMD.toInt() and 0xFF,
            expectedAddress = 0,
            expectedNum = BluetoothConstants.CONFIGURATION_SIZE
        ).onSuccess {
            log.d("config write success")
        }
    }

    override suspend fun loadDfu(address: String, filePath: String): Result<Unit> {
        log.d("Nordic Secure DFU started: address=$address file=$filePath")
        val dfuPackage = IosNordicDfuZipParser.parse(filePath, log).getOrElse { e ->
            return Result.failure(Exception("DFU ZIP parse failed: ${e.message}"))
        }

        return try {
            connectDfuPeripheral(address).getOrElse { e ->
                return Result.failure(Exception("DFU connect failed: ${e.message}"))
            }

            val packetMax = connectedPeripheral
                ?.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                ?.toInt()
                ?: 20
            val packetSize = packetMax.coerceIn(20, NORDIC_DFU_MAX_PACKET_SIZE)
            log.d("Nordic Secure DFU transfer settings: packetSize=$packetSize")

            sendDfuInitPacket(dfuPackage.datData, packetSize).getOrElse { e ->
                return Result.failure(Exception("DFU init packet failed: ${e.message}"))
            }
            sendDfuFirmware(dfuPackage.binData, packetSize).getOrElse { e ->
                return Result.failure(Exception("DFU firmware upload failed: ${e.message}"))
            }

            log.d("Nordic Secure DFU waiting for final reboot/disconnect")
            waitingForFinalDfuDisconnect = true
            waitForDfuDisconnect(15_000L)
            log.d("Nordic Secure DFU completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            log.e("Nordic Secure DFU failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            cleanupDfuSession("loadDfu finished")
        }
    }

    override fun clearPendingBuffer() {
        pendingStringResponses.clear()
        responseBuffer.clear()
        log.d("clearPendingBuffer: сброшен буфер ожидающих ответов")
    }

    override suspend fun sendRawBytes(command: ByteArray): Result<ByteArray> {
        val rx = rxCharacteristic ?: return Result.failure(Exception("Не подключено"))
        val p = connectedPeripheral ?: return Result.failure(Exception("Не подключено"))
        val data = command.toNSData()
        responseBuffer.clear()
        val result = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Result<ByteArray>> { cont ->
                var completed = false
                fun complete(value: Result<ByteArray>) {
                    if (completed) return
                    completed = true
                    rawBytesCallback = null
                    writeCallback = null
                    cont.resume(value)
                }

                rawBytesCallback = { complete(it) }
                writeCallback = { writeResult ->
                    if (writeResult.isFailure) {
                        complete(Result.failure(writeResult.exceptionOrNull() ?: Exception("Write failed")))
                    }
                }
                try {
                    p.writeValue(data, forCharacteristic = rx, type = CBCharacteristicWriteWithResponse)
                } catch (e: Throwable) {
                    complete(Result.failure(Exception(e.message ?: "Write failed")))
                }
            }
        }
        if (result == null) {
            rawBytesCallback = null
            writeCallback = null
            log.e("sendRawBytes TIMEOUT: no binary response in 10s")
            return Result.failure(Exception("Timeout: no binary response"))
        }
        return result
    }

    // ── Nordic Secure DFU ────────────────────────────────────────────────────────

    private suspend fun connectDfuPeripheral(address: String): Result<Unit> {
        val cbPeripheral = peripheralCache[address]
            ?: return Result.failure(Exception("DFU peripheral $address не найден в кэше — сначала выполните scan"))

        stopScan()
        dfuMode = true
        rxCharacteristic = null
        txCharacteristic = null
        dfuControlPointCharacteristic = null
        dfuPacketCharacteristic = null
        connectedPeripheral = cbPeripheral
        cbPeripheral.delegate = peripheralDelegate
        _connectionState.value = ConnectionState.CONNECTING

        log.d("Nordic Secure DFU connecting: uuid=${cbPeripheral.identifier.UUIDString}")
        return withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                dfuConnectCallback = { cont.resume(it) }
                centralManager.connectPeripheral(cbPeripheral, options = null)
            }
        } ?: run {
            dfuConnectCallback = null
            Result.failure(Exception("Timeout connecting to Nordic DFU peripheral"))
        }
    }

    private suspend fun sendDfuInitPacket(initPacket: ByteArray, packetSize: Int): Result<Unit> {
        if (initPacket.isEmpty()) return Result.failure(Exception("Init packet .dat is empty"))
        log.d("DFU init packet started: size=${initPacket.size}")

        setDfuPacketReceiptNotifications(0).getOrElse { return Result.failure(it) }

        val commandInfo = selectDfuObject(NORDIC_DFU_OBJECT_COMMAND).getOrElse {
            return Result.failure(it)
        }
        log.d("DFU command object info: maxSize=${commandInfo.maxSize}, offset=${commandInfo.offset}, crc=${commandInfo.crc.hex8()}")
        if (initPacket.size > commandInfo.maxSize) {
            return Result.failure(Exception("Init packet too large: ${initPacket.size}, max=${commandInfo.maxSize}"))
        }

        repeat(NORDIC_DFU_MAX_ATTEMPTS) { attempt ->
            createDfuObject(NORDIC_DFU_OBJECT_COMMAND, initPacket.size).getOrElse {
                return Result.failure(it)
            }
            val crc = writeDfuPacketData(
                source = initPacket,
                offset = 0,
                size = initPacket.size,
                packetSize = packetSize
            ).getOrElse {
                return Result.failure(it)
            }
            val checksum = calculateDfuChecksum().getOrElse {
                return Result.failure(it)
            }
            log.d("DFU init checksum: offset=${checksum.offset}, remoteCrc=${checksum.crc.hex8()}, localCrc=${crc.hex8()}")
            if (checksum.offset == initPacket.size && checksum.crc == crc) {
                executeDfuObject().getOrElse { return Result.failure(it) }
                log.d("DFU init packet executed")
                return Result.success(Unit)
            }
            log.w("DFU init CRC mismatch, retry ${attempt + 1}/$NORDIC_DFU_MAX_ATTEMPTS")
        }
        return Result.failure(Exception("Init packet CRC mismatch"))
    }

    private suspend fun sendDfuFirmware(firmware: ByteArray, packetSize: Int): Result<Unit> {
        if (firmware.isEmpty()) return Result.failure(Exception("Firmware .bin is empty"))
        log.d("DFU firmware upload started: size=${firmware.size}")

        setDfuPacketReceiptNotifications(0).getOrElse { return Result.failure(it) }
        val dataInfo = selectDfuObject(NORDIC_DFU_OBJECT_DATA).getOrElse {
            return Result.failure(it)
        }
        val objectSize = dataInfo.maxSize.takeIf { it > 0 } ?: NORDIC_DFU_DEFAULT_OBJECT_SIZE
        val chunkCount = (firmware.size + objectSize - 1) / objectSize
        log.d("DFU data object info: maxSize=${dataInfo.maxSize}, offset=${dataInfo.offset}, crc=${dataInfo.crc.hex8()}, chunks=$chunkCount")

        var offset = 0
        var chunkIndex = 0
        while (offset < firmware.size) {
            val currentObjectSize = minOf(objectSize, firmware.size - offset)
            var sent = false

            repeat(NORDIC_DFU_MAX_ATTEMPTS) { attempt ->
                if (sent) return@repeat
                log.d("DFU data object create: ${chunkIndex + 1}/$chunkCount offset=$offset size=$currentObjectSize")
                createDfuObject(NORDIC_DFU_OBJECT_DATA, currentObjectSize).getOrElse {
                    return Result.failure(it)
                }
                writeDfuPacketData(
                    source = firmware,
                    offset = offset,
                    size = currentObjectSize,
                    packetSize = packetSize
                ).getOrElse {
                    return Result.failure(it)
                }
                val checksum = calculateDfuChecksum().getOrElse {
                    return Result.failure(it)
                }
                val expectedOffset = offset + currentObjectSize
                val actualRemoteOffset = checksum.offset
                if (actualRemoteOffset < 0 || actualRemoteOffset > firmware.size) {
                    return Result.failure(Exception("Invalid DFU remote offset: $actualRemoteOffset, firmwareSize=${firmware.size}"))
                }
                // Secure DFU reports CRC for the accepted firmware stream, not for the current object only.
                val localCrc = IosDfuCrc32.calculate(firmware, 0, actualRemoteOffset)
                log.d(
                    "DFU data checksum: " +
                        "objectStartOffset=$offset, " +
                        "objectSize=$currentObjectSize, " +
                        "expectedRemoteOffset=$expectedOffset, " +
                        "actualRemoteOffset=$actualRemoteOffset, " +
                        "localCrcRange=0..$actualRemoteOffset, " +
                        "localCrc=${localCrc.hex8()}, " +
                        "remoteCrc=${checksum.crc.hex8()}"
                )
                if (actualRemoteOffset == expectedOffset && checksum.crc == localCrc) {
                    executeDfuObject(allowFinalDisconnect = expectedOffset == firmware.size).getOrElse {
                        return Result.failure(it)
                    }
                    val progress = (expectedOffset * 100 / firmware.size).coerceIn(0, 100)
                    log.d("DFU data object executed: ${chunkIndex + 1}/$chunkCount progress=$progress")
                    sent = true
                } else if (actualRemoteOffset == expectedOffset) {
                    return Result.failure(
                        Exception(
                            "DFU data CRC mismatch at offset=$offset: " +
                                "remoteCrc=${checksum.crc.hex8()}, localCrc=${localCrc.hex8()}"
                        )
                    )
                } else {
                    log.w(
                        "DFU data offset mismatch at objectStartOffset=$offset: " +
                            "expectedRemoteOffset=$expectedOffset, actualRemoteOffset=$actualRemoteOffset, " +
                            "retry ${attempt + 1}/$NORDIC_DFU_MAX_ATTEMPTS"
                    )
                }
            }

            if (!sent) return Result.failure(Exception("Data object CRC mismatch at offset=$offset"))
            offset += currentObjectSize
            chunkIndex++
        }
        return Result.success(Unit)
    }

    private suspend fun setDfuPacketReceiptNotifications(value: Int): Result<Unit> {
        val response = writeDfuControl(
            command = byteArrayOf(
                NORDIC_DFU_OP_PRN.toByte(),
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte()
            ),
            expectedRequest = NORDIC_DFU_OP_PRN
        ).getOrElse { return Result.failure(it) }
        return validateDfuResponse(response, NORDIC_DFU_OP_PRN, "Set PRN").map { Unit }
    }

    private suspend fun selectDfuObject(type: Int): Result<DfuObjectInfo> {
        val response = writeDfuControl(
            command = byteArrayOf(NORDIC_DFU_OP_SELECT.toByte(), type.toByte()),
            expectedRequest = NORDIC_DFU_OP_SELECT
        ).getOrElse { return Result.failure(it) }
        validateDfuResponse(response, NORDIC_DFU_OP_SELECT, "Select object").getOrElse {
            return Result.failure(it)
        }
        if (response.size < 15) {
            return Result.failure(Exception("Invalid Select Object response length=${response.size}"))
        }
        return Result.success(
            DfuObjectInfo(
                maxSize = response.readLe32(3),
                offset = response.readLe32(7),
                crc = response.readLe32(11)
            )
        )
    }

    private suspend fun createDfuObject(type: Int, size: Int): Result<Unit> {
        val command = ByteArray(6)
        command[0] = NORDIC_DFU_OP_CREATE.toByte()
        command[1] = type.toByte()
        command.writeLe32(2, size)
        val response = writeDfuControl(command, NORDIC_DFU_OP_CREATE).getOrElse {
            return Result.failure(it)
        }
        return validateDfuResponse(response, NORDIC_DFU_OP_CREATE, "Create object").map { Unit }
    }

    private suspend fun calculateDfuChecksum(): Result<DfuObjectChecksum> {
        val response = writeDfuControl(
            command = byteArrayOf(NORDIC_DFU_OP_CALCULATE_CHECKSUM.toByte()),
            expectedRequest = NORDIC_DFU_OP_CALCULATE_CHECKSUM
        ).getOrElse { return Result.failure(it) }
        validateDfuResponse(response, NORDIC_DFU_OP_CALCULATE_CHECKSUM, "Calculate checksum").getOrElse {
            return Result.failure(it)
        }
        if (response.size < 11) {
            return Result.failure(Exception("Invalid checksum response length=${response.size}"))
        }
        return Result.success(
            DfuObjectChecksum(
                offset = response.readLe32(3),
                crc = response.readLe32(7)
            )
        )
    }

    private suspend fun executeDfuObject(allowFinalDisconnect: Boolean = false): Result<Unit> {
        val result = writeDfuControl(
            command = byteArrayOf(NORDIC_DFU_OP_EXECUTE.toByte()),
            expectedRequest = NORDIC_DFU_OP_EXECUTE,
            timeoutMs = if (allowFinalDisconnect) 20_000L else 10_000L,
            allowDisconnect = allowFinalDisconnect
        )
        val response = result.getOrElse { error ->
            return if (allowFinalDisconnect && !isConnected()) {
                log.d("DFU execute completed by disconnect")
                Result.success(Unit)
            } else {
                Result.failure(error)
            }
        }
        return validateDfuResponse(response, NORDIC_DFU_OP_EXECUTE, "Execute object").map { Unit }
    }

    private suspend fun writeDfuControl(
        command: ByteArray,
        expectedRequest: Int,
        timeoutMs: Long = 10_000L,
        allowDisconnect: Boolean = false
    ): Result<ByteArray> {
        val cp = dfuControlPointCharacteristic
            ?: return Result.failure(Exception("DFU Control Point characteristic is not ready"))
        val p = connectedPeripheral
            ?: return Result.failure(Exception("DFU peripheral is not connected"))
        log.d("DFU control → ${command.toHexString()}")

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<ByteArray>> { cont ->
                var completed = false
                fun complete(value: Result<ByteArray>) {
                    if (completed) return
                    completed = true
                    dfuNotificationCallback = null
                    dfuWriteCallback = null
                    cont.resume(value)
                }

                dfuNotificationCallback = callback@{ notification ->
                    val bytes = notification.getOrElse { e ->
                        complete(Result.failure(e))
                        return@callback
                    }
                    if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == NORDIC_DFU_OP_RESPONSE && bytes[1].toInt() and 0xFF == expectedRequest) {
                        complete(Result.success(bytes))
                    } else {
                        complete(Result.failure(Exception("Unexpected DFU notification: ${bytes.toHexString()}")))
                    }
                }
                dfuWriteCallback = { writeResult ->
                    if (writeResult.isFailure) {
                        complete(Result.failure(writeResult.exceptionOrNull() ?: Exception("DFU control write failed")))
                    }
                }
                try {
                    p.writeValue(command.toNSData(), forCharacteristic = cp, type = CBCharacteristicWriteWithResponse)
                } catch (e: Throwable) {
                    complete(Result.failure(Exception(e.message ?: "DFU control write failed")))
                }
            }
        }

        if (result == null) {
            dfuNotificationCallback = null
            dfuWriteCallback = null
            if (allowDisconnect && !isConnected()) {
                return Result.success(byteArrayOf(NORDIC_DFU_OP_RESPONSE.toByte(), expectedRequest.toByte(), NORDIC_DFU_STATUS_SUCCESS.toByte()))
            }
            return Result.failure(Exception("Timeout waiting DFU response for op=${expectedRequest.hex2()}"))
        }
        result.onSuccess { log.d("DFU control ← ${it.toHexString()}") }
        return result
    }

    private suspend fun writeDfuPacketData(
        source: ByteArray,
        offset: Int,
        size: Int,
        packetSize: Int
    ): Result<Int> {
        val packet = dfuPacketCharacteristic
            ?: return Result.failure(Exception("DFU Packet characteristic is not ready"))
        val p = connectedPeripheral
            ?: return Result.failure(Exception("DFU peripheral is not connected"))

        var sent = 0
        while (sent < size) {
            if (!isConnected()) return Result.failure(Exception("DFU disconnected while writing packet data"))
            val len = minOf(packetSize, size - sent)
            val chunk = source.copyOfRange(offset + sent, offset + sent + len)
            p.writeValue(chunk.toNSData(), forCharacteristic = packet, type = CBCharacteristicWriteWithoutResponse)
            sent += len
            delay(NORDIC_DFU_PACKET_DELAY_MS)
        }
        delay(50L)
        return Result.success(IosDfuCrc32.calculate(source, offset, size))
    }

    private fun validateDfuResponse(response: ByteArray, expectedRequest: Int, action: String): Result<Unit> {
        if (response.size < 3 || response[0].toInt() and 0xFF != NORDIC_DFU_OP_RESPONSE || response[1].toInt() and 0xFF != expectedRequest) {
            return Result.failure(Exception("$action invalid response: ${response.toHexString()}"))
        }
        val status = response[2].toInt() and 0xFF
        if (status == NORDIC_DFU_STATUS_SUCCESS) return Result.success(Unit)

        val extended = if (status == NORDIC_DFU_STATUS_EXTENDED_ERROR && response.size > 3) {
            ", extended=${(response[3].toInt() and 0xFF).hex2()}"
        } else {
            ""
        }
        return Result.failure(Exception("$action failed: status=${status.hex2()}${extended}"))
    }

    private suspend fun waitForDfuDisconnect(timeoutMs: Long): Boolean {
        val delayMs = 100L
        val attempts = maxOf(1, (timeoutMs / delayMs).toInt())
        repeat(attempts) {
            if (!isConnected()) {
                log.d("waitForDfuDisconnect: peripheral disconnected")
                return true
            }
            delay(delayMs)
        }
        val stillConnected = isConnected()
        log.d("waitForDfuDisconnect: timeout reached, stillConnected=$stillConnected")
        return !stillConnected
    }

    private fun cleanupDfuSession(reason: String) {
        log.d("cleanupDfuSession: reason=$reason")
        dfuControlPointCharacteristic = null
        dfuPacketCharacteristic = null
        dfuNotificationCallback = null
        dfuWriteCallback = null
        dfuConnectCallback = null
        dfuMode = false
        waitingForFinalDfuDisconnect = false
        log.d("DFU state reset completed")
    }

    private fun resetConnectionState(reason: String) {
        log.d("resetConnectionState: reason=$reason")
        rxCharacteristic = null
        txCharacteristic = null
        commandCallback = null
        rawBytesCallback = null
        writeCallback = null
        pendingStringResponses.clear()
        responseBuffer.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        connectedPeripheral = null
        log.d("Connection state reset completed")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private suspend fun writeBootPacketWithRetry(
        packet: ByteArray,
        expectedCommand: Int,
        expectedAddress: Int,
        expectedNum: Int
    ): Result<Unit> {
        repeat(BOOT_WRITE_MAX_BUSY_RETRIES + 1) { attempt ->
            val responseBytes = sendRawBytes(packet).getOrElse { e ->
                return Result.failure(Exception("No write response: ${e.message}"))
            }
            val response = try {
                parseBootWriteResponse(responseBytes)
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

    private fun advertisementSummary(advertisementData: Map<Any?, *>): String {
        if (advertisementData.isEmpty()) return "{}"
        return advertisementData.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${key ?: "null"}=$value"
        }
    }

    private fun isSupportedAdvertisedName(name: String): Boolean =
        name.startsWith("SatelliteOnline", ignoreCase = true) ||
            name.startsWith("Dfu", ignoreCase = true)

    private fun uuidMatches(actual: String, expected: String): Boolean {
        val a = actual.uppercase()
        val e = expected.uppercase()
        if (a == e) return true
        return e.startsWith("0000") &&
            e.endsWith("-0000-1000-8000-00805F9B34FB") &&
            a == e.substring(4, 8)
    }

    private fun Int.hex2(): String =
        "0x" + (this and 0xFF).toString(16).uppercase().padStart(2, '0')

    private fun Int.hex8(): String =
        "0x" + (toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    private fun ByteArray.writeLe32(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.readLe32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

    /**
     * ByteArray → NSData через NSData.create(bytes:length:).
     */
    @OptIn(BetaInteropApi::class)
    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

    /**
     * NSData → Kotlin String через чтение raw байтов и UTF-8 декодирование.
     */
    private fun NSData.toKotlinString(): String? {
        val len = length.toInt()
        if (len == 0) return null
        val ptr = bytes?.reinterpret<ByteVar>() ?: return null
        return ptr.readBytes(len).decodeToString()
    }

    /**
     * NSData → Kotlin ByteArray через чтение raw байтов.
     */
    private fun NSData.toKotlinByteArray(): ByteArray? {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val ptr = bytes?.reinterpret<ByteVar>() ?: return null
        return ptr.readBytes(len)
    }

    private data class DfuObjectInfo(
        val maxSize: Int,
        val offset: Int,
        val crc: Int
    )

    private data class DfuObjectChecksum(
        val offset: Int,
        val crc: Int
    )

    private companion object {
        const val BOOT_WRITE_MAX_BUSY_RETRIES = 20
        const val NORDIC_DFU_SERVICE_UUID = "0000FE59-0000-1000-8000-00805F9B34FB"
        const val NORDIC_DFU_CONTROL_POINT_UUID = "8EC90001-F315-4F60-9FB8-838830DAEA50"
        const val NORDIC_DFU_PACKET_UUID = "8EC90002-F315-4F60-9FB8-838830DAEA50"

        const val NORDIC_DFU_OBJECT_COMMAND = 0x01
        const val NORDIC_DFU_OBJECT_DATA = 0x02

        const val NORDIC_DFU_OP_CREATE = 0x01
        const val NORDIC_DFU_OP_PRN = 0x02
        const val NORDIC_DFU_OP_CALCULATE_CHECKSUM = 0x03
        const val NORDIC_DFU_OP_EXECUTE = 0x04
        const val NORDIC_DFU_OP_SELECT = 0x06
        const val NORDIC_DFU_OP_RESPONSE = 0x60

        const val NORDIC_DFU_STATUS_SUCCESS = 0x01
        const val NORDIC_DFU_STATUS_EXTENDED_ERROR = 0x0B

        const val NORDIC_DFU_MAX_ATTEMPTS = 3
        const val NORDIC_DFU_DEFAULT_OBJECT_SIZE = 4096
        const val NORDIC_DFU_MAX_PACKET_SIZE = 244
        const val NORDIC_DFU_PACKET_DELAY_MS = 8L
    }
}
