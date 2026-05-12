package org.bootmoder.kmp.presentation

import androidx.compose.runtime.Composable
import org.bootmoder.kmp.shared.domain.entity.BleDevice

@Composable
expect fun rememberBondedDevices(): List<BleDevice>

