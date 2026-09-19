package com.hydro.link.server.util

import java.io.File

/**
 * root 执行（替换原来的 Shizuku 通道）。
 *
 * **su 不一定在 PATH 里**：KernelSU 只把 su 暴露给已授权的进程，Magisk 在不同版本里
 * 放在 `/debug_ramdisk`、`/sbin` 等位置。之前直接 `ProcessBuilder("su", …)` 在
 * KernelSU 设备上必然 `ENOENT` —— 表现为"打开 root 执行后命令一直失败"。
 * 所以这里先按候选路径**找到 su 的绝对路径**，找不到就给出可操作的错误信息。
 *
 * 探测不缓存：用户随时可能在 KernelSU/Magisk 里授权或改 su 路径。
 */
object RootShell {

    /** 常见 su 位置，按优先级排列（"su" 表示走 PATH）。 */
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",       // KernelSU
        "/data/adb/magisk/su",        // Magisk
        "/data/adb/ap/bin/su",        // APatch
    )

    data class Result(val ok: Boolean, val output: String, val code: Int)

    /** 已探到的 su 绝对路径（每次探测后更新，仅用于诊断展示）。 */
    @Volatile
    private var lastProbe: String = ""

    /** 找到可用的 su 命令（返回可直接给 ProcessBuilder 的第一个参数）。 */
    fun resolveSu(): String? {
        for (cand in SU_CANDIDATES) {
            if (cand == "su") {
                // 走 PATH：能启动就算可用（是否授权由执行结果说了算）
                if (canStart(cand)) { lastProbe = "PATH 里的 su"; return cand }
            } else if (File(cand).exists() && File(cand).canExecute()) {
                lastProbe = cand
                return cand
            } else if (File(cand).exists()) {
                lastProbe = "$cand（存在但不可执行）"
                return cand
            }
        }
        lastProbe = "未找到"
        return null
    }

    private fun canStart(cmd: String): Boolean = try {
        val p = ProcessBuilder(cmd, "-v").redirectErrorStream(true).start()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * root 是否可用：能跑 `su -c id` 且输出里有 uid=0。
     * **注意**：KernelSU/Magisk 第一次会弹授权框，没授权时会失败（返回 false 并记下来）。
     */
    fun isAvailable(): Boolean {
        val su = resolveSu() ?: return false
        val r = try { runWith(su, "id") } catch (e: Exception) { return false }
        return r.ok && r.output.contains("uid=0")
    }

    /** root 不可用时给 UI 的一句话（带探到的 su 路径，便于用户判断是没授权还是没 root）。 */
    fun statusText(): String = when {
        isAvailable() -> "root 已就绪（$lastProbe）"
        resolveSu() == null -> "未找到 su（已试：${SU_CANDIDATES.joinToString("、")}）"
        else -> "su 在 $lastProbe，但未授权或执行失败"
    }

    /** 执行一条命令（阻塞，调用方负责放到后台线程）。 */
    fun exec(command: String, timeoutMs: Long = 15_000): Result {
        val su = resolveSu()
            ?: return Result(false, "未找到 su（已试：${SU_CANDIDATES.joinToString("、")}）；" +
                "KernelSU/Magisk 里确认已给本应用 root 授权", -1)
        return runWith(su, command, timeoutMs)
    }

    /** 用指定 su 跑一条命令。 */
    private fun runWith(su: String, command: String, timeoutMs: Long = 15_000): Result {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            // 没授权时 su 会一直等（弹框可能被忽略），这里明确提示，别让用户以为是命令本身的问题
            return Result(false, (output.ifBlank { "" } + "\n执行超时（${timeoutMs}ms）：su 是否在等授权？").trim(), -1)
        }
        val code = process.exitValue()
        return Result(code == 0, output, code)
    }
}
