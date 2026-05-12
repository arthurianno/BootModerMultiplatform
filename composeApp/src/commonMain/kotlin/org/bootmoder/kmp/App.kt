package org.bootmoder.kmp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.bootmoder.kmp.presentation.screens.BleScanScreen
import org.bootmoder.kmp.presentation.screens.BootModeScreen
import org.bootmoder.kmp.presentation.screens.ConnectedDeviceScreen
import org.bootmoder.kmp.presentation.screens.RawModeScreen
import org.bootmoder.kmp.presentation.screens.TerminalModeScreen

private enum class AppScreen {
    SCAN, CONNECTED, BOOT_MODE, TERMINAL, RAW_MODE
}

@Composable
fun App(
    onPickFirmware: () -> Unit = {},
    // legacy params kept for backward compat
    onPickFiles: () -> Unit = {},
    selectedFileNames: List<String> = emptyList()
) {
    var currentScreen by remember { mutableStateOf(AppScreen.SCAN) }

    MaterialTheme {
        when (currentScreen) {
            AppScreen.SCAN -> BleScanScreen(
                onNavigateToConnected = { currentScreen = AppScreen.CONNECTED }
            )
            AppScreen.CONNECTED -> ConnectedDeviceScreen(
                onExit = { currentScreen = AppScreen.SCAN },
                onNavigate = { route ->
                    currentScreen = when (route) {
                        "boot_mode"     -> AppScreen.BOOT_MODE
                        "raw_mode"      -> AppScreen.RAW_MODE
                        "terminal_mode" -> AppScreen.TERMINAL
                        else            -> currentScreen
                    }
                }
            )
            AppScreen.BOOT_MODE -> BootModeScreen(
                onNavigateBack = { currentScreen = AppScreen.CONNECTED },
                onExit = { currentScreen = AppScreen.SCAN },
                onPickFirmware = onPickFirmware
            )
            AppScreen.TERMINAL -> TerminalModeScreen(
                onNavigateBack = { currentScreen = AppScreen.CONNECTED },
                onExit = { currentScreen = AppScreen.SCAN }
            )
            AppScreen.RAW_MODE -> RawModeScreen(
                onNavigateBack = { currentScreen = AppScreen.CONNECTED },
                onExit = { currentScreen = AppScreen.SCAN }
            )
        }
    }
}