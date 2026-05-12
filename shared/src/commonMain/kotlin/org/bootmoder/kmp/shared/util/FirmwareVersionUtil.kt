package org.bootmoder.kmp.shared.util

/**
 * Результат проверки версии прошивки.
 *
 * Определяет тип чипа и совместимый метод обновления:
 *  — Nordic (4.1.0–4.4.9): DFU через Nordic DFU SDK
 *  — WCH    (4.5.0–4.9.9): бинарная запись через UART Write
 */
sealed class VersionCheckResult {
    /** Nordic DFU: версии 4.1.0 – 4.4.9 */
    data object NordicDfu : VersionCheckResult()

    /** WCH UART: версии 4.5.0 – 4.9.9 */
    data object WchUart : VersionCheckResult()

    /** Версия вне поддерживаемых диапазонов */
    data object UnsupportedVersion : VersionCheckResult()

    /** Строка версии не соответствует ожидаемому формату */
    data object InvalidFormat : VersionCheckResult()
}

/**
 * Извлекает строку sw-версии из ответа устройства.
 * Пример: "hw:1.0 sw:4.2.1 bat:3" → "4.2.1"
 */
fun String.extractFirmwareVersion(): String =
    this.substringAfter("sw:").trim().split(" ")[0]

/**
 * Определяет тип чипа по строке версии устройства.
 * Ожидаемый формат: "hw:X.X sw:X.X.X ..."
 */
fun String.checkFirmwareVersion(): VersionCheckResult {
    return try {
        val swVersion = this.substringAfter("sw:").trim().split(" ")[0]
        val parts = swVersion.split(".").map { it.toInt() }
        if (parts.size < 3) return VersionCheckResult.InvalidFormat

        val (major, minor, patch) = parts
        val version = major * 1_000_000 + minor * 1_000 + patch

        when (version) {
            in 4_001_000..4_004_999 -> VersionCheckResult.NordicDfu
            in 4_005_000..4_009_999 -> VersionCheckResult.WchUart
            else                    -> VersionCheckResult.UnsupportedVersion
        }
    } catch (e: Exception) {
        VersionCheckResult.InvalidFormat
    }
}

/**
 * Вычисляет 3-значный PIN-код из серийного номера (DataMatrix-значение).
 *
 * Алгоритм: берутся последние 6 цифр, применяется хеш-функция.
 */
private const val PIN_MUL    = 599681139L
private const val PIN_PLUS   = 123L
private const val PIN_AND    = 0xFFFFFFFFL
private const val PIN_MOD    = 1000L
private const val PIN_LENGTH = 3

fun String.extractPinCode(): String =
    this.drop(1)
        .takeLast(6)
        .toLong()
        .times(PIN_MUL)
        .plus(PIN_PLUS)
        .and(PIN_AND)
        .mod(PIN_MOD)
        .toString()
        .let { code -> "0".repeat(PIN_LENGTH - code.length) + code }

/**
 * Вычисляет DFU-адрес устройства: последний октет MAC + 1.
 * Пример: "AA:BB:CC:DD:EE:FF" → "AA:BB:CC:DD:EE:00"  (0xFF + 1 = 0x00 с переносом)
 */
fun String.toDfuAddress(): String {
    val tokens = this.split(":")
    val lastOctet = tokens.last().toInt(16)
    val newOctet = ((lastOctet + 1) and 0xFF).toString(16).padStart(2, '0')
    return (tokens.dropLast(1) + newOctet).joinToString(":").uppercase()
}

/**
 * Извлекает X.X.X версию из строки (имени файла или пути).
 * Пример: "/cache/DfuAppOnline_4.2.1.zip" → "4.2.1"
 */
fun extractVersionFromPath(path: String): String? =
    Regex("\\d+\\.\\d+\\.\\d+").find(path)?.value

