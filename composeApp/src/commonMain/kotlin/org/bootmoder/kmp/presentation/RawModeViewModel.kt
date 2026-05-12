package org.bootmoder.kmp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bootmoder.kmp.shared.domain.entity.BleDevice
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository

// ── Data model ─────────────────────────────────────────────────────────────────

data class ConfigData(
    val name: String,
    val address: String,
    val value: String,
    val type: String,
    val isEditable: Boolean = true,
    var newValue: String? = null
) {
    val isModified: Boolean get() = newValue != null && newValue != value
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

class RawModeViewModel(
    private val bleRepository: BleRepository,
    private val deviceWorkingRepository: DeviceWorkingRepository,
    private val appState: AppState
) : ViewModel() {

    private val _readResponseFlow = MutableSharedFlow<List<ConfigData>>()
    val readResponseFlow: SharedFlow<List<ConfigData>> = _readResponseFlow

    private val _deviceInfo = MutableStateFlow<BleDevice?>(null)
    val deviceInfo: StateFlow<BleDevice?> = _deviceInfo.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRawModeActive = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            appState.connectedDevice.collect { device ->
                _deviceInfo.value = device
            }
        }
    }

    fun tryingToSendCommands(forModify: Boolean, modifiedData: List<ConfigData>? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (!bleRepository.isConnected()) {
                    val connected = connectToDevice()
                    if (!connected) return@launch
                }
                if (forModify) {
                    val data = modifiedData ?: run {
                        _errorState.value = "No modified data provided"
                        return@launch
                    }
                    sendModifiedData(data)
                } else {
                    sendReadCommands()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorState.value = null
    }

    /** Разрыв соединения и сброс данных для возврата к сканированию. */
    fun disconnectAndReset() {
        viewModelScope.launch {
            bleRepository.disconnect()
            appState.clearAll()
            _isRawModeActive.value = false
        }
    }

    private suspend fun connectToDevice(): Boolean {
        val device = _deviceInfo.value ?: run {
            _errorState.value = "No device selected"
            return false
        }
        return try {
            val ok = bleRepository.connect(device).isSuccess
            if (!ok) {
                _errorState.value = "Connection failed"
                return false
            }
            val password = deviceWorkingRepository.getPassword(device.address)
            if (password.isNullOrBlank()) {
                _errorState.value = "No password found for device"
                return false
            }
            val response = bleRepository.sendCommand("pin.$password").getOrDefault("")
            if (response.contains("pin.ok")) {
                _isRawModeActive.value = false
                true
            } else {
                _errorState.value = "Invalid PIN response: $response"
                false
            }
        } catch (e: Exception) {
            _errorState.value = "Connection error: ${e.message}"
            false
        }
    }

    // ── Команды чтения конфигурации (бинарный протокол) ──────────────────────

    private suspend fun sendReadCommands() {
        try {
            // Переходим в raw-режим
            if (!_isRawModeActive.value) {
                val setRawResp = bleRepository.sendCommand("setraw").getOrDefault("")
                if (setRawResp.contains("setraw.ok")) {
                    _isRawModeActive.value = true
                } else {
                    _errorState.value = "Failed to set raw mode: $setRawResp"
                    return
                }
            }

            // Бинарные команды чтения: [0x21, 0x81, addr, count]
            val commands = listOf(
                byteArrayOf(0x21, 0x81.toByte(), 0x00, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x10, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x20, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x30, 0x08),
                byteArrayOf(0x21, 0x81.toByte(), 0x38, 0x04),
                byteArrayOf(0x21, 0x81.toByte(), 0x3C, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x4C, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x5C, 0x10),
                byteArrayOf(0x21, 0x81.toByte(), 0x6C, 0x04),
                byteArrayOf(0x21, 0x81.toByte(), 0x70, 0x0C)
            )

            val allConfigData = mutableListOf<ConfigData>()
            for (command in commands) {
                val result = bleRepository.sendRawBytes(command)
                result.fold(
                    onSuccess = { response ->
                        val parsed = parseResponse(response)
                        allConfigData.addAll(parsed)
                        _readResponseFlow.emit(allConfigData.toList())
                    },
                    onFailure = { e ->
                        _errorState.value = "Read error: ${e.message}"
                        return
                    }
                )
            }
        } catch (e: Exception) {
            _errorState.value = "Error: ${e.message}"
        }
    }

    // ── Запись изменённых значений ────────────────────────────────────────────

    private suspend fun sendModifiedData(modifiedData: List<ConfigData>) {
        try {
            if (!_isRawModeActive.value) {
                val setRawResp = bleRepository.sendCommand("setraw").getOrDefault("")
                if (setRawResp.contains("setraw.ok")) {
                    _isRawModeActive.value = true
                } else {
                    _errorState.value = "Failed to set raw mode"
                    return
                }
            }

            val modified = modifiedData.filter { it.isModified }
            for (item in modified) {
                val writeCmd = item.toWriteCommand() ?: continue
                val result = bleRepository.sendRawBytes(writeCmd)
                if (result.isFailure) {
                    _errorState.value = "Write error for ${item.name}: ${result.exceptionOrNull()?.message}"
                    return
                }
            }

            // Команда применения изменений: [0x21, 0xEE]
            val applyCmd = byteArrayOf(0x21, 0xEE.toByte())
            bleRepository.sendRawBytes(applyCmd)
        } catch (e: Exception) {
            _errorState.value = "Error: ${e.message}"
        }
    }

    // ── Вспомогательные функции ───────────────────────────────────────────────

    private fun ConfigData.toWriteCommand(): ByteArray? {
        val newVal = newValue ?: return null
        val addr = address.removePrefix("0x").toIntOrNull(16) ?: return null
        val bytes = when (type) {
            "FLOAT" -> {
                val f = newVal.toFloatOrNull() ?: return null
                f.toBits().toLEBytes()
            }
            "UINT32" -> {
                val u = newVal.toUIntOrNull() ?: return null
                u.toInt().toLEBytes()
            }
            "UINT32_HEX" -> {
                val u = newVal.removePrefix("0x").toUIntOrNull(16)
                    ?: newVal.toUIntOrNull()
                    ?: return null
                u.toInt().toLEBytes()
            }
            "TIMESTAMP" -> {
                val ts = parseTimestampToUnix(newVal)
                ts.toInt().toLEBytes()
            }
            "BITWISE" -> {
                val u = newVal.toUIntOrNull(2) ?: return null
                u.toInt().toLEBytes()
            }
            "INT32" -> {
                val i = newVal.toIntOrNull() ?: return null
                i.toLEBytes()
            }
            "Char[]" -> {
                val b = newVal.encodeToByteArray()
                ByteArray(16).also { arr -> b.copyInto(arr, endIndex = minOf(b.size, 16)) }
            }
            else -> return null
        }
        // [0x21, 0x82, addr, count, ...data...]
        return byteArrayOf(0x21, 0x82.toByte(), addr.toByte(), bytes.size.toByte()) + bytes
    }

    private fun Int.toLEBytes(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )

    private fun parseTimestampToUnix(dateStr: String): Long {
        // Ожидаемый формат: "yyyy-MM-dd HH:mm:ss"
        return try {
            val parts = dateStr.split(" ")
            val dateParts = parts[0].split("-")
            val timeParts = if (parts.size > 1) parts[1].split(":") else listOf("0", "0", "0")
            val year = dateParts[0].toInt()
            val month = dateParts[1].toInt()
            val day = dateParts[2].toInt()
            val hour = timeParts[0].toInt()
            val min = timeParts[1].toInt()
            val sec = if (timeParts.size > 2) timeParts[2].toInt() else 0

            // Упрощённый расчёт Unix timestamp
            var days = 0L
            for (y in 1970 until year) {
                days += if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
            }
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val md = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            for (m in 1 until month) days += md[m - 1]
            days += (day - 1)
            days * 86400L + hour * 3600L + min * 60L + sec
        } catch (e: Exception) {
            0L
        }
    }

    // ── KMP-совместимый парсинг байт (без ByteBuffer) ─────────────────────────

    fun parseResponse(response: ByteArray): List<ConfigData> {
        val result = mutableListOf<ConfigData>()
        if (response.isEmpty() || response[0] != 0x00.toByte()) return emptyList()
        if ((response[1].toInt() and 0xFF) != 0x81) return emptyList()

        val address = response[2].toInt() and 0xFF
        val num = response[3].toInt() and 0xFF
        val data = response.drop(4).take(num)

        val configMap = mapOf(
            0x00 to Pair("Калибровка тока (0 мкА)", "FLOAT"),
            0x04 to Pair("Калибровка тока (2 мкА)", "FLOAT"),
            0x08 to Pair("Калибровка тока (10 мкА)", "FLOAT"),
            0x0C to Pair("Калибровка тока (20 мкА)", "FLOAT"),
            0x10 to Pair("Калибровка тока (30 мкА)", "FLOAT"),
            0x14 to Pair("Калибровка тока (40 мкА)", "FLOAT"),
            0x18 to Pair("Калибровка тока (60 мкА)", "FLOAT"),
            0x1C to Pair("Калибровка температуры (мВ)", "FLOAT"),
            0x20 to Pair("Значение R1 (Ом)", "UINT32"),
            0x24 to Pair("Калибровка Uref (UIC1101)", "UINT32_HEX"),
            0x28 to Pair("Калибровка Uw (UIC1101)", "UINT32_HEX"),
            0x2C to Pair("Калибровка температуры (C x10)", "UINT32"),
            0x30 to Pair("Дата выпуска устройства (UNIX)", "TIMESTAMP"),
            0x34 to Pair("Калибровка напряжения питания", "FLOAT"),
            0x38 to Pair("Слово конфигурации устройства", "BITWISE"),
            0x3C to Pair("Имя выпускавшего оператора", "Char[]"),
            0x4C to Pair("Аппаратная версия устройства", "Char[]"),
            0x5C to Pair("Серийный номер устройства", "Char[]"),
            0x6C to Pair("Локальное смещение времени (UNIX)", "INT32"),
            0x70 to Pair("Зарезервированная область", "BYTE[]")
        )

        var offset = 0
        while (offset < data.size && offset < num) {
            val currentAddress = address + offset
            configMap[currentAddress]?.let { (name, type) ->
                val isEditable = currentAddress != 0x70
                val value = when (type) {
                    "FLOAT" -> if (data.size - offset >= 4) {
                        val bits = readInt32LE(data, offset)
                        Float.fromBits(bits).toString()
                    } else "Invalid data"

                    "UINT32" -> if (data.size - offset >= 4) {
                        readInt32LE(data, offset).toUInt().toString()
                    } else "Invalid data"

                    "UINT32_HEX" -> if (data.size - offset >= 4) {
                        val v = readInt32LE(data, offset).toUInt()
                        "0x${v.toString(16).uppercase().padStart(8, '0')}"
                    } else "Invalid data"

                    "TIMESTAMP" -> if (data.size - offset >= 4) {
                        val ts = readInt32LE(data, offset).toLong()
                        formatTimestamp(ts)
                    } else "Invalid data"

                    "BITWISE" -> if (data.size - offset >= 4) {
                        val v = readInt32LE(data, offset).toUInt()
                        v.toString(2).padStart(32, '0')
                    } else "Invalid data"

                    "INT32" -> if (data.size - offset >= 4) {
                        readInt32LE(data, offset).toString()
                    } else "Invalid data"

                    "Char[]" -> if (data.size - offset >= 16) {
                        val bytes = data.drop(offset).take(16).toByteArray()
                        bytes.decodeToString().trimEnd('\u0000')
                    } else "Invalid data"

                    "BYTE[]" -> data.drop(offset).take(num - offset)
                        .joinToString(" ") { b -> b.toInt().and(0xFF).toString(16).padStart(2, '0') }

                    else -> "Unsupported type"
                }
                result.add(ConfigData(
                    name = name,
                    address = "0x${currentAddress.toString(16).padStart(2, '0')}",
                    value = value,
                    type = type,
                    isEditable = isEditable
                ))
                offset += when (type) {
                    "FLOAT", "UINT32", "UINT32_HEX", "TIMESTAMP", "BITWISE", "INT32" -> 4
                    "Char[]" -> 16
                    "BYTE[]" -> num - offset
                    else -> 0
                }
            } ?: run { offset++ }
        }
        return result
    }

    // Little-endian int32 без ByteBuffer (KMP-совместимо)
    private fun readInt32LE(data: List<Byte>, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun formatTimestamp(unixSeconds: Long): String {
        var t = if (unixSeconds < 0) 0L else unixSeconds
        val sec = t % 60; t /= 60
        val min = t % 60; t /= 60
        val hour = t % 24; t /= 24
        var days = t.toInt()
        var year = 1970
        while (true) {
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            val diy = if (leap) 366 else 365
            if (days < diy) break
            days -= diy
            year++
        }
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val md = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var month = 0
        while (month < 12 && days >= md[month]) { days -= md[month]; month++ }
        return "${year.toString().padStart(4,'0')}-${(month+1).toString().padStart(2,'0')}-${(days+1).toString().padStart(2,'0')} ${hour.toString().padStart(2,'0')}:${min.toString().padStart(2,'0')}:${sec.toString().padStart(2,'0')}"
    }
}
