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
 *   commands → DataManager 里配置的命令 + root/Shell 执行
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
            // nm.notify() 不抛异常但通知不会出现。这里如实返回 false，路由层统一
            // 回 500 {"error":"通知失败"}（与桌面一致），插件可据此降级。
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

    override fun wolWake(mac: String, host: String?, port: Int?): Map<String, Any?> {
        val macBytes = try {
            val parts = mac.split(":", "-")
            if (parts.size != 6) return mapOf("error" to "无效的 MAC 地址")
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return mapOf("error" to "MAC 解析失败")
        }

        val packetBytes = ByteArray(102)
        for (i in 0 until 6) {
            packetBytes[i] = 0xFF.toByte()
        }
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)
        }

        var lock: android.net.wifi.WifiManager.MulticastLock? = null
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            lock = wm?.createMulticastLock("HydroLink:WOL")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            val targetAddr = java.net.InetAddress.getByName(host ?: "255.255.255.255")
            val targetPort = port ?: 9
            val socket = java.net.DatagramSocket().apply { broadcast = true }
            val packet = java.net.DatagramPacket(packetBytes, packetBytes.size, targetAddr, targetPort)
            socket.send(packet)
            socket.close()
            mapOf("ok" to true)
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "发送 WOL 失败"))
        } finally {
            try {
                if (lock != null && lock.isHeld) lock.release()
            } catch (_: Exception) {}
        }
    }

    override fun wolProbe(host: String, port: Int?, timeout: Int?): Map<String, Any?> {
        val start = System.currentTimeMillis()
        var online = false
        val to = timeout ?: 1000
        try {
            if (port != null && port > 0) {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), to)
                    online = true
                }
            } else {
                online = java.net.InetAddress.getByName(host).isReachable(to)
            }
        } catch (e: Exception) {
            online = false
        }
        val elapsed = System.currentTimeMillis() - start
        return mapOf("online" to online, "rttMs" to elapsed)
    }

    override fun udpSend(host: String, port: Int, data: ByteArray, broadcast: Boolean): Map<String, Any?> {
        var lock: android.net.wifi.WifiManager.MulticastLock? = null
        return try {
            if (broadcast) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                lock = wm?.createMulticastLock("HydroLink:UDP")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
            val targetAddr = java.net.InetAddress.getByName(host)
            val socket = java.net.DatagramSocket().apply {
                if (broadcast) this.broadcast = true
            }
            val packet = java.net.DatagramPacket(data, data.size, targetAddr, port)
            socket.send(packet)
            socket.close()
            mapOf("ok" to true, "bytesSent" to data.size)
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "发送 UDP 失败"))
        } finally {
            try {
                if (lock != null && lock.isHeld) lock.release()
            } catch (_: Exception) {}
        }
    }

    // ---- 屏幕触控与指针模拟能力（input 权限） ----

    @Volatile
    private var virtualCursorX: Float = -1f
    @Volatile
    private var virtualCursorY: Float = -1f

    private fun ensureVirtualCursor(): Pair<Float, Float> {
        val dm = context.resources.displayMetrics
        if (virtualCursorX < 0f || virtualCursorY < 0f) {
            virtualCursorX = dm.widthPixels / 2f
            virtualCursorY = dm.heightPixels / 2f
        }
        return virtualCursorX to virtualCursorY
    }

    override fun inputScreen(): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        return mapOf(
            "width" to dm.widthPixels,
            "height" to dm.heightPixels,
            "density" to dm.density,
            "dpr" to dm.density,
        )
    }

    override fun inputCursor(): Map<String, Any?> {
        val (x, y) = ensureVirtualCursor()
        return mapOf("x" to x, "y" to y)
    }

    override fun inputMove(x: Float, y: Float): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        virtualCursorX = x.coerceIn(0f, dm.widthPixels.toFloat())
        virtualCursorY = y.coerceIn(0f, dm.heightPixels.toFloat())
        return mapOf("ok" to true, "x" to virtualCursorX, "y" to virtualCursorY)
    }

    override fun inputMoveBy(dx: Float, dy: Float): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        val (curX, curY) = ensureVirtualCursor()
        virtualCursorX = (curX + dx).coerceIn(0f, dm.widthPixels.toFloat())
        virtualCursorY = (curY + dy).coerceIn(0f, dm.heightPixels.toFloat())
        return mapOf("ok" to true, "x" to virtualCursorX, "y" to virtualCursorY, "dx" to dx, "dy" to dy)
    }

    override fun inputClick(x: Float?, y: Float?, button: String, count: Int): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        val targetX = x?.coerceIn(0f, dm.widthPixels.toFloat()) ?: run { ensureVirtualCursor().first }
        val targetY = y?.coerceIn(0f, dm.heightPixels.toFloat()) ?: run { ensureVirtualCursor().second }
        virtualCursorX = targetX
        virtualCursorY = targetY

        val service = LockAccessibilityService.instance
        if (service != null) {
            // 多击：双击用单次手势内的两次 stroke（间隔可控，见 dispatchDoubleTap），
            // 而不是循环 dispatchTap（回调等待会让两下间隔过大，被当成单击）
            val success = if (count >= 2) {
                service.dispatchDoubleTap(targetX, targetY)
            } else {
                service.dispatchTap(targetX, targetY)
            }
            if (success) {
                return mapOf("ok" to true, "x" to targetX, "y" to targetY, "button" to button)
            }
        }

        // 降级使用 Shell/Root
        var shellOk = true
        for (i in 0 until count.coerceAtLeast(1)) {
            val (ok, _, code) = commandRunner("input tap ${targetX.toInt()} ${targetY.toInt()}")
            if (!(ok && code == 0)) {
                shellOk = false
                break
            }
            if (count > 1) Thread.sleep(80)
        }
        if (shellOk) {
            return mapOf("ok" to true, "x" to targetX, "y" to targetY, "button" to button, "method" to "shell")
        }

        return mapOf("error" to "点击失败：无障碍服务未启用或手势被取消，且无 Root 权限")
    }

    override fun inputDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        val clampedX1 = x1.coerceIn(0f, dm.widthPixels.toFloat())
        val clampedY1 = y1.coerceIn(0f, dm.heightPixels.toFloat())
        val clampedX2 = x2.coerceIn(0f, dm.widthPixels.toFloat())
        val clampedY2 = y2.coerceIn(0f, dm.heightPixels.toFloat())
        virtualCursorX = clampedX2
        virtualCursorY = clampedY2

        val service = LockAccessibilityService.instance
        if (service != null && service.dispatchSwipe(clampedX1, clampedY1, clampedX2, clampedY2, durationMs)) {
            return mapOf("ok" to true)
        }

        // 降级使用 Shell/Root
        val (ok, _, code) = commandRunner("input swipe ${clampedX1.toInt()} ${clampedY1.toInt()} ${clampedX2.toInt()} ${clampedY2.toInt()} $durationMs")
        if (ok && code == 0) {
            return mapOf("ok" to true, "method" to "shell")
        }

        return mapOf("error" to "滑动失败：无障碍服务未启用或手势被取消，且无 Root 权限")
    }

    override fun inputScroll(dx: Float, dy: Float): Map<String, Any?> {
        val dm = context.resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val midY = dm.heightPixels / 2f
        val factor = dm.density * 50f
        val endX = (midX - dx * factor).coerceIn(0f, dm.widthPixels.toFloat())
        val endY = (midY - dy * factor).coerceIn(0f, dm.heightPixels.toFloat())
        return inputDrag(midX, midY, endX, endY, 200L)
    }

    override fun inputKey(key: String): Map<String, Any?> {
        val k = key.trim().lowercase()
        val service = LockAccessibilityService.instance

        when (k) {
            "back" -> {
                if (service?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true) {
                    return mapOf("ok" to true, "key" to key)
                }
            }
            "home" -> {
                if (service?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) == true) {
                    return mapOf("ok" to true, "key" to key)
                }
            }
            "recents", "app_switch" -> {
                if (service?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) == true) {
                    return mapOf("ok" to true, "key" to key)
                }
            }
            "notifications" -> {
                if (service?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) {
                    return mapOf("ok" to true, "key" to key)
                }
            }
        }

        val keyCode = when (k) {
            "back" -> 4
            "home" -> 3
            "recents", "app_switch" -> 187
            "enter" -> 66
            "space" -> 62
            "escape" -> 111
            "tab" -> 61
            "up", "arrowup" -> 19
            "down", "arrowdown" -> 20
            "left", "arrowleft" -> 21
            "right", "arrowright" -> 22
            "volume_up" -> 24
            "volume_down" -> 25
            "power" -> 26
            else -> k.toIntOrNull()
        }

        if (keyCode != null) {
            val (ok, _, code) = commandRunner("input keyevent $keyCode")
            if (ok && code == 0) {
                return mapOf("ok" to true, "key" to key, "keyCode" to keyCode)
            }
        }

        return if (service != null) {
            mapOf("error" to "不支持的按键: $key")
        } else {
            mapOf("error" to "按键执行失败：无障碍服务未启用且无 Root 权限")
        }
    }

    override fun inputShortcut(keys: List<String>): Map<String, Any?> {
        val (ok, _, code) = commandRunner("input keyevent " + keys.joinToString(" "))
        return if (ok && code == 0) {
            mapOf("ok" to true, "keys" to keys)
        } else {
            mapOf("error" to "快捷键执行失败")
        }
    }

    override fun inputText(text: String): Map<String, Any?> {
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        val (ok, _, code) = commandRunner("input text '$escaped'")
        return if (ok && code == 0) {
            mapOf("ok" to true, "text" to text)
        } else {
            mapOf("error" to "文本输入失败：需要 Root/Shizuku 权限")
        }
    }

    override fun serverBase(): String = try {
        "http://${NetworkUtil.getLocalIpAddress(context)}:${port()}"
    } catch (e: Exception) {
        "http://127.0.0.1:${port()}"
    }
}
