package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable
import org.koin.compose.viewmodel.koinViewModel

@Composable
actual fun koinBleScanViewModel(): BleScanViewModel = koinViewModel()

@Composable
actual fun koinConnectedViewModel(): ConnectedViewModel = koinViewModel()

@Composable
actual fun koinBootModeViewModel(): BootModeViewModel = koinViewModel()

@Composable
actual fun koinTerminalViewModel(): TerminalViewModel = koinViewModel()

@Composable
actual fun koinRawModeViewModel(): RawModeViewModel = koinViewModel()

