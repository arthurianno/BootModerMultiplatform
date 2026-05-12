package org.bootmoder.kmp.shared.data.ble

import android.util.Log
import no.nordicsemi.android.dfu.DfuProgressListener

/**
 * Базовый DFU-прогресс листенер с дефолтным логированием.
 * Все методы используют блочный синтаксис — Log.X возвращает Int,
 * поэтому выражение-тело (=) привело бы к несовпадению возвращаемого типа.
 */
abstract class DfuLogger : DfuProgressListener {

    override fun onDeviceConnecting(deviceAddress: String) {
        Log.i(TAG, "DeviceConnecting: $deviceAddress")
    }

    override fun onDeviceConnected(deviceAddress: String) {
        Log.i(TAG, "DeviceConnected: $deviceAddress")
    }

    override fun onDfuProcessStarting(deviceAddress: String) {
        Log.i(TAG, "DfuProcessStarting")
    }

    override fun onDfuProcessStarted(deviceAddress: String) {
        Log.i(TAG, "DfuProcessStarted")
    }

    override fun onEnablingDfuMode(deviceAddress: String) {
        Log.i(TAG, "EnablingDfuMode")
    }

    override fun onProgressChanged(
        deviceAddress: String,
        percent: Int,
        speed: Float,
        avgSpeed: Float,
        currentPart: Int,
        partsTotal: Int
    ) {
        Log.i(TAG, "Progress: $percent% ($currentPart/$partsTotal)")
    }

    override fun onFirmwareValidating(deviceAddress: String) {
        Log.i(TAG, "FirmwareValidating")
    }

    override fun onDeviceDisconnecting(deviceAddress: String?) {
        Log.i(TAG, "DeviceDisconnecting")
    }

    override fun onDeviceDisconnected(deviceAddress: String) {
        Log.i(TAG, "DeviceDisconnected")
    }

    override fun onDfuCompleted(deviceAddress: String) {
        Log.i(TAG, "DfuCompleted: $deviceAddress")
    }

    override fun onDfuAborted(deviceAddress: String) {
        Log.i(TAG, "DfuAborted: $deviceAddress")
    }

    override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
        Log.e(TAG, "DfuError: code=$error type=$errorType message=$message")
    }

    companion object {
        private const val TAG = "DfuProgressListener"
    }
}
