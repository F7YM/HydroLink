package com.hydro.link.server.util

import android.app.Activity
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 命令授权器：**无 / Root / Shizuku** 三选一（默认「无」，即普通 shell）。
 *
 * - [Kind.NONE]：直接 `sh -c`（应用自身权限）
 * - [Kind.ROOT]：见 [RootShell]（`su -c`）
 * - [Kind.SHIZUKU]：Shizuku（`dev.rikka.shizuku`），需要装 Shizuku 并授权；
 *   未授权时命令直接失败并给出明确原因，不静默降级
 */
object Authorizer {

    enum class Kind(val id: String, val label: String) {
        NONE("none", "无"),
        ROOT("root", "Root"),
        SHIZUKU("shizuku", "Shizuku");

        companion object {
            fun fromId(id: String?): Kind = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    data class Result(val ok: Boolean, val output: String, val code: Int)

    /** Shizuku 授权请求码（Activity 里 requestPermission 用）。 */
    const val SHIZUKU_REQUEST_CODE = 0x5A1

    fun kindOf(id: String?): Kind = Kind.fromId(id)

    // ---------------- Shizuku ----------------

    fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuInstalled(context: android.content.Context): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: Exception) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 请求 Shizuku 授权（需前台 Activity；用户没装 Shizuku 时不要调用）。 */
    fun requestShizukuPermission(activity: Activity) {
        try {
            if (shizukuAlive()) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (_: Throwable) {
            // 用户拒绝或 Shizuku 未运行：状态由 statusText 反映，不弹自己的提示
        }
    }

    private fun execShizuku(command: String, timeoutMs: Long): Result {
        if (!shizukuGranted()) {
            val why = when {
                !installed() -> "未安装 Shizuku"
                !shizukuAlive() -> "Shizuku 未运行"
                else -> "未授予 Shizuku 权限"
            }
            return Result(false, why, -1)
        }
        return ShizukuCommand.exec(command, timeoutMs)
    }

    // 供 execShizuku 判断"没装"用；由 App 启动时注入，避免到处传 Context
    @Volatile
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        ShizukuCommand.init(context)
    }

    private fun installed(): Boolean {
        val ctx = appContext ?: return false
        return shizukuInstalled(ctx)
    }

    // ---------------- 执行 ----------------

    /** 执行一条命令（阻塞，调用方放后台线程）。 */
    fun exec(command: String, kind: Kind, timeoutMs: Long = 15_000): Result = when (kind) {
        Kind.ROOT -> RootShell.exec(command, timeoutMs).let { Result(it.ok, it.output, it.code) }
        Kind.SHIZUKU -> execShizuku(command, timeoutMs)
        Kind.NONE -> execPlain(command, timeoutMs)
    }

    private fun execPlain(command: String, timeoutMs: Long): Result {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                Result(false, (output + "\n执行超时（${timeoutMs}ms）").trim(), -1)
            } else {
                val code = process.exitValue()
                Result(code == 0, output, code)
            }
        } catch (e: Exception) {
            Result(false, "命令执行失败: ${e.message}", -1)
        }
    }

    /** 当前授权器是否可用（选择时的状态行文本）。 */
    fun statusText(kind: Kind): String = when (kind) {
        Kind.NONE -> "以应用自身权限执行"
        Kind.ROOT -> RootShell.statusText()
        Kind.SHIZUKU -> when {
            !installed() -> "未安装 Shizuku"
            !shizukuAlive() -> "Shizuku 未运行"
            !shizukuGranted() -> "Shizuku 未授权"
            else -> "Shizuku 已授权"
        }
    }

    /** 探测/请求授权；返回给 UI 用的最新状态文本。 */
    fun probe(kind: Kind, activity: Activity?): String {
        if (kind == Kind.SHIZUKU) {
            if (activity != null && shizukuAlive() && !shizukuGranted()) {
                requestShizukuPermission(activity)
            }
            if (shizukuGranted()) ShizukuCommand.bindIfNeeded()
        }
        return statusText(kind)
    }
}
