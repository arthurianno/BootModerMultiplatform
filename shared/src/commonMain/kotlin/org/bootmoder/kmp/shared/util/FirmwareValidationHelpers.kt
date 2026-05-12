package org.bootmoder.kmp.shared.util

data class ParsedVersionResponse(
    val hardwareRevision: String,
    val softwareVersion: String
)

data class ManufacturerFirmwareInfo(
    val companyId: Int,
    val version: String,
    val protocol: FirmwareProtocol
)

enum class FirmwareProtocol {
    NRF52_NORDIC_DFU,
    WCH58X_BOOT_MODE,
    UNKNOWN
}

fun parseVersionResponse(response: String): ParsedVersionResponse? {
    val hardware = Regex("""\bhw:([^\s]+)""")
        .find(response)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val software = Regex("""\bsw:([0-9]+(?:\.[0-9]+){2})""")
        .find(response)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return if (hardware != null && software != null) {
        ParsedVersionResponse(hardwareRevision = hardware, softwareVersion = software)
    } else {
        null
    }
}

fun parseSerialResponse(response: String): String? =
    response
        .substringAfter("ser.", missingDelimiterValue = "")
        .trim()
        .takeIf { it.isNotEmpty() }

fun parseManufacturerData(data: ByteArray): ManufacturerFirmwareInfo? {
    if (data.size < 3) return null
    val companyId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    val version = data
        .copyOfRange(2, data.size)
        .decodeToString()
        .trim { it <= ' ' || it == '\u0000' }
        .takeIf { isSemanticVersion(it) }
        ?: return null

    val protocol = when (companyId) {
        0x0059 -> FirmwareProtocol.NRF52_NORDIC_DFU
        0x07D7 -> FirmwareProtocol.WCH58X_BOOT_MODE
        else -> FirmwareProtocol.UNKNOWN
    }
    return ManufacturerFirmwareInfo(companyId = companyId, version = version, protocol = protocol)
}

fun compareSemanticVersions(left: String, right: String): Int {
    val leftParts = left.semanticVersionParts()
    val rightParts = right.semanticVersionParts()
    for (i in 0 until maxOf(leftParts.size, rightParts.size)) {
        val l = leftParts.getOrElse(i) { 0 }
        val r = rightParts.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

fun classifyFirmwareProtocol(version: String): FirmwareProtocol {
    val parts = version.semanticVersionParts()
    if (parts.size < 3) return FirmwareProtocol.UNKNOWN
    val value = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    return when (value) {
        in 4_001_000..4_004_999 -> FirmwareProtocol.NRF52_NORDIC_DFU
        in 4_005_000..4_009_999 -> FirmwareProtocol.WCH58X_BOOT_MODE
        else -> FirmwareProtocol.UNKNOWN
    }
}

fun isSupportedSatelliteName(name: String?): Boolean {
    val value = name.orEmpty()
    return value.startsWith("SatelliteOnline", ignoreCase = true) ||
        value.startsWith("SatelliteExpress", ignoreCase = true) ||
        value.startsWith("SatelliteVoice", ignoreCase = true) ||
        value.startsWith("Dfu", ignoreCase = true)
}

fun serialSuffix(value: String?): String? =
    value
        ?.filter { it.isDigit() }
        ?.takeLast(4)
        ?.takeIf { it.length == 4 }

private fun isSemanticVersion(value: String): Boolean =
    Regex("""\d+\.\d+\.\d+""").matches(value)

private fun String.semanticVersionParts(): List<Int> =
    split(".").mapNotNull { it.toIntOrNull() }
