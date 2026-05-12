package org.bootmoder.kmp.shared.util

private const val BOOT_MODE_MIN_ADDRESS = 0x00000
private const val BOOT_MODE_MAX_ADDRESS = 0x1FFFF
private const val BOOT_MODE_HEADER_SIZE = 7
private const val MIN_DATA_WRITE_SIZE = 4
private const val MAX_DATA_WRITE_SIZE = 128

data class BootWriteResponse(
    val flag: Int,
    val command: Int,
    val address: Int,
    val num: Int
)

/**
 * Построитель бинарных пакетов BootModer-протокола.
 *
 * Реализован без java.nio.ByteBuffer — полностью KMP-совместим.
 *
 * Формат пакета (7 байт заголовок + data):
 * [0]      startByte  — маркер начала пакета (0x24)
 * [1]      command    — код команды (FIRMWARE_CHUNK_CMD / CONFIGURATION_CMD)
 * [2..5]   address    — 4 байта, Little-Endian
 * [6]      size       — размер data в байтах
 * [7..]    data       — полезная нагрузка
 */
fun buildCommandPacket(
    startByte: Byte,
    command: Byte,
    address: Int,
    data: ByteArray
): ByteArray {
    val packet = ByteArray(data.size + 7)
    packet[0] = startByte
    packet[1] = command

    // Little-Endian без java.nio.ByteBuffer
    packet[2] = (address and 0xFF).toByte()
    packet[3] = ((address shr 8) and 0xFF).toByte()
    packet[4] = ((address shr 16) and 0xFF).toByte()
    packet[5] = ((address shr 24) and 0xFF).toByte()

    packet[6] = data.size.toByte()
    data.copyInto(packet, destinationOffset = 7)
    return packet
}

fun buildDataWritePacket(address: Int, chunk: ByteArray): ByteArray {
    validateDataWrite(address, chunk)
    return buildCommandPacket(
        startByte = BluetoothConstants.BOOT_MODE_START,
        command = BluetoothConstants.FIRMWARE_CHUNK_CMD,
        address = address,
        data = chunk
    )
}

fun buildConfigWritePacket(config: ByteArray): ByteArray {
    require(config.size == BluetoothConstants.CONFIGURATION_SIZE) {
        "Config size must be ${BluetoothConstants.CONFIGURATION_SIZE} bytes, actual=${config.size}"
    }
    return buildCommandPacket(
        startByte = BluetoothConstants.BOOT_MODE_START,
        command = BluetoothConstants.CONFIGURATION_CMD,
        address = 0,
        data = config
    )
}

fun parseBootWriteResponse(bytes: ByteArray): BootWriteResponse {
    require(bytes.size >= BOOT_MODE_HEADER_SIZE) {
        "Response too short: ${bytes.size} bytes, expected at least $BOOT_MODE_HEADER_SIZE"
    }
    return BootWriteResponse(
        flag = bytes[0].toInt() and 0xFF,
        command = bytes[1].toInt() and 0xFF,
        address = readUInt32Le(bytes, 2),
        num = bytes[6].toInt() and 0xFF
    )
}

fun validateDataWrite(address: Int, chunk: ByteArray) {
    require(address % 4 == 0) { "Address must be 4-byte aligned: $address" }
    require(address in BOOT_MODE_MIN_ADDRESS..BOOT_MODE_MAX_ADDRESS) {
        "Address out of range: $address"
    }
    require(chunk.size in MIN_DATA_WRITE_SIZE..MAX_DATA_WRITE_SIZE) {
        "Chunk size must be in $MIN_DATA_WRITE_SIZE..$MAX_DATA_WRITE_SIZE bytes, actual=${chunk.size}"
    }
    require(chunk.size % 4 == 0) { "Chunk size must be 4-byte aligned: ${chunk.size}" }
    require(address + chunk.size - 1 <= BOOT_MODE_MAX_ADDRESS) {
        "Write exceeds max address 0x1FFFF: address=$address size=${chunk.size}"
    }
}

fun validateBootWriteResponse(
    response: BootWriteResponse,
    expectedCommand: Int,
    expectedAddress: Int,
    expectedNum: Int
) {
    require(response.command == expectedCommand) {
        "Unexpected response cmd: ${response.command}, expected=$expectedCommand"
    }
    require(response.address == expectedAddress) {
        "Unexpected response address: ${response.address}, expected=$expectedAddress"
    }
    require(response.num == expectedNum) {
        "Unexpected response num: ${response.num}, expected=$expectedNum"
    }
}

fun readUInt32Le(bytes: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset + 4 <= bytes.size) {
        "Cannot read UInt32 LE at offset=$offset from ${bytes.size} bytes"
    }
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
