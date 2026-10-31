package com.hydro.link.compose.tray

import com.hydro.link.compose.PlatformExit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Linux 原生系统托盘管理器（基于 Freedesktop StatusNotifierItem + com.canonical.dbusmenu）。
 *
 * ## 为什么不能用 Java AWT 的 SystemTray / TrayIcon
 *
 * Java AWT 的 `SystemTray` 只实现了 20 年前基于 X11 的 XEmbed 托盘协议（`_NET_SYSTEM_TRAY`）。
 * 在现代 Linux 桌面（特别是 KDE Plasma Wayland、GNOME 40+ 等）下：
 * 1. Wayland 混成器完全不支持 X11 弹出菜单在面板上抓取指针与正确弹出；
 * 2. `xembedsniproxy` 无法正常向 AWT `TrayIcon` 派发鼠标点击与动作事件；
 * 3. 结果是：托盘图标点击没有任何反应、右键菜单无法弹出、无法点击“退出”，一旦窗口隐藏
 *    用户将彻底被困在后台，只能通过终端 kill 杀进程。
 *
 * 本模块在 Linux 下启动专用的轻量级 StatusNotifierItem 助手进程（无 cgo 依赖的纯 Go DBus 实现，
 * 逻辑与 Go 版 HydroLink 的 `internal/tray` 100% 对齐），直接向桌面会话总线注册原生 SNI 接口。
 * KDE Plasma 与 GNOME 原生渲染托盘图标与右键菜单，点击“显示主窗口”和“退出”能 100% 可靠响应。
 */
class NativeTray(
    private val onActivate: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var process: Process? = null
    @Volatile
    private var isRunning = false

    fun isRunning(): Boolean = isRunning && process?.isAlive == true

    /**
     * 启动原生托盘助手。
     * @return true 表示成功启动或已在运行；false 表示启动失败（需降级）
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning()) return true
        val helperFile = extractHelper() ?: run {
            println("[Tray] 未能解压原生托盘助手")
            return false
        }

        return try {
            val pb = ProcessBuilder(
                helperFile.absolutePath,
                "--title", "HydroLink",
                "--tooltip", "HydroLink — 桌面服务端",
            )
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            val proc = pb.start()
            process = proc

            val readerThread = Thread({
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            when (line.trim()) {
                                "READY" -> {
                                    isRunning = true
                                    println("[Tray] Linux 原生系统托盘已就绪 (StatusNotifierItem)")
                                }
                                "ACTIVATE" -> {
                                    println("[Tray] 收到激活事件（点击图标或显示主窗口）")
                                    SwingUtilities.invokeLater {
                                        runCatching { onActivate() }
                                    }
                                }
                                "QUIT" -> {
                                    println("[Tray] 收到退出事件（点击菜单退出）")
                                    SwingUtilities.invokeLater {
                                        runCatching { onQuit() }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 读取中断或进程退出
                } finally {
                    isRunning = false
                }
            }, "NativeTray-Reader").apply {
                isDaemon = true
                start()
            }

            // 等待最多 1.5 秒确认就绪
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline && !isRunning && proc.isAlive) {
                Thread.sleep(50)
            }

            isRunning
        } catch (e: Exception) {
            println("[Tray] 启动原生托盘助手失败: ${e.message}")
            isRunning = false
            false
        }
    }

    /**
     * 停止原生托盘助手并清理 DBus 注册。
     */
    @Synchronized
    fun stop() {
        val proc = process ?: return
        isRunning = false
        process = null
        try {
            proc.outputStream.bufferedWriter().use { writer ->
                writer.write("QUIT\n")
                writer.flush()
            }
            if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                proc.destroy()
            }
        } catch (e: Exception) {
            runCatching { proc.destroyForcibly() }
        }
        println("[Tray] 原生系统托盘已停止")
    }

    private fun extractHelper(): File? {
        val resourcePath = "/bin/linux/trayhelper"
        val input = javaClass.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath.removePrefix("/"))
            ?: return null

        val cacheDir = File(System.getProperty("user.home"), ".hydrolink/bin").apply { mkdirs() }
        val target = File(cacheDir, "trayhelper")

        try {
            input.use { inStream ->
                target.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            target.setExecutable(true, false)
            return target
        } catch (e: Exception) {
            println("[Tray] 写入 trayhelper 文件失败: ${e.message}")
            return null
        }
    }

    companion object {
        val isLinux: Boolean = System.getProperty("os.name").lowercase().contains("linux")
    }
}
