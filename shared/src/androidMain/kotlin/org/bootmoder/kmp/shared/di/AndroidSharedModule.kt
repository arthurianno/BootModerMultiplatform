package org.bootmoder.kmp.shared.di

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import org.bootmoder.kmp.shared.data.ble.BleService
import org.bootmoder.kmp.shared.data.ble.BleServiceImpl
import org.bootmoder.kmp.shared.data.ble.BluetoothDeviceMapper
import org.bootmoder.kmp.shared.data.repository.AndroidBleRepository
import org.bootmoder.kmp.shared.data.repository.AndroidDeviceValidatorImpl
import org.bootmoder.kmp.shared.data.repository.AndroidDeviceWorkingRepository
import org.bootmoder.kmp.shared.data.repository.AndroidFileRepository
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
import org.bootmoder.kmp.shared.domain.usecase.SendPinCommandUseCase
import org.bootmoder.kmp.shared.domain.usecase.SendVersionCommandUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidSharedModule = module {

    // ── Низкоуровневые Android-утилиты ────────────────────────────────────────

    single { BluetoothDeviceMapper(androidContext()) }

    single<BleService> {
        BleServiceImpl(
            context = androidContext(),
            deviceMapper = get()
        )
    }

    // ── Settings (SharedPreferences через multiplatform-settings) ─────────────

    single {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("bootmoder_prefs", Context.MODE_PRIVATE)
        )
    }

    // ── Репозитории ───────────────────────────────────────────────────────────

    single<BleRepository> {
        AndroidBleRepository(
            context = androidContext(),
            bleService = get(),
            deviceMapper = get()
        )
    }

    single<FileRepository> { AndroidFileRepository() }

    single<DeviceWorkingRepository> { AndroidDeviceWorkingRepository(get()) }

    // DeviceValidator зависит от use cases — создаётся как single,
    // чтобы избежать циклических зависимостей через factory
    single<DeviceValidator> {
        AndroidDeviceValidatorImpl(
            context = androidContext(),
            connectUseCase = ConnectDeviceUseCase(get()),
            disconnectUseCase = DisconnectDeviceUseCase(get()),
            sendPinUseCase = SendPinCommandUseCase(get()),
            sendVersionUseCase = SendVersionCommandUseCase(get()),
            sendBootModeUseCase = SendBootModeCommandUseCase(get()),
            processFilesUseCase = ProcessFilesUseCase(get()),
            loadFirmwareUseCase = LoadFirmwareUseCase(get()),
            loadConfigurationUseCase = LoadConfigurationUseCase(get()),
            loadDfuUseCase = LoadDfuUseCase(get()),
            scanDevicesUseCase = ScanDevicesUseCase(get())
        )
    }
}
