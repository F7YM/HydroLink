package com.hydro.station.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.hydro.station.server.util.DataManager
import rikka.sui.Sui

class HydroApp : Application() {

    companion object {
        const val CHANNEL_ID = "hydro_server_channel"

        init {
            // Initialize Sui (Magisk module) — safe to call even if not installed
            try {
                Sui.init(BuildConfig.APPLICATION_ID)
            } catch (e: Throwable) {
                // Sui not available, ignore
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize data manager
        DataManager.get(this)
        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
