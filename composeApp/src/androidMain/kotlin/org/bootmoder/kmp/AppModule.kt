package org.bootmoder.kmp

import org.bootmoder.kmp.presentation.AndroidSystemChecker
import org.bootmoder.kmp.presentation.AppState
import org.bootmoder.kmp.presentation.BleScanViewModel
import org.bootmoder.kmp.presentation.BootModeViewModel
import org.bootmoder.kmp.presentation.ConnectedViewModel
import org.bootmoder.kmp.presentation.RawModeViewModel
import org.bootmoder.kmp.presentation.ScanViewModel
import org.bootmoder.kmp.presentation.SystemChecker
import org.bootmoder.kmp.presentation.TerminalViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin-модуль UI-слоя Android.
 * Регистрирует:
 *  — AndroidSystemChecker (BT + Location)
 *  — ScanViewModel
 */
val appModule = module {
    // ── Общее состояние ────────────────────────────────────────────────────────
    single { AppState() }

    // ── System checker ─────────────────────────────────────────────────────────
    single<SystemChecker> { AndroidSystemChecker(androidContext()) }

    // ── ViewModels ─────────────────────────────────────────────────────────────
    // Старый ScanViewModel (DataMatrix flow)
    viewModel { ScanViewModel(get(), get(), get(), get(), get()) }

    // Новые ViewModels (UniversalTerminal UI)
    viewModel { BleScanViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ConnectedViewModel(get()) }
    viewModel { BootModeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { TerminalViewModel(get(), get(), get()) }
    viewModel { RawModeViewModel(get(), get(), get()) }
}
