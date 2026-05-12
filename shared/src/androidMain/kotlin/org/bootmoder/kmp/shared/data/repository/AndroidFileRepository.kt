package org.bootmoder.kmp.shared.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bootmoder.kmp.shared.domain.repository.FileRepository
import org.bootmoder.kmp.shared.util.BluetoothConstants
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile

/**
 * Android-реализация [FileRepository].
 * Использует java.util.zip.ZipFile для извлечения .bin и .dat из zip-архива прошивки.
 */
class AndroidFileRepository : FileRepository {

    override suspend fun processZipFiles(zipPath: String): Result<Pair<ByteArray, ByteArray>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val zipFile = File(zipPath)
                Log.i(TAG, "Processing ZIP: $zipPath")

                if (!zipFile.exists()) {
                    throw FileNotFoundException("ZIP file not found: $zipPath")
                }

                var binName: String? = null
                var binData: ByteArray? = null
                var datName: String? = null
                var datData: ByteArray? = null

                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        when {
                            entry.name.endsWith(".bin") -> {
                                binName = entry.name.substringAfterLast('/')
                                binData = zip.getInputStream(entry).use { it.readBytes() }
                                Log.i(TAG, "Read .bin: $binName, ${binData?.size} bytes")
                            }
                            entry.name.endsWith(".dat") -> {
                                datName = entry.name.substringAfterLast('/')
                                datData = zip.getInputStream(entry).use { it.readBytes() }
                                Log.i(TAG, "Read .dat: $datName, ${datData?.size} bytes")
                            }
                        }
                    }
                }

                val bin = binData ?: throw IllegalStateException(".bin file not found in ZIP")
                val dat = datData ?: throw IllegalStateException(".dat file not found in ZIP")
                if (dat.size != BluetoothConstants.CONFIGURATION_SIZE) {
                    throw IllegalStateException(".dat file must be exactly ${BluetoothConstants.CONFIGURATION_SIZE} bytes, actual=${dat.size}")
                }

                Log.i(TAG, "firmware zip parsed: bin name=$binName, bin size=${bin.size}, dat name=$datName, dat size=${dat.size}")

                Pair(bin, dat)
            }
        }

    companion object {
        private const val TAG = "AndroidFileRepository"
    }
}
