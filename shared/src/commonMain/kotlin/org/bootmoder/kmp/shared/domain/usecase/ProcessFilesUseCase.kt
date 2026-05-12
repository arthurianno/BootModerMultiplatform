package org.bootmoder.kmp.shared.domain.usecase

import org.bootmoder.kmp.shared.domain.repository.FileRepository

/** Извлекает .bin и .dat из zip-архива прошивки. */
class ProcessFilesUseCase(private val repository: FileRepository) {
    suspend operator fun invoke(zipPath: String): Result<Pair<ByteArray, ByteArray>> =
        repository.processZipFiles(zipPath)
}

