package com.hydro.link.server

import java.io.File
import java.net.NetworkInterface
import java.time.Duration

/**
 * 系统能力（对应 Go `internal/system`）。
 *
 * 取值方式**逐项照抄** Go 的实现，别"优化"：hostname 拿不到就退回 GOOS 字符串、
 * Linux 版本读 `/etc/os-release` 的 `PRETTY_NAME`、电池读 `/sys/class/power_supply`、
 * 锁屏调桌面环境的命令、音乐走 MPRIS。行为不一致时手表端会看到不一样的数据。
 */
object SystemCapabilities {

    data class Battery(val hasBattery: Boolean, val percent: Int, val charging: Boolean)

    data class Perf(val cpu: Double, val memPercent: Double, val memUsedMb: Long, val memTotalMb: Long)

    /**
     * 当前媒体信息。
     *
     * [artUrl] 是播放器给的封面地址（MPRIS 的 `mpris:artUrl`；Windows 的 SMTC
     * 缩略图以后同样塞这里），空串 = 没有封面。插件不许直接拿它去取图：手表取不到
     * 播放器本地的 file://，必须走宿主 `/api/music/art` 那层处理。
     */
    data class Music(
        val hasMedia: Boolean,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val artUrl: String = "",
    )

    /** 主机名；拿不到退回 OS 名（与 Go 的 `runtime.GOOS` 语义一致）。 */
    fun hostname(): String {
        val fromHost: String? = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            null
        }
        if (fromHost != null && fromHost.isNotBlank()) return fromHost
        return osName
    }

    /**
     * 系统版本的人类可读描述。
     *
     * Linux：读 `/etc/os-release` 的 `PRETTY_NAME`（没有就退回 `NAME`+`VERSION_ID`，
     * 再没有就 "Linux"）；macOS 走 `sw_vers -productVersion`；Windows 走注册表/系统 API
     * （本阶段先给出版本号，parity 测试会验证差异并补齐）。
     */
    fun systemVersion(): String = when {
        isMac -> runCommand(listOf("sw_vers", "-productVersion")).ifBlank { "macOS" }
        isWindows -> windowsVersion()
        else -> linuxVersion()
    }

    private fun linuxVersion(): String {
        val file = File("/etc/os-release")
        if (!file.isFile) return "Linux"
        var name = ""
        var version = ""
        for (line in file.readText().split("\n")) {
            if (line.startsWith("PRETTY_NAME=")) {
                return line.removePrefix("PRETTY_NAME=").trim('"')
            }
            if (line.startsWith("NAME=")) name = line.removePrefix("NAME=").trim('"')
            if (line.startsWith("VERSION_ID=")) version = line.removePrefix("VERSION_ID=").trim('"')
        }
        return if (name.isNotBlank() && version.isNotBlank()) "$name $version" else "Linux"
    }

    private fun windowsVersion(): String {
        // Go 侧用 RtlGetVersion 拿 major.minor.build；这里先用系统属性拼一个可比对的版本号，
        // parity 测试会指出差异，再按结论补 RtlGetVersion 等价实现
        val osName = System.getProperty("os.name").orEmpty()
        val version = System.getProperty("os.version").orEmpty()
        return "$osName $version".trim().ifBlank { "Windows" }
    }

    /**
     * 局域网 IP（手表/手机连的就是它）。
     *
     * 与 Go 的 `LocalIP` 同一套判定思路：优先**默认路由**所在的那块网卡（笔记本常年
     * 连着 Wi-Fi 和 VPN，只按枚举顺序取会拿到 127.0.0.1 或 docker0）；过滤虚拟网卡。
     */
    fun localIp(): String {
        // 默认路由：读 /proc/net/route 里 destination 为 00000000 的条目，取其接口名
        val defaultIface = linuxDefaultInterface()
        val candidates: List<NetworkInterface> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { isVirtualName(it.name) }
            .sortedBy { if (defaultIface != null && it.name == defaultIface) 0 else 1 }
        for (nic in candidates) {
            val addresses: List<java.net.InetAddress> = nic.inetAddresses.toList()
            val ip = addresses.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 } ?: continue
            val bytes = ip.address
            // 只取私有网段（10/8、172.16/12、192.168/16）
            val b1 = bytes[0].toInt() and 0xff
            val b2 = bytes[1].toInt() and 0xff
            val priv = b1 == 10 ||
                (b1 == 172 && b2 in 16..31) ||
                (b1 == 192 && b2 == 168)
            if (priv) return ip.hostAddress.orEmpty()
        }
        return "127.0.0.1"
    }

    private fun linuxDefaultInterface(): String? = try {
        val route = File("/proc/net/route")
        if (route.isFile) {
            route.readLines().drop(1).forEach { line ->
                val cols = line.trim().split("\\s+".toRegex())
                if (cols.size > 2 && cols[1] == "00000000") return cols[0]
            }
        }
        null
    } catch (e: Exception) {
        null
    }

    private fun isVirtualName(name: String): Boolean =
        name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") ||
            name.startsWith("virbr") || name.startsWith("tun") || name.startsWith("tap") ||
            name.startsWith("lo")

    /** 电池：读 `/sys/class/power_supply`（与 Go 的 battery_linux 同一来源）。 */
    fun battery(): Battery = try {
        val dir = File("/sys/class/power_supply")
        val batteries = dir.listFiles()?.filter {
            val type = File(it, "type").takeIf { f -> f.isFile }?.readText()?.trim()
            type == "Battery"
        } ?: emptyList()
        if (batteries.isEmpty()) {
            Battery(hasBattery = false, percent = 0, charging = false)
        } else {
            val b = batteries.first()
            val percent = File(b, "capacity").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
            val status = File(b, "status").takeIf { it.isFile }?.readText()?.trim().orEmpty()
            Battery(
                hasBattery = true,
                percent = percent,
                charging = status.equals("Charging", ignoreCase = true),
            )
        }
    } catch (e: Exception) {
        Battery(hasBattery = false, percent = 0, charging = false)
    }

    /** 性能：CPU 用 /proc/stat 的两次采样差值，内存用 /proc/meminfo。 */
    fun performance(): Perf = try {
        val (cpu, total) = readCpuTimes()
        val mem = readMemInfo()
        Perf(
            cpu = cpu,
            memPercent = mem.percent,
            memUsedMb = mem.usedMb,
            memTotalMb = mem.totalMb,
        )
    } catch (e: Exception) {
        Perf(cpu = 0.0, memPercent = 0.0, memUsedMb = 0, memTotalMb = 0)
    }

    private fun readCpuTimes(): Pair<Double, Long> {
        fun sample(): LongArray? {
            val stat = File("/proc/stat")
            if (!stat.exists() || !stat.canRead()) return null
            val line = stat.readLines().firstOrNull { it.startsWith("cpu ") } ?: return null
            val parts = line.trim().split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return null
            val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
            val total = parts.sum()
            return longArrayOf(idle, total)
        }
        val a = sample()
        if (a == null) {
            return try {
                val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                if (osBean is com.sun.management.OperatingSystemMXBean) {
                    val load = osBean.cpuLoad
                    if (load >= 0.0) {
                        ((load * 10000).toInt() / 100.0) to 0L
                    } else 0.0 to 0L
                } else 0.0 to 0L
            } catch (_: Throwable) {
                0.0 to 0L
            }
        }
        Thread.sleep(120)
        val b = sample() ?: return 0.0 to 0L
        val idleDelta = (b[0] - a[0]).coerceAtLeast(1)
        val totalDelta = (b[1] - a[1]).coerceAtLeast(1)
        val usage = (1.0 - idleDelta.toDouble() / totalDelta) * 100.0
        return (usage * 100).toInt() / 100.0 to totalDelta
    }

    private data class Mem(val percent: Double, val usedMb: Long, val totalMb: Long)

    private fun readMemInfo(): Mem {
        try {
            val meminfo = File("/proc/meminfo")
            if (meminfo.exists() && meminfo.canRead()) {
                val values = mutableMapOf<String, Long>()
                meminfo.forEachLine { line ->
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val num = parts[1].trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                        if (num != null) {
                            values[key] = num
                        }
                    }
                }
                val totalKb = values["MemTotal"] ?: 0L
                val availKb = values["MemAvailable"] ?: 0L
                if (totalKb > 0L) {
                    val usedKb = (totalKb - availKb).coerceAtLeast(0L)
                    return Mem(
                        percent = (usedKb * 100.0 / totalKb).let { ((it * 100).toInt() / 100.0) },
                        usedMb = usedKb / 1024,
                        totalMb = totalKb / 1024,
                    )
                }
            }
        } catch (_: Throwable) {}

        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (osBean is com.sun.management.OperatingSystemMXBean) {
                val totalBytes = osBean.totalMemorySize
                val freeBytes = osBean.freeMemorySize
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
                val totalMb = totalBytes / (1024 * 1024)
                val usedMb = usedBytes / (1024 * 1024)
                val pct = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes) * 100.0 else 0.0
                Mem(
                    percent = (pct * 100).toInt() / 100.0,
                    usedMb = usedMb,
                    totalMb = totalMb,
                )
            } else {
                Mem(0.0, 0L, 0L)
            }
        } catch (_: Throwable) {
            Mem(0.0, 0L, 0L)
        }
    }

    /**
     * 锁屏。
     *
     * Linux 下依次尝试桌面环境的常见命令（与 Go 的 lock_linux 同一思路：gnome-screensaver
     * / loginctl / xfce4-screensaver / swaylock）。都不在就返回 false。
     */
    fun lockScreen(): Boolean {
        val attempts = listOf(
            listOf("loginctl", "lock-session"),
            listOf("xdg-screensaver", "lock"),
            listOf("gnome-screensaver-command", "--lock"),
            listOf("xfce4-screensaver", "--lock"),
            listOf("swaylock", "--grace 0"),
        )
        for (cmd in attempts) {
            if (!hasCommand(cmd[0])) continue
            return runProcess(cmd) == 0
        }
        return false
    }

    private fun hasCommand(name: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
            File(it, name).canExecute()
        }

    /** 音乐信息：MPRIS（与 Go 的 music_linux 同一套 D-Bus 接口）。 */
    fun music(): Music = mpris() ?: Music(false, "", "", false)

    /** 音乐控制的诊断信息：拿不到时说明原因（Go 侧同样会写进日志）。 */
    fun musicDiagnostics(): String {
        val dbus = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        if (dbus.isNullOrBlank()) return "D-Bus 会话地址为空（无桌面会话？）"
        if (isLinux) return "当前 MPRIS 实现读取失败"
        return ""
    }

    fun musicControl(action: String): Boolean {
        val cmd = when (action) {
            "play" -> listOf("playerctl", "play")
            "pause" -> listOf("playerctl", "pause")
            "play_pause", "toggle" -> listOf("playerctl", "play-pause")
            "next" -> listOf("playerctl", "next")
            "previous" -> listOf("playerctl", "previous")
            else -> return false
        }
        if (!hasCommand(cmd[0])) return false
        return runProcess(cmd) == 0
    }

    /**
     * 系统通知。
     *
     * 桌面没有安卓那套通知权限，失败时返回 false（调用方回 500，不静默报成功——
     * 与两端语义统一）。
     */
    fun notify(title: String, text: String): Boolean = try {
        val script = buildString {
            append("notify-send ")
            append(shellQuote(title)).append(' ')
            append(shellQuote(text))
        }
        if (hasCommand("notify-send")) {
            runProcess(listOf("/bin/sh", "-c", script)) == 0
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * `playerctl metadata --format "{{title}}|||{{artist}}|||{{status}}|||{{mpris:artUrl}}"`
     * 的输出解析。
     *
     * 一次调用取全量（Go 的 `music_linux.go` 就是这么做的）：三次独立调用会放大
     * 延迟，而且手表每次刷新都要读一次音乐信息。纯函数，单测直接钉住解析规则。
     */
    internal fun parseMprisMetadata(raw: String): Music? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parts = text.split("|||")
        val title = parts.getOrNull(0).orEmpty()
        val artist = parts.getOrNull(1).orEmpty()
        val playing = parts.getOrNull(2).orEmpty().equals("Playing", ignoreCase = true)
        val artUrl = parts.getOrNull(3).orEmpty()
        // 与 Go 一致：标题和歌手都空 = 没有正在播放的媒体
        if (title.isBlank() && artist.isBlank()) return null
        return Music(hasMedia = true, title = title, artist = artist, playing = playing, artUrl = artUrl)
    }

    private fun mpris(): Music? = try {
        if (!hasCommand("playerctl")) return null
        parseMprisMetadata(
            runCommand(
                listOf(
                    "playerctl", "metadata", "--format",
                    "{{title}}|||{{artist}}|||{{status}}|||{{mpris:artUrl}}",
                ),
            ),
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ 工具

    private fun runCommand(cmd: List<String>): String = try {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out
    } catch (e: Exception) {
        ""
    }

    private fun runProcess(cmd: List<String>): Int = try {
        ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
    } catch (e: Exception) {
        -1
    }

    private val osName: String
        get() = System.getProperty("os.name").orEmpty().lowercase().let {
            when {
                it.contains("mac") -> "darwin"
                it.contains("win") -> "windows"
                else -> "linux"
            }
        }

    private val isLinux get() = !isMac && !isWindows
    private val isMac get() = osName == "darwin"
    private val isWindows get() = osName == "windows"

    // ------------------------------------------------------------ 输入控制 (kit.input)

    private var robotInstance: java.awt.Robot? = null
    private fun robot(): java.awt.Robot? = try {
        if (robotInstance == null) {
            robotInstance = java.awt.Robot().apply { isAutoWaitForIdle = true }
        }
        robotInstance
    } catch (e: Exception) {
        null
    }

    fun inputScreen(): Map<String, Any?> = try {
        val dm = java.awt.Toolkit.getDefaultToolkit().screenSize
        mapOf("width" to dm.width, "height" to dm.height, "dpr" to 1.0)
    } catch (e: Exception) {
        mapOf("error" to "获取屏幕尺寸失败: ${e.message}")
    }

    fun inputCursor(): Map<String, Any?> = try {
        val p = java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point(0, 0)
        mapOf("x" to p.x, "y" to p.y)
    } catch (e: Exception) {
        mapOf("error" to "获取光标位置失败: ${e.message}")
    }

    fun inputMove(x: Float, y: Float): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            r.mouseMove(x.toInt(), y.toInt())
            mapOf("ok" to true, "x" to x.toInt(), "y" to y.toInt())
        } catch (e: Exception) {
            mapOf("error" to "光标移动失败: ${e.message}")
        }
    }

    fun inputMoveBy(dx: Float, dy: Float): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            val p = java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point(0, 0)
            val nx = p.x + dx.toInt()
            val ny = p.y + dy.toInt()
            r.mouseMove(nx, ny)
            mapOf("ok" to true, "x" to nx, "y" to ny, "dx" to dx, "dy" to dy)
        } catch (e: Exception) {
            mapOf("error" to "光标相对移动失败: ${e.message}")
        }
    }

    fun inputClick(x: Float?, y: Float?, button: String = "left", count: Int = 1): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            if (x != null && y != null) {
                r.mouseMove(x.toInt(), y.toInt())
            }
            val mask = when (button.lowercase()) {
                "right" -> java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                "middle" -> java.awt.event.InputEvent.BUTTON2_DOWN_MASK
                else -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            }
            val repeat = count.coerceIn(1, 10)
            for (i in 0 until repeat) {
                r.mousePress(mask)
                r.mouseRelease(mask)
                if (i < repeat - 1) Thread.sleep(40)
            }
            mapOf("ok" to true, "button" to button, "count" to repeat)
        } catch (e: Exception) {
            mapOf("error" to "点击失败: ${e.message}")
        }
    }

    fun inputDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            r.mouseMove(x1.toInt(), y1.toInt())
            val mask = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            r.mousePress(mask)
            val steps = 20
            val delayStep = (durationMs / steps).coerceAtLeast(5L)
            for (i in 1..steps) {
                val curX = (x1 + (x2 - x1) * i / steps).toInt()
                val curY = (y1 + (y2 - y1) * i / steps).toInt()
                r.mouseMove(curX, curY)
                Thread.sleep(delayStep)
            }
            r.mouseRelease(mask)
            mapOf("ok" to true, "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2)
        } catch (e: Exception) {
            mapOf("error" to "拖拽滑动失败: ${e.message}")
        }
    }

    fun inputScroll(dx: Float, dy: Float): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            val amount = dy.toInt().let { if (it == 0 && dy != 0f) (if (dy > 0) 1 else -1) else it }
            r.mouseWheel(amount)
            mapOf("ok" to true, "dx" to dx, "dy" to dy)
        } catch (e: Exception) {
            mapOf("error" to "滚轮滚动失败: ${e.message}")
        }
    }

    private fun resolveKeyCode(name: String): Int? {
        val k = name.trim().lowercase()
        return when (k) {
            "enter", "return" -> java.awt.event.KeyEvent.VK_ENTER
            "space" -> java.awt.event.KeyEvent.VK_SPACE
            "back", "backspace" -> java.awt.event.KeyEvent.VK_BACK_SPACE
            "tab" -> java.awt.event.KeyEvent.VK_TAB
            "esc", "escape" -> java.awt.event.KeyEvent.VK_ESCAPE
            "delete", "del" -> java.awt.event.KeyEvent.VK_DELETE
            "up", "arrowup" -> java.awt.event.KeyEvent.VK_UP
            "down", "arrowdown" -> java.awt.event.KeyEvent.VK_DOWN
            "left", "arrowleft" -> java.awt.event.KeyEvent.VK_LEFT
            "right", "arrowright" -> java.awt.event.KeyEvent.VK_RIGHT
            "pageup" -> java.awt.event.KeyEvent.VK_PAGE_UP
            "pagedown" -> java.awt.event.KeyEvent.VK_PAGE_DOWN
            "home" -> java.awt.event.KeyEvent.VK_HOME
            "end" -> java.awt.event.KeyEvent.VK_END
            "ctrl", "control" -> java.awt.event.KeyEvent.VK_CONTROL
            "shift" -> java.awt.event.KeyEvent.VK_SHIFT
            "alt" -> java.awt.event.KeyEvent.VK_ALT
            "meta", "win", "cmd", "command" -> if (isMac) java.awt.event.KeyEvent.VK_META else java.awt.event.KeyEvent.VK_WINDOWS
            "f1" -> java.awt.event.KeyEvent.VK_F1
            "f2" -> java.awt.event.KeyEvent.VK_F2
            "f3" -> java.awt.event.KeyEvent.VK_F3
            "f4" -> java.awt.event.KeyEvent.VK_F4
            "f5" -> java.awt.event.KeyEvent.VK_F5
            "f6" -> java.awt.event.KeyEvent.VK_F6
            "f7" -> java.awt.event.KeyEvent.VK_F7
            "f8" -> java.awt.event.KeyEvent.VK_F8
            "f9" -> java.awt.event.KeyEvent.VK_F9
            "f10" -> java.awt.event.KeyEvent.VK_F10
            "f11" -> java.awt.event.KeyEvent.VK_F11
            "f12" -> java.awt.event.KeyEvent.VK_F12
            else -> {
                if (k.length == 1) {
                    val ch = k[0]
                    when (ch) {
                        in 'a'..'z' -> java.awt.event.KeyEvent.VK_A + (ch - 'a')
                        in '0'..'9' -> java.awt.event.KeyEvent.VK_0 + (ch - '0')
                        else -> null
                    }
                } else null
            }
        }
    }

    fun inputKey(key: String): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        val code = resolveKeyCode(key) ?: return mapOf("error" to "不支持的按键: $key")
        return try {
            r.keyPress(code)
            r.keyRelease(code)
            mapOf("ok" to true, "key" to key, "keyCode" to code)
        } catch (e: Exception) {
            mapOf("error" to "按键执行失败: ${e.message}")
        }
    }

    fun inputShortcut(keys: List<String>): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        val codes = keys.map { resolveKeyCode(it) ?: return mapOf("error" to "不支持的按键: $it") }
        return try {
            codes.forEach { r.keyPress(it) }
            codes.asReversed().forEach { r.keyRelease(it) }
            mapOf("ok" to true, "keys" to keys)
        } catch (e: Exception) {
            mapOf("error" to "组合键执行失败: ${e.message}")
        }
    }

    fun inputText(text: String): Map<String, Any?> {
        val r = robot() ?: return mapOf("error" to "Robot 输入模拟不可用")
        return try {
            val selection = java.awt.datatransfer.StringSelection(text)
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(selection, selection)
            val modifier = if (isMac) java.awt.event.KeyEvent.VK_META else java.awt.event.KeyEvent.VK_CONTROL
            r.keyPress(modifier)
            r.keyPress(java.awt.event.KeyEvent.VK_V)
            r.keyRelease(java.awt.event.KeyEvent.VK_V)
            r.keyRelease(modifier)
            mapOf("ok" to true, "text" to text)
        } catch (e: Exception) {
            mapOf("error" to "文本输入失败: ${e.message}")
        }
    }
}
