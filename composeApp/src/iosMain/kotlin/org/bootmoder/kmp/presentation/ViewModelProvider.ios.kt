package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject

/**
 * На iOS используем koinInject() вместо koinViewModel(), чтобы избежать
 * IrLinkageError от несовместимости koin-compose-viewmodel с lifecycle 2.10+.
 * ScanViewModel зарегистрирован как single в iosAppModule.
 */
@Composable
actual fun koinScanViewModel(): ScanViewModel = koinInject()

