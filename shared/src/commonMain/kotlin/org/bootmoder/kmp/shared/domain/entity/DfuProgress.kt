package org.bootmoder.kmp.shared.domain.entity

/**
 * Состояние процесса DFU (Device Firmware Update).
 */
enum class DfuState {
    IDLE,
    STARTING,
    ENABLING_DFU_MODE,
    UPLOADING,
    VALIDATING,
    DISCONNECTING,
    COMPLETED,
    ABORTED,
    ERROR
}

/**
 * Прогресс обновления прошивки через DFU.
 *
 * @param state         Текущее состояние DFU-процесса
 * @param percent       Процент загрузки текущей части (0..100)
 * @param currentPart   Номер текущей части (для многочастных DFU-пакетов)
 * @param partsTotal    Общее количество частей
 * @param speed         Скорость загрузки в байт/сек (0, если недоступно)
 * @param avgSpeed      Средняя скорость загрузки в байт/сек
 * @param errorMessage  Сообщение об ошибке (только при state == ERROR)
 */
data class DfuProgress(
    val state: DfuState = DfuState.IDLE,
    val percent: Int = 0,
    val currentPart: Int = 1,
    val partsTotal: Int = 1,
    val speed: Float = 0f,
    val avgSpeed: Float = 0f,
    val errorMessage: String? = null
) {
    val isFinished: Boolean
        get() = state == DfuState.COMPLETED || state == DfuState.ABORTED || state == DfuState.ERROR
}

