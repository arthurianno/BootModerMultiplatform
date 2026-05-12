package org.bootmoder.kmp

import org.bootmoder.kmp.presentation.AppState
import org.bootmoder.kmp.presentation.BleScanViewModel
import org.bootmoder.kmp.presentation.BootModeViewModel
import org.bootmoder.kmp.presentation.ConnectedViewModel
import org.bootmoder.kmp.presentation.IosSystemChecker
import org.bootmoder.kmp.presentation.RawModeViewModel
import org.bootmoder.kmp.presentation.ScanViewModel
import org.bootmoder.kmp.presentation.SystemChecker
import org.bootmoder.kmp.presentation.TerminalViewModel
import org.bootmoder.kmp.shared.di.iosSharedModule
import org.bootmoder.kmp.shared.di.sharedModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * iOS-модуль Koin: регистрирует IosSystemChecker и ScanViewModel.
 */
private val iosAppModule = module {
    // ── Общее состояние ────────────────────────────────────────────────────────
    single { AppState() }

    // ── System checker ─────────────────────────────────────────────────────────
    single<SystemChecker> { IosSystemChecker() }

    // ── ViewModels как single (iOS не использует ViewModelStore) ───────────────
    single { ScanViewModel(get(), get(), get(), get(), get()) }
    single { BleScanViewModel(get(), get(), get(), get(), get(), get(), get()) }
    single { ConnectedViewModel(get(), get()) }
    single { BootModeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { TerminalViewModel(get(), get(), get()) }
    single { RawModeViewModel(get(), get(), get()) }
}

/**
 * Полная инициализация Koin для iOS.
 *
 * Вызывается из Swift:
 * ```swift
 * import ComposeApp
 * KoinIosKt.doInitKoinIos()
 * ```
 *
 * Примечание: Kotlin/Native переименовывает функции начинающиеся на `init`
 * добавляя префикс `do`, поэтому в Swift вызов — `doInitKoinIos()`.
 */
fun initKoinIos() {
    startKoin {
        modules(
            sharedModule,    // use cases (commonMain)
            iosSharedModule, // репозитории iOS (iosMain из :shared)
            iosAppModule     // ViewModel + SystemChecker (iosMain из :composeApp)
        )
    }
}
