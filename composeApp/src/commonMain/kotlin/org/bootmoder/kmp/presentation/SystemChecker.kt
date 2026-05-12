package org.bootmoder.kmp.presentation

import kotlinx.coroutines.flow.StateFlow

/**
 * Платформо-независимый интерфейс для проверки состояния системных сервисов.
 * Android: AndroidSystemChecker (BroadcastReceiver)
 * iOS:     IosSystemChecker (CoreBluetooth state holder)
 */
interface SystemChecker {
    val isBluetoothEnabled: StateFlow<Boolean>
    val isLocationEnabled: StateFlow<Boolean>
}
