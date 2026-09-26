package com.hydro.link.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.hydro.link.server.util.DataManager

class HydroLinkApp : Application() {

    companion object {
        const val CHANNEL_ID = "hydro_server_channel"
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize data manager
        DataManager.get(this)
        // 授权器（Shizuku 需要 Application Context 判断是否安装）
        com.hydro.link.server.util.Authorizer.init(this)
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
