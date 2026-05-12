package org.bootmoder.kmp.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bootmoder.kmp.shared.util.Logger
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.darwin.NSObject

/**
 * iOS-реализация SystemChecker.
 *
 * CBCentralManager.state сначала может быть unknown, поэтому состояние Bluetooth
 * обновляется после delegate callback centralManagerDidUpdateState.
 * Location permission для BLE scanning на iOS не требуется; boolean оставлен true,
 * чтобы общий UI не блокировал сканирование по Android-специфичному требованию.
 */
class IosSystemChecker : SystemChecker {

    private val log = Logger("IosSystemChecker")

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    // Для BLE scan на iOS отдельный Location permission не нужен.
    private val _isLocationEnabled = MutableStateFlow(true)
    override val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            updateBluetoothState(central)
        }
    }

    private val centralManager = CBCentralManager(delegate = centralDelegate, queue = null)

    init {
        log.d("location check: not required for BLE on iOS")
        updateBluetoothState(centralManager)
    }

    private fun updateBluetoothState(manager: CBCentralManager) {
        val state = manager.state
        val authorization = manager.authorization
        val enabled = state == CBManagerStatePoweredOn &&
            authorization != CBManagerAuthorizationDenied &&
            authorization != CBManagerAuthorizationRestricted

        _isBluetoothEnabled.value = enabled
        log.d("bluetooth state=${state.stateDescription()}, authorization=${authorization.authorizationDescription()}, enabled=$enabled")
    }

    private fun Long.stateDescription(): String = when (this) {
        CBManagerStateUnknown -> "unknown"
        CBManagerStateResetting -> "resetting"
        CBManagerStateUnsupported -> "unsupported"
        CBManagerStateUnauthorized -> "unauthorized"
        CBManagerStatePoweredOff -> "poweredOff"
        CBManagerStatePoweredOn -> "poweredOn"
        else -> "state=$this"
    }

    private fun Long.authorizationDescription(): String = when (this) {
        CBManagerAuthorizationNotDetermined -> "notDetermined"
        CBManagerAuthorizationRestricted -> "restricted"
        CBManagerAuthorizationDenied -> "denied"
        CBManagerAuthorizationAllowedAlways -> "allowedAlways"
        else -> "authorization=$this"
    }
}
