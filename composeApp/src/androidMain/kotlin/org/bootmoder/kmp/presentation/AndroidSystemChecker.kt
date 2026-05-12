package org.bootmoder.kmp.presentation

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android-реализация SystemChecker.
 * Слушает системные broadcast'ы для реактивного отслеживания состояния BT и геолокации.
 */
class AndroidSystemChecker(private val context: Context) : SystemChecker {

    private val _isBluetoothEnabled = MutableStateFlow(isBluetoothOn())
    override val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(isLocationOn())
    override val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED ->
                    _isBluetoothEnabled.value = isBluetoothOn()
                LocationManager.PROVIDERS_CHANGED_ACTION ->
                    _isLocationEnabled.value = isLocationOn()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    private fun isBluetoothOn(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter?.isEnabled == true
    }

    private fun isLocationOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}

