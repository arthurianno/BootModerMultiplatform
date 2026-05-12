package org.bootmoder.kmp.shared.data.repository

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.bootmoder.kmp.shared.util.Logger
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate as zlibInflate
import platform.zlib.inflateEnd as zlibInflateEnd
import platform.zlib.inflateInit2 as zlibInflateInit2
import platform.zlib.z_stream_s

internal data class IosNordicDfuPackage(
    val binName: String,
    val binData: ByteArray,
    val datName: String,
    val datData: ByteArray
)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosNordicDfuZipParser {

    fun parse(filePath: String, log: Logger): Result<IosNordicDfuPackage> = runCatching {
        val nsData = NSData.dataWithContentsOfFile(filePath)
            ?: throw IllegalStateException("Не удалось прочитать DFU ZIP: $filePath")
        val zipBytes = nsData.toKotlinBytes()
        val entries = parseCentralDirectory(zipBytes, log).ifEmpty {
            parseLocalHeaders(zipBytes, log)
        }
        if (entries.isEmpty()) {
            throw IllegalStateException("DFU ZIP не содержит читаемых файлов")
        }

        val binEntry = chooseEntry(entries, "application.bin", ".bin")
            ?: throw IllegalStateException("Файл application.bin/.bin не найден в DFU ZIP")
        val datEntry = chooseEntry(entries, "application.dat", ".dat")
            ?: throw IllegalStateException("Файл application.dat/.dat не найден в DFU ZIP")

        log.d("Nordic DFU zip parsed: bin name=${binEntry.key}, bin size=${binEntry.value.size}, dat name=${datEntry.key}, dat size=${datEntry.value.size}")
        IosNordicDfuPackage(
            binName = binEntry.key,
            binData = binEntry.value,
            datName = datEntry.key,
            datData = datEntry.value
        )
    }

    private fun chooseEntry(entries: Map<String, ByteArray>, preferredName: String, suffix: String): Map.Entry<String, ByteArray>? =
        entries.entries.firstOrNull { it.key.equals(preferredName, ignoreCase = true) }
            ?: entries.entries.firstOrNull { it.key.endsWith("/$preferredName", ignoreCase = true) }
            ?: entries.entries.firstOrNull { it.key.endsWith(suffix, ignoreCase = true) }

    private fun parseCentralDirectory(bytes: ByteArray, log: Logger): Map<String, ByteArray> {
        val eocd = findEndOfCentralDirectory(bytes) ?: return emptyMap()
        val entryCount = readU16(bytes, eocd + 10)
        var pos = readU32(bytes, eocd + 16)
        val result = linkedMapOf<String, ByteArray>()

        repeat(entryCount) {
            if (pos < 0 || pos + 46 > bytes.size || readU32(bytes, pos) != CENTRAL_DIRECTORY_SIGNATURE) {
                return@repeat
            }

            val method = readU16(bytes, pos + 10)
            val compressedSize = readU32(bytes, pos + 20)
            val uncompressedSize = readU32(bytes, pos + 24)
            val nameLen = readU16(bytes, pos + 28)
            val extraLen = readU16(bytes, pos + 30)
            val commentLen = readU16(bytes, pos + 32)
            val localHeaderOffset = readU32(bytes, pos + 42)

            val nameStart = pos + 46
            val nameEnd = nameStart + nameLen
            if (nameEnd > bytes.size) return@repeat
            val name = bytes.decodeToString(nameStart, nameEnd)

            val localNameLen = readU16(bytes, localHeaderOffset + 26)
            val localExtraLen = readU16(bytes, localHeaderOffset + 28)
            val dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen
            val dataEnd = dataStart + compressedSize
            if (dataStart < 0 || dataEnd > bytes.size) return@repeat

            if (name.endsWith(".bin", ignoreCase = true) || name.endsWith(".dat", ignoreCase = true)) {
                val compressed = bytes.copyOfRange(dataStart, dataEnd)
                val data = inflateEntry(method, compressed, uncompressedSize, log, name)
                if (data != null) result[name] = data
            }

            pos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun parseLocalHeaders(bytes: ByteArray, log: Logger): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var pos = 0
        while (pos <= bytes.size - 30) {
            if (readU32OrNull(bytes, pos) != LOCAL_FILE_HEADER_SIGNATURE) {
                pos++
                continue
            }

            val method = readU16(bytes, pos + 8)
            val compressedSize = readU32(bytes, pos + 18)
            val uncompressedSize = readU32(bytes, pos + 22)
            val nameLen = readU16(bytes, pos + 26)
            val extraLen = readU16(bytes, pos + 28)
            val nameStart = pos + 30
            val nameEnd = nameStart + nameLen
            val dataStart = nameEnd + extraLen
            val dataEnd = dataStart + compressedSize
            if (nameEnd > bytes.size || dataStart < 0 || dataEnd > bytes.size || compressedSize <= 0) {
                pos++
                continue
            }

            val name = bytes.decodeToString(nameStart, nameEnd)
            if (name.endsWith(".bin", ignoreCase = true) || name.endsWith(".dat", ignoreCase = true)) {
                val compressed = bytes.copyOfRange(dataStart, dataEnd)
                val data = inflateEntry(method, compressed, uncompressedSize, log, name)
                if (data != null) result[name] = data
            }
            pos = dataEnd
        }
        return result
    }

    private fun inflateEntry(
        method: Int,
        compressed: ByteArray,
        uncompressedSize: Int,
        log: Logger,
        name: String
    ): ByteArray? = when (method) {
        0 -> compressed
        8 -> inflateRaw(compressed, uncompressedSize)
        else -> {
            log.w("Unsupported ZIP compression method $method for '$name'")
            null
        }
    }

    private fun inflateRaw(src: ByteArray, expectedSize: Int): ByteArray? {
        if (src.isEmpty()) return ByteArray(0)
        val outBuf = ByteArray(maxOf(expectedSize, src.size * 4, 1024))

        val written: Int = memScoped {
            val zs: z_stream_s = alloc()
            zs.zalloc = null
            zs.zfree = null
            zs.opaque = null
            val zsPtr: CPointer<z_stream_s> = zs.ptr
            if (zlibInflateInit2(zsPtr, -15) != Z_OK) return@memScoped -1

            src.usePinned { sp ->
                outBuf.usePinned { op ->
                    zs.next_in = sp.addressOf(0).reinterpret()
                    zs.avail_in = src.size.toUInt()
                    zs.next_out = op.addressOf(0).reinterpret()
                    zs.avail_out = outBuf.size.toUInt()
                    val ret = zlibInflate(zsPtr, Z_FINISH)
                    val count = outBuf.size - zs.avail_out.toInt()
                    zlibInflateEnd(zsPtr)
                    if (ret == Z_STREAM_END || ret == Z_OK) count else -1
                }
            }
        }

        return if (written >= 0) outBuf.copyOf(written) else null
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        var pos = bytes.size - 22
        while (pos >= 0) {
            if (readU32OrNull(bytes, pos) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) return pos
            pos--
        }
        return null
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        readU32OrNull(bytes, offset) ?: -1

    private fun readU32OrNull(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun NSData.toKotlinBytes(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val ptr = bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
        return ptr.readBytes(len)
    }

    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50
}

internal object IosDfuCrc32 {
    private val table = IntArray(256) { index ->
        var crc = index
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                0xEDB88320.toInt() xor (crc ushr 1)
            } else {
                crc ushr 1
            }
        }
        crc
    }

    fun calculate(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor -1
    }
}
