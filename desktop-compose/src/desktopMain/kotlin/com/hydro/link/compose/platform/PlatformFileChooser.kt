package com.hydro.link.compose.platform

import java.awt.Image
import java.awt.Window as AwtWindow
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.JDialog
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 跨平台桌面文件选择器（打开 / 保存），彻底解决 Linux 下 AWT/Swing 重组件互斥黑屏、卡死与缺失图标的问题。
 *
 * 优先采用系统原生 CLI 工具（KDE kdialog / GNOME zenity），以独立进程模式与 Wayland/桌面环境无缝交互；
 * 严格区分「用户主动取消」与「命令不可用」，一旦原生命令交互完成（无论是选定文件还是用户取消），
 * 立即终止对话流程，坚决杜绝因错误判断而引发的连续弹窗以及误回退到 Swing JFileChooser 导致的死锁卡死。
 */
object PlatformFileChooser {

    val appIconAwtImage: Image? by lazy {
        runCatching {
            val cl = Thread.currentThread().contextClassLoader ?: PlatformFileChooser::class.java.classLoader
            val stream = cl.getResourceAsStream("appicon.png")
            stream?.use { ImageIO.read(it) }
        }.getOrNull()
    }

    fun isCommandAvailable(cmd: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            val file = File(dir, cmd)
            file.isFile && file.canExecute()
        }
    }

    private fun defaultDownloadDir(): File {
        val userHome = File(System.getProperty("user.home") ?: ".")
        return listOf(
            File(userHome, "Downloads"),
            File(userHome, "下载")
        ).firstOrNull { it.isDirectory } ?: userHome
    }

    /** 对话框交互结果状态 */
    sealed interface DialogResult {
        data class Selected(val file: File) : DialogResult
        data object Cancelled : DialogResult
        data object Unavailable : DialogResult
    }

    // ==================== 打开文件 (OPEN) ====================

    fun chooseOpenFile(
        parent: AwtWindow? = null,
        title: String = "选择文件",
        filterDescription: String = "HydroLink 插件包 (*.hpk, *.zip)",
        extensions: List<String> = listOf("hpk", "zip"),
    ): File? {
        val osName = (System.getProperty("os.name") ?: "").lowercase()
        val isLinux = osName.contains("linux")

        if (isLinux) {
            when (val native = chooseOpenFileLinux(title, filterDescription, extensions)) {
                is DialogResult.Selected -> return native.file
                is DialogResult.Cancelled -> return null // 用户明确取消，立即终止，坚决不二次弹窗
                is DialogResult.Unavailable -> {
                    // 仅在原生 CLI 完全不可用时回退 Swing
                    return chooseOpenFileSwing(parent, title, filterDescription, extensions)
                }
            }
        } else {
            val awt = chooseOpenFileAwt(parent, title, extensions)
            if (awt != null) return awt
            return chooseOpenFileSwing(parent, title, filterDescription, extensions)
        }
    }

    private fun chooseOpenFileLinux(
        title: String,
        filterDescription: String,
        extensions: List<String>,
    ): DialogResult {
        val desktop = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").lowercase()
        val preferKde = desktop.contains("kde")

        val tools: List<() -> DialogResult> = if (preferKde) {
            listOf(
                { chooseOpenFileKdialog(title, filterDescription, extensions) },
                { chooseOpenFileZenity(title, filterDescription, extensions) }
            )
        } else {
            listOf(
                { chooseOpenFileZenity(title, filterDescription, extensions) },
                { chooseOpenFileKdialog(title, filterDescription, extensions) }
            )
        }

        for (tool in tools) {
            when (val res = tool()) {
                is DialogResult.Selected -> return res
                is DialogResult.Cancelled -> return res // 用户主动取消，立即返回，不尝试后续备选工具
                is DialogResult.Unavailable -> continue // 当前工具不存在或无法启动，尝试下一个
            }
        }
        return DialogResult.Unavailable
    }

    private fun chooseOpenFileZenity(
        title: String,
        filterDescription: String,
        extensions: List<String>,
    ): DialogResult {
        if (!isCommandAvailable("zenity")) return DialogResult.Unavailable
        return try {
            val patterns = extensions.joinToString(" ") { "*.$it" }
            val process = ProcessBuilder(
                "zenity",
                "--file-selection",
                "--title=$title",
                "--file-filter=$filterDescription | $patterns",
                "--file-filter=所有文件 | *"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                val f = File(output)
                if (f.isFile) DialogResult.Selected(f) else DialogResult.Cancelled
            } else {
                DialogResult.Cancelled
            }
        } catch (_: Throwable) {
            DialogResult.Unavailable
        }
    }

    private fun chooseOpenFileKdialog(
        title: String,
        filterDescription: String,
        extensions: List<String>,
    ): DialogResult {
        if (!isCommandAvailable("kdialog")) return DialogResult.Unavailable
        return try {
            val homeDir = System.getProperty("user.home") ?: "."
            val patterns = extensions.joinToString(" ") { "*.$it" }
            val process = ProcessBuilder(
                "kdialog",
                "--title", title,
                "--getopenfilename", homeDir,
                "$patterns | $filterDescription\n* | 所有文件 (*)"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                val f = File(output)
                if (f.isFile) DialogResult.Selected(f) else DialogResult.Cancelled
            } else {
                DialogResult.Cancelled
            }
        } catch (_: Throwable) {
            DialogResult.Unavailable
        }
    }

    private fun chooseOpenFileAwt(
        parent: AwtWindow?,
        title: String,
        extensions: List<String>,
    ): File? = runCatching {
        val frame = parent as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, title, java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val l = name.lowercase()
            extensions.any { l.endsWith(".$it") }
        }
        dialog.file = extensions.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) {
            val f = File(dir, file)
            if (f.isFile) return@runCatching f
        }
        null
    }.getOrNull()

    private fun chooseOpenFileSwing(
        parent: AwtWindow?,
        title: String,
        filterDescription: String,
        extensions: List<String>,
    ): File? {
        var result: File? = null
        val action = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            runCatching {
                val chooser = object : JFileChooser() {
                    override fun createDialog(parent: java.awt.Component?): JDialog {
                        val dialog = super.createDialog(parent)
                        appIconAwtImage?.let { dialog.iconImages = listOf(it) }
                        return dialog
                    }
                }.apply {
                    dialogTitle = title
                    fileFilter = FileNameExtensionFilter(filterDescription, *extensions.toTypedArray())
                    isMultiSelectionEnabled = false
                }
                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                    val sel = chooser.selectedFile
                    if (sel != null && sel.isFile) result = sel
                }
            }
        }
        if (java.awt.EventQueue.isDispatchThread()) action.run()
        else runCatching { java.awt.EventQueue.invokeAndWait(action) }
        return result
    }

    // ==================== 保存文件 (SAVE) ====================

    fun chooseSaveFile(
        parent: AwtWindow? = null,
        title: String = "保存文件",
        suggestedName: String = "download",
    ): File? {
        val osName = (System.getProperty("os.name") ?: "").lowercase()
        val isLinux = osName.contains("linux")
        val defaultFile = File(defaultDownloadDir(), suggestedName)

        if (isLinux) {
            when (val native = chooseSaveFileLinux(title, defaultFile)) {
                is DialogResult.Selected -> return native.file
                is DialogResult.Cancelled -> return null // 用户明确取消，立即终止，坚决不二次弹窗
                is DialogResult.Unavailable -> {
                    // 仅在原生 CLI 完全不可用时回退 Swing
                    return chooseSaveFileSwing(parent, title, defaultFile)
                }
            }
        } else {
            val awt = chooseSaveFileAwt(parent, title, defaultFile)
            if (awt != null) return awt
            return chooseSaveFileSwing(parent, title, defaultFile)
        }
    }

    private fun chooseSaveFileLinux(title: String, defaultFile: File): DialogResult {
        val desktop = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").lowercase()
        val preferKde = desktop.contains("kde")

        val tools: List<() -> DialogResult> = if (preferKde) {
            listOf(
                { chooseSaveFileKdialog(title, defaultFile) },
                { chooseSaveFileZenity(title, defaultFile) }
            )
        } else {
            listOf(
                { chooseSaveFileZenity(title, defaultFile) },
                { chooseSaveFileKdialog(title, defaultFile) }
            )
        }

        for (tool in tools) {
            when (val res = tool()) {
                is DialogResult.Selected -> return res
                is DialogResult.Cancelled -> return res // 用户主动取消，立即返回，不尝试后续备选工具
                is DialogResult.Unavailable -> continue // 当前工具不存在或无法启动，尝试下一个
            }
        }
        return DialogResult.Unavailable
    }

    private fun chooseSaveFileZenity(title: String, defaultFile: File): DialogResult {
        if (!isCommandAvailable("zenity")) return DialogResult.Unavailable
        return try {
            val process = ProcessBuilder(
                "zenity",
                "--file-selection",
                "--save",
                "--confirm-overwrite",
                "--title=$title",
                "--filename=${defaultFile.absolutePath}"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                DialogResult.Selected(File(output))
            } else {
                DialogResult.Cancelled
            }
        } catch (_: Throwable) {
            DialogResult.Unavailable
        }
    }

    private fun chooseSaveFileKdialog(title: String, defaultFile: File): DialogResult {
        if (!isCommandAvailable("kdialog")) return DialogResult.Unavailable
        return try {
            val process = ProcessBuilder(
                "kdialog",
                "--title", title,
                "--getsavefilename", defaultFile.absolutePath
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                DialogResult.Selected(File(output))
            } else {
                DialogResult.Cancelled
            }
        } catch (_: Throwable) {
            DialogResult.Unavailable
        }
    }

    private fun chooseSaveFileAwt(parent: AwtWindow?, title: String, defaultFile: File): File? = runCatching {
        val frame = parent as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, title, java.awt.FileDialog.SAVE)
        dialog.directory = defaultFile.parent
        dialog.file = defaultFile.name
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) {
            File(dir, file)
        } else null
    }.getOrNull()

    private fun chooseSaveFileSwing(parent: AwtWindow?, title: String, defaultFile: File): File? {
        var result: File? = null
        val action = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            runCatching {
                val chooser = object : JFileChooser() {
                    override fun createDialog(parent: java.awt.Component?): JDialog {
                        val dialog = super.createDialog(parent)
                        appIconAwtImage?.let { dialog.iconImages = listOf(it) }
                        return dialog
                    }
                }.apply {
                    dialogTitle = title
                    selectedFile = defaultFile
                }
                if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                    val sel = chooser.selectedFile
                    if (sel != null) result = sel
                }
            }
        }
        if (java.awt.EventQueue.isDispatchThread()) action.run()
        else runCatching { java.awt.EventQueue.invokeAndWait(action) }
        return result
    }
}
