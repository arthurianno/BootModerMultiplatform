package org.bootmoder.kmp.shared.di

import org.bootmoder.kmp.shared.domain.usecase.CheckDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.ConnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.DisconnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.GetDeviceInfoUseCase
import org.bootmoder.kmp.shared.domain.usecase.GetDevicePasswordUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadConfigurationUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadDfuUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadFirmwareUseCase
import org.bootmoder.kmp.shared.domain.usecase.ProcessFilesUseCase
import org.bootmoder.kmp.shared.domain.usecase.SaveDevicePasswordUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendBatteryCheckCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendBootModeCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendPinCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendVersionCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase
import org.koin.dsl.module

/**
 * Koin-модуль commonMain — платформо-независимые use cases.
 *
 * Репозитории биндятся в платформенных модулях:
 *  — androidMain: [androidSharedModule]
 *  — iosMain:     [iosSharedModule]
 */
val sharedModule = module {

    // ── BLE — сканирование ────────────────────────────────────────────────────
    factory { ScanDevicesUseCase(get()) }
    factory { StopScanUseCase(get()) }

    // ── BLE — соединение ──────────────────────────────────────────────────────
    factory { ConnectDeviceUseCase(get()) }
    factory { DisconnectDeviceUseCase(get()) }

    // ── BLE — UART строковые команды ──────────────────────────────────────────
    factory { SendCommandUseCase(get()) }
    factory { SendPinCommandUseCase(get()) }
    factory { SendVersionCommandUseCase(get()) }
    factory { SendBootModeCommandUseCase(get()) }
    factory { SendBatteryCheckCommandUseCase(get()) }

    // ── BLE — прошивка ────────────────────────────────────────────────────────
    factory { LoadFirmwareUseCase(get()) }
    factory { LoadConfigurationUseCase(get()) }
    factory { LoadDfuUseCase(get()) }

    // ── Файлы ─────────────────────────────────────────────────────────────────
    factory { ProcessFilesUseCase(get()) }

    // ── Валидация устройства ──────────────────────────────────────────────────
    factory { CheckDeviceUseCase(get()) }

    // ── Хранилище (настройки / пароли) ────────────────────────────────────────
    factory { GetDeviceInfoUseCase(get()) }
    factory { SaveDevicePasswordUseCase(get()) }
    factory { GetDevicePasswordUseCase(get()) }
}
