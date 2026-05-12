package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable

/**
 * Платформо-зависимое получение ScanViewModel из Koin.
 *
 * Android: koinViewModel() — ViewModelStore, переживает поворот экрана.
 * iOS:     koinInject()   — single-инстанция из Koin, не требует lifecycle-viewmodel-compose.
 */
@Composable
expect fun koinScanViewModel(): ScanViewModel

