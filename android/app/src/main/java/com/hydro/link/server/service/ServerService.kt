package com.hydro.link.server.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hydro.link.server.HydroLinkApp
import com.hydro.link.server.MainActivity
import com.hydro.link.server.R
import com.hydro.link.server.server.HydroHttpServer
import com.hydro.link.server.util.DataManager

class ServerService : Service() {

    private var httpServer: HydroHttpServer? = null
    private var interconnectHandler: InterconnectHandler? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 9000) ?: 9000
        startForeground(NOTIFICATION_ID, createNotification())
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
            // 载入已安装的 HydroKit 插件并拉起它们的 worker 宿主（WebView）
            try {
                val loaded = server.hydroKit.startHosts(server.pluginHosts)
                if (loaded.isNotEmpty()) {
                    server.addLog("HydroKit: 已加载插件 ${loaded.joinToString(", ")}", "success")
                }
            } catch (e: Exception) {
                server.addLog("HydroKit 插件加载失败: ${e.message}", "warning")
            }
            // Start interconnect handler (XMS Wearable SDK based)
            interconnectHandler = InterconnectHandler(this, server, port).also { it.start() }
            httpServer?.addLog("Interconnect 已启动", "info")

            // 初始化 IME 输入法引擎（异步预检/加载离线手写模型）
            try {
                com.hydro.link.server.ime.ImeManager.init(this)
                httpServer?.addLog("IME 输入法引擎已就绪", "info")
            } catch (e: Throwable) {
                httpServer?.addLog("IME 输入法初始化异常: ${e.message}", "warning")
            }
            // 授权器为 Root 时记一条 su 解析结果：真机上"命令执行失败"十有八九是
            // su 不在 PATH（KernelSU 只对已授权进程暴露 su），日志里先看清这个再排查
            val kind = com.hydro.link.server.util.Authorizer.kindOf(
                DataManager.get(this).loadConfig().authorizer
            )
            if (kind == com.hydro.link.server.util.Authorizer.Kind.ROOT) {
                val su = com.hydro.link.server.util.RootShell.resolveSu()
                server.addLog(
                    if (su == null) "授权器 Root：未找到 su（请在 KernelSU/Magisk 里给本应用授权）"
                    else "授权器 Root：su = $su",
                    if (su == null) "error" else "info",
                )
            }
            ServerService.instance = this
        } catch (e: Exception) {
            httpServer?.addLog("启动失败: ${e.message}", "error")
        }
    }

    private fun stopServer() {
        try {
            // 先停插件宿主（隐藏 WebView），再停 HTTP
            httpServer?.pluginHosts?.destroyAll()
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

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, HydroLinkApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun getServer(): HydroHttpServer? = httpServer

    fun getInterconnectHandler(): InterconnectHandler? = interconnectHandler

    /** 供 UI（插件页 / 插件 WebUI 页）读取运行时状态。 */
    fun server(): HydroHttpServer? = httpServer

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
