package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject

@Composable
actual fun koinBleScanViewModel(): BleScanViewModel = koinInject()

@Composable
actual fun koinConnectedViewModel(): ConnectedViewModel = koinInject()

@Composable
actual fun koinBootModeViewModel(): BootModeViewModel = koinInject()

@Composable
actual fun koinTerminalViewModel(): TerminalViewModel = koinInject()

@Composable
actual fun koinRawModeViewModel(): RawModeViewModel = koinInject()

