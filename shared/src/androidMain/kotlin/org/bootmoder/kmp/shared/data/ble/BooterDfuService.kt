package org.bootmoder.kmp.shared.data.ble

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import no.nordicsemi.android.dfu.DfuBaseService

/**
 * DFU-сервис для Nordic обновления прошивки.
 * Зарегистрирован в AndroidManifest composeApp.
 */
class BooterDfuService : DfuBaseService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun getNotificationTarget(): Class<out Activity>? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            Class.forName("org.bootmoder.kmp.MainActivity") as Class<Activity>
        }.getOrNull()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DfuBaseService.NOTIFICATION_CHANNEL_DFU,
                "Firmware Update",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nordic DFU firmware update notifications"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

