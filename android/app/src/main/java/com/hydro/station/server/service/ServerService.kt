package com.hydro.station.server.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hydro.station.server.HydroApp
import com.hydro.station.server.MainActivity
import com.hydro.station.server.R
import com.hydro.station.server.server.HydroHttpServer
import com.hydro.station.server.util.DataManager

class ServerService : Service() {

    private var httpServer: HydroHttpServer? = null
    private var interconnectHandler: InterconnectHandler? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 9000) ?: 9000
        startForeground(NOTIFICATION_ID, createNotification(port))
        startServer(port)
        return START_STICKY
    }

    private fun startServer(port: Int) {
        try {
            if (httpServer != null) {
                httpServer?.stop()
            }
            val server = HydroHttpServer(this, port).also {
                it.start(SOCKET_READ_TIMEOUT, false)
                it.addLog("服务已启动 · 端口 $port", "success")
            }
            httpServer = server
            // Start interconnect handler (XMS Wearable SDK based)
            interconnectHandler = InterconnectHandler(this, server, port).also { it.start() }
            httpServer?.addLog("Interconnect 已启动", "info")
            ServerService.instance = this
        } catch (e: Exception) {
            httpServer?.addLog("启动失败: ${e.message}", "error")
        }
    }

    private fun stopServer() {
        try {
            interconnectHandler?.stop()
            interconnectHandler = null
            httpServer?.stop()
        } catch (e: Exception) {
            // ignore
        }
        httpServer = null
    }

    override fun onDestroy() {
        stopServer()
        ServerService.instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, HydroApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun getServer(): HydroHttpServer? = httpServer

    fun getInterconnectHandler(): InterconnectHandler? = interconnectHandler

    companion object {
        const val EXTRA_PORT = "port"
        const val NOTIFICATION_ID = 1
        const val SOCKET_READ_TIMEOUT = 5000

        @Volatile
        var instance: ServerService? = null
            private set

        fun start(context: Context, port: Int) {
            val intent = Intent(context, ServerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
