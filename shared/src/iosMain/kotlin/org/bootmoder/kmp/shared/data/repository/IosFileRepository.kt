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
import org.bootmoder.kmp.shared.domain.repository.FileRepository
import org.bootmoder.kmp.shared.util.BluetoothConstants
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

/**
 * iOS-реализация [FileRepository].
 * Читает ZIP-архив прошивки (файлы .bin и .dat) с помощью минимального
 * ZIP-парсера и разжимает DEFLATE через platform.zlib.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileRepository : FileRepository {

    private val log = Logger("IosFileRepository")

    override suspend fun processZipFiles(zipPath: String): Result<Pair<ByteArray, ByteArray>> {
        return try {
            log.d("processZipFiles: $zipPath")
            val nsData = NSData.dataWithContentsOfFile(zipPath)
                ?: return Result.failure(Exception("Не удалось прочитать файл: $zipPath"))

            val bytes = nsData.toKotlinBytes()
            log.d("Прочитано ${bytes.size} байт из ZIP")

            val entries = parseZipEntries(bytes)
            log.d("Найдено записей: ${entries.keys.joinToString()}")

            val binEntry = entries.entries.firstOrNull { it.key.endsWith(".bin") }
                ?: return Result.failure(Exception("Файл .bin не найден в ZIP-архиве"))
            val datEntry = entries.entries.firstOrNull { it.key.endsWith(".dat") }
                ?: return Result.failure(Exception("Файл .dat не найден в ZIP-архиве"))

            if (datEntry.value.size != BluetoothConstants.CONFIGURATION_SIZE) {
                return Result.failure(Exception("Файл .dat должен быть ровно ${BluetoothConstants.CONFIGURATION_SIZE} байт, найдено ${datEntry.value.size}"))
            }

            log.d("firmware zip parsed: bin name=${binEntry.key}, bin size=${binEntry.value.size}, dat name=${datEntry.key}, dat size=${datEntry.value.size}")
            Result.success(Pair(binEntry.value, datEntry.value))
        } catch (e: Exception) {
            log.e("processZipFiles failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── ZIP-парсер ────────────────────────────────────────────────────────────

    /**
     * Сканирует ZIP по локальным заголовкам файлов (Local File Header, сигнатура PK\x03\x04).
     * Поддерживаемые методы сжатия: 0 (stored) и 8 (deflated).
     */
    private fun parseZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var pos = 0

        while (pos <= bytes.size - 30) {
            // Локальный заголовок: PK\x03\x04
            if (!isLocalHeader(bytes, pos)) { pos++; continue }

            val method         = readU16(bytes, pos + 8).toInt()
            val compressedSz   = readU32(bytes, pos + 18).toInt()
            val uncompressedSz = readU32(bytes, pos + 22).toInt()
            val nameLen        = readU16(bytes, pos + 26).toInt()
            val extraLen       = readU16(bytes, pos + 28).toInt()
            val dataStart      = pos + 30 + nameLen + extraLen

            if (nameLen < 0 || extraLen < 0 || compressedSz < 0 ||
                dataStart < 0 || dataStart + compressedSz > bytes.size) {
                pos++; continue
            }

            val fullName = bytes.decodeToString(pos + 30, pos + 30 + nameLen)
            val name = fullName.trimEnd('/', '\\').substringAfterLast('/')

            if (name.isNotEmpty() && (name.endsWith(".bin") || name.endsWith(".dat"))) {
                val compressed = bytes.copyOfRange(dataStart, dataStart + compressedSz)
                val fileData: ByteArray? = when (method) {
                    0 -> compressed
                    8 -> inflateRaw(compressed, uncompressedSz)
                    else -> {
                        log.w("Неподдерживаемый метод сжатия $method для '$name'")
                        null
                    }
                }
                if (fileData != null) {
                    result[name] = fileData
                    log.d("Извлечено '$name': ${fileData.size} байт")
                }
            }

            pos = dataStart + compressedSz
        }
        return result
    }

    // ── DEFLATE через platform.zlib ───────────────────────────────────────────

    /**
     * Разжимает raw-DEFLATE поток (wbits = -15, без zlib-заголовка).
     */
    private fun inflateRaw(src: ByteArray, expectedSize: Int): ByteArray? {
        if (src.isEmpty()) return ByteArray(0)
        val outBuf = ByteArray(maxOf(expectedSize, src.size * 4, 1024))

        val written: Int = memScoped {
            // Явно указываем z_stream_s, чтобы избежать проблем с typealias z_stream
            val zs: z_stream_s = alloc()
            zs.zalloc = null
            zs.zfree  = null
            zs.opaque = null

            val zsPtr: CPointer<z_stream_s> = zs.ptr

            // wbits = -15 → raw DEFLATE (без zlib-заголовка)
            if (zlibInflateInit2(zsPtr, -15) != Z_OK) return@memScoped -1

            val w = src.usePinned { sp ->
                outBuf.usePinned { op ->
                    zs.next_in   = sp.addressOf(0).reinterpret()
                    zs.avail_in  = src.size.toUInt()
                    zs.next_out  = op.addressOf(0).reinterpret()
                    zs.avail_out = outBuf.size.toUInt()
                    val ret = zlibInflate(zsPtr, Z_FINISH)
                    val written = outBuf.size - zs.avail_out.toInt()
                    zlibInflateEnd(zsPtr)
                    if (ret == Z_STREAM_END || ret == Z_OK) written else -1
                }
            }
            w
        }

        if (written < 0) {
            log.e("inflateRaw: декомпрессия не удалась")
            return null
        }
        return outBuf.copyOf(written)
    }

    // ── Вспомогательные функции ───────────────────────────────────────────────

    private fun isLocalHeader(b: ByteArray, at: Int): Boolean =
        at + 4 <= b.size &&
        b[at    ] == 0x50.toByte() &&
        b[at + 1] == 0x4B.toByte() &&
        b[at + 2] == 0x03.toByte() &&
        b[at + 3] == 0x04.toByte()

    private fun readU16(b: ByteArray, off: Int): UShort =
        ((b[off + 1].toInt() and 0xFF shl 8) or (b[off].toInt() and 0xFF)).toUShort()

    private fun readU32(b: ByteArray, off: Int): UInt =
        ((b[off + 3].toInt() and 0xFF shl 24) or
         (b[off + 2].toInt() and 0xFF shl 16) or
         (b[off + 1].toInt() and 0xFF shl  8) or
         (b[off    ].toInt() and 0xFF         )).toUInt()

    private fun NSData.toKotlinBytes(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val ptr = bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
        return ptr.readBytes(len)
    }
}
