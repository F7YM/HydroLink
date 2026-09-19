package com.hydro.link.server.util

import java.io.File

/**
 * root 执行（替换原来的 Shizuku 通道）。
 *
 * 现代 Magisk / KernelSU 会把 `su` **注入到已授权进程的 PATH**，所以正常路径就是
 * "在 PATH 里找到 su 再执行它"。老设备上 su 可能躺在 /system/xbin 之类的固定位置，
 * 所以再兜一层固定路径。
 *
 * 刻意**不**碰 `/data/adb/magisk/su`、`/data/adb/ksu/bin/su` 这类管理器内部路径：
 * 它们不是给第三方 App 用的入口，绕过去可能拿不到正确的挂载命名空间，把"没授权"
 * 这种明确错误变成莫名其妙的执行结果。
 *
 * 探测策略：**只查文件，不执行**（执行 su 会弹授权框，光为了"看看有没有"不该打扰用户）。
 */
object RootShell {

    /** 老位置兜底（现代管理器不需要，留着兼容 SuperSU 时代的设备）。 */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    data class Result(val ok: Boolean, val output: String, val code: Int)

    /** 最近一次探测到的 su（诊断展示用）。 */
    @Volatile
    private var lastProbe: String = ""

    /** 找可用的 su：先扫 PATH（只查文件），再查固定路径；都没有返回 null。 */
    fun resolveSu(): String? {
        for (dir in (System.getenv("PATH") ?: "").split(":")) {
            if (dir.isBlank()) continue
            val f = File(dir, "su")
            if (f.isFile && f.canExecute()) {
                lastProbe = f.absolutePath
                return lastProbe
            }
        }
        for (p in SU_PATHS) {
            val f = File(p)
            if (f.isFile && f.canExecute()) {
                lastProbe = p
                return lastProbe
            }
        }
        lastProbe = ""
        return null
    }

    /**
     * root 是否可用：能跑 `su -c id` 且输出里有 uid=0。
     * 没授权时会失败（首次执行会弹管理器的授权框，这是预期行为）。
     */
    fun isAvailable(): Boolean {
        val su = resolveSu() ?: return false
        val r = try {
            runWith(su, "id")
        } catch (e: Exception) {
            return false
        }
        return r.ok && r.output.contains("uid=0")
    }

    /** 给 UI / 日志的一句话：没找到、找到了但没授权、就绪，三种情况分得清。 */
    fun statusText(): String = when {
        resolveSu() == null -> "未找到 su（PATH 与 ${SU_PATHS.joinToString("、")} 都没有）"
        isAvailable() -> "root 已就绪（$lastProbe）"
        else -> "su 在 $lastProbe，但未授权"
    }

    /** 执行一条命令（阻塞，调用方负责放到后台线程）。 */
    fun exec(command: String, timeoutMs: Long = 15_000): Result {
        val su = resolveSu()
            ?: return Result(
                false,
                "未找到 su（PATH 与 ${SU_PATHS.joinToString("、")} 都没有）；" +
                    "请确认设备已 root，并在 KernelSU/Magisk 里给本应用授权",
                -1,
            )
        return runWith(su, command, timeoutMs)
    }

    /** 用指定 su 跑一条命令；code == -1 表示"压根没跑起来"（异常/超时）。 */
    private fun runWith(su: String, command: String, timeoutMs: Long = 15_000): Result {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            return Result(false, (output + "\n执行超时（${timeoutMs}ms）：su 是否在等授权？").trim(), -1)
        }
        val code = process.exitValue()
        return Result(code == 0, output, code)
    }
}
