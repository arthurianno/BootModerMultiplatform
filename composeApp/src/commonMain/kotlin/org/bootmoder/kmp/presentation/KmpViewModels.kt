package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable

// ── Expect-функции для получения ViewModel через Koin ──────────────────────────
// Android: koinViewModel() — ViewModelStore, переживает поворот экрана.
// iOS:     koinInject()   — single-инстанция из Koin.

@Composable
expect fun koinBleScanViewModel(): BleScanViewModel

@Composable
expect fun koinConnectedViewModel(): ConnectedViewModel

@Composable
expect fun koinBootModeViewModel(): BootModeViewModel

@Composable
expect fun koinTerminalViewModel(): TerminalViewModel

@Composable
expect fun koinRawModeViewModel(): RawModeViewModel

