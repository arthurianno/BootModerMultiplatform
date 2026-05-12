package org.bootmoder.kmp.shared.domain.entity

/**
 * Режим BLE-сканирования.
 *
 * LOW_POWER      — редкие сообщения рекламы, минимальный расход энергии (интервал ~1000 мс)
 * BALANCED       — баланс между энергопотреблением и скоростью обнаружения (интервал ~250 мс)
 * LOW_LATENCY    — максимальная скорость сканирования, активно расходует батарею (интервал ~100 мс)
 */
enum class ScanMode {
    LOW_POWER,
    BALANCED,
    LOW_LATENCY
}

