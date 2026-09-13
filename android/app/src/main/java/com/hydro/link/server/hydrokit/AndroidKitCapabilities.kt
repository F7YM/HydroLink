package com.hydro.link.server.hydrokit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.hydro.link.server.service.LockAccessibilityService
import com.hydro.link.server.service.MusicController
import com.hydro.link.server.util.NetworkUtil

/**
 * 安卓端对 [KitCapabilities] 的实现：把 kit 能力接到现有服务上。
 *
 *   perf     → 进程/内存/存储统计（与 /api/performance 一致的口径）
 *   music    → MusicController（MediaSession 控制 / 通知监听）
 *   notify   → NotificationManager（Android 8+ 走通知渠道）
 *   lock     → LockAccessibilityService（无障碍锁屏）
 *   commands → DataManager 里配置的命令 + Shizuku/Shell 执行
 *
 * 任何一项能力不可用都只返回失败，不抛异常（插件侧会拿到 error 字段）。
 */
class AndroidKitCapabilities(
    private val context: Context,
    private val port: () -> Int,
    private val dataManagerProvider: () -> com.hydro.link.server.util.DataManager? = { null },
    private val commandRunner: (String) -> Triple<Boolean, String, Int> = { Triple(false, "命令执行能力不可用", -1) },
) : KitCapabilities {

    private val music = MusicController(context)

    override fun perf(): Map<String, Any?> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val totalMb = mem.totalMem / (1024 * 1024)
            val availMb = mem.availMem / (1024 * 1024)
            val usedMb = totalMb - availMb
            val cpu = try {
                // 没有 os.getloadavg 时用 1 分钟负载近似（与桌面 cpu 字段口径一致即可）
                val load: Double = java.io.File("/proc/loadavg").readText().trim()
                    .split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
                // 注意：本包内也有个 Runtime（HydroKit 注册表），所以必须全限定 java.lang.Runtime
                val cores: Int = java.lang.Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                (load / cores.toDouble() * 100.0).coerceIn(0.0, 100.0)
            } catch (e: Exception) {
                0.0
            }
            mapOf<String, Any?>(
                "cpu" to cpu,
                "memPercent" to if (totalMb > 0) (usedMb * 100.0 / totalMb) else 0.0,
                "memUsedMB" to usedMb.toInt(),
                "memTotalMB" to totalMb.toInt(),
            )
        } catch (e: Exception) {
            mapOf<String, Any?>("cpu" to 0.0, "memPercent" to 0.0, "memUsedMB" to 0, "memTotalMB" to 0)
        }
    }

    override fun music(): Map<String, Any?> {
        return try {
            val info = music.getActiveMediaInfo()
            if (info == null) {
                mapOf<String, Any?>("hasMedia" to false, "title" to "", "artist" to "", "playing" to false, "artUrl" to "", "artBlur" to "")
            } else {
                // 与桌面 ABI 上下文同构：artUrl = 原图、artBlur = 手表桌搭背景用的
                // 模糊+压暗版本（示例插件 deskbuddy 拿它当手表背景）。
                // 桌面无论有没有封面都给地址；安卓这边能便宜地判断有没有封面，
                // 没有就给空串，插件就不会去请求一个必然 404 的地址。
                val base = serverBase()
                val hasArt = music.hasAlbumArt()
                mapOf<String, Any?>(
                    "hasMedia" to true,
                    "title" to info.title,
                    "artist" to info.artist,
                    "playing" to info.playing,
                    "artUrl" to if (hasArt) "$base/api/music/art" else "",
                    "artBlur" to if (hasArt) "$base/api/music/art?size=466&blur=26&dim=0.3" else "",
                )
            }
        } catch (e: Exception) {
            mapOf<String, Any?>("hasMedia" to false, "title" to "", "artist" to "", "playing" to false, "artUrl" to "", "artBlur" to "")
        }
    }

    override fun musicAction(action: String): Boolean =
        try { music.control(action) } catch (e: Exception) { false }

    override fun notify(title: String, text: String): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Android 13+ 起发通知需要 POST_NOTIFICATIONS 运行时授权；没授权时
            // nm.notify() 不抛异常但通知不会出现，插件会误以为成功（"静默失败"）。
            // 这里如实返回 false，插件拿到 {"status":"error"} 可自行降级。
            if (!nm.areNotificationsEnabled()) return false
            val channelId = "hydrokit_plugin"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(channelId, "插件通知", NotificationManager.IMPORTANCE_DEFAULT),
                    )
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val n = builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 通知权限是否已授予（Android 13+ 需要 POST_NOTIFICATIONS）——供 UI/诊断用。 */
    fun notifyPermissionGranted(): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled()
    } catch (e: Exception) {
        false
    }

    override fun lock(): Boolean =
        try { LockAccessibilityService.instance?.lockScreen() ?: false } catch (e: Exception) { false }

    override fun commandList(): List<Map<String, Any?>> {
        val dm = dataManagerProvider() ?: return emptyList()
        return try {
            dm.loadCommands().mapIndexed { i, c ->
                mapOf<String, Any?>("id" to i, "name" to c.name, "command" to c.command)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun runCommand(index: Int): Map<String, Any?> {
        val dm = dataManagerProvider() ?: return mapOf("success" to false, "exitCode" to -1, "output" to "命令能力不可用")
        val commands = try { dm.loadCommands() } catch (e: Exception) { return mapOf("success" to false, "exitCode" to -1, "output" to "读取命令失败") }
        if (index < 0 || index >= commands.size) {
            return mapOf("success" to false, "exitCode" to -1, "output" to "命令序号越界")
        }
        val (ok, output, code) = commandRunner(commands[index].command.trim())
        return mapOf("success" to ok, "exitCode" to code, "output" to output.take(1000))
    }

    override fun serverBase(): String = try {
        "http://${NetworkUtil.getLocalIpAddress(context)}:${port()}"
    } catch (e: Exception) {
        "http://127.0.0.1:${port()}"
    }
}
