package org.bootmoder.kmp.shared.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import org.bootmoder.kmp.shared.domain.entity.BleDevice

/**
 * Конвертирует [BleDevice] KMP-домена ↔ [BluetoothDevice] Android API.
 */
class BluetoothDeviceMapper(private val context: Context) {

    /**
     * Возвращает Android [BluetoothDevice] по MAC-адресу из [BleDevice].
     * @return null если Bluetooth недоступен
     */
    fun toBluetoothDevice(device: BleDevice): BluetoothDevice? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.getRemoteDevice(device.address)
    }

    /**
     * Конвертирует Android [BluetoothDevice] в KMP [BleDevice].
     */
    @SuppressLint("MissingPermission")
    fun toDomainDevice(bluetoothDevice: BluetoothDevice, rssi: Int = 0): BleDevice =
        BleDevice(
            address = bluetoothDevice.address,
            name = bluetoothDevice.name,
            rssi = rssi
        )
}

