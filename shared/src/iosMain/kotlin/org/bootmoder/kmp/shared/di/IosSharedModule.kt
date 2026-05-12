package org.bootmoder.kmp.shared.di

import org.bootmoder.kmp.shared.data.repository.IosBleRepository
import org.bootmoder.kmp.shared.data.repository.IosDeviceValidator
import org.bootmoder.kmp.shared.data.repository.IosDeviceWorkingRepository
import org.bootmoder.kmp.shared.data.repository.IosFileRepository
import org.bootmoder.kmp.shared.domain.repository.BleRepository
import org.bootmoder.kmp.shared.domain.repository.DeviceValidator
import org.bootmoder.kmp.shared.domain.repository.DeviceWorkingRepository
import org.bootmoder.kmp.shared.domain.repository.FileRepository
import org.bootmoder.kmp.shared.domain.usecase.ConnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.DisconnectDeviceUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadConfigurationUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadDfuUseCase
import org.bootmoder.kmp.shared.domain.usecase.LoadFirmwareUseCase
import org.bootmoder.kmp.shared.domain.usecase.ProcessFilesUseCase
import org.bootmoder.kmp.shared.domain.usecase.ScanDevicesUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendBootModeCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendPinCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendVersionCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.StopScanUseCase
import org.koin.dsl.module

/**
 * Koin-модуль для iOS-платформы.
 *
 * Инициализация из Swift:
 * ```swift
 * import Shared
 * IosSharedModuleKt.initKoin()
 * ```
 */
val iosSharedModule = module {

    single<BleRepository> { IosBleRepository() }
    single<DeviceWorkingRepository> { IosDeviceWorkingRepository() }

    single<FileRepository> { IosFileRepository() }

    single<DeviceValidator> {
        IosDeviceValidator(
            connectUseCase = ConnectDeviceUseCase(get()),
            disconnectUseCase = DisconnectDeviceUseCase(get()),
            sendPinUseCase = SendPinCommandUseCase(get()),
            sendVersionUseCase = SendVersionCommandUseCase(get()),
            sendBootModeUseCase = SendBootModeCommandUseCase(get()),
            sendCommandUseCase = SendCommandUseCase(get()),
            processFilesUseCase = ProcessFilesUseCase(get()),
            loadFirmwareUseCase = LoadFirmwareUseCase(get()),
            loadConfigurationUseCase = LoadConfigurationUseCase(get()),
            loadDfuUseCase = LoadDfuUseCase(get()),
            scanDevicesUseCase = ScanDevicesUseCase(get()),
            stopScanUseCase = StopScanUseCase(get())
        )
    }
}

/**
 * Точка входа для Koin из Swift-кода.
 */
fun initKoin() {
    org.koin.core.context.startKoin {
        modules(sharedModule, iosSharedModule)
    }
}
