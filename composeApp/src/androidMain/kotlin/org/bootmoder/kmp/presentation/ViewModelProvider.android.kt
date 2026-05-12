package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable
import org.koin.compose.viewmodel.koinViewModel

@Composable
actual fun koinScanViewModel(): ScanViewModel = koinViewModel()

