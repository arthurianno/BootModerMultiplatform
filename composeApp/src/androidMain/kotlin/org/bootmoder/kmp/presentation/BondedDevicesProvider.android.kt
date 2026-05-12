package org.bootmoder.kmp.presentation

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.bootmoder.kmp.shared.domain.entity.BleDevice

@Composable
actual fun rememberBondedDevices(): List<BleDevice> {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return@remember emptyList()
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter?.bondedDevices?.map { device ->
            BleDevice(
                name = device.name ?: "Unknown Device",
                address = device.address,
                rssi = 0
            )
        } ?: emptyList()
    }
}

