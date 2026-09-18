package com.hydro.link.server.util

/**
 * root 执行（替换原来的 Shizuku 通道）。
 *
 * 设置里打开"使用 root 执行"后，快速命令与插件命令都通过 `su -c <cmd>` 跑；关掉则退回
 * 普通 `sh -c`（受应用沙箱限制）。root 是否可用就是"`su` 能不能跑通"——每次执行都重新
 * 探测一次（几毫秒），不缓存：用户可能在 Magisk 里改了授权，缓存会让状态长时间不对。
 *
 * 安全提醒（UI 里也会弹窗提示）：root 下执行的命令以 uid 0 运行，插件请求的命令同样
 * 走这条路——所以只装可信插件、只配自己能看懂的快速命令。
 */
object RootShell {

    /** 一次命令的结果：成功与否 / 输出 / 返回码。 */
    data class Result(val ok: Boolean, val output: String, val code: Int)

    /**
     * root 是否可用：跑一次 `su -c id` 看 uid 是不是 0。
     * 没有 root、用户拒绝授权、或 su 不存在都会返回 false。
     */
    fun isAvailable(): Boolean = try {
        val r = exec("id")
        r.ok && r.output.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /** root 不可用时给 UI 的一句话。 */
    fun statusText(): String = if (isAvailable()) "root 已就绪" else "root 不可用（未 root 或未授权）"

    /** 执行一条命令（阻塞，调用方负责放到后台线程）。 */
    fun exec(command: String, timeoutMs: Long = 15_000): Result {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            return Result(false, output.ifBlank { "执行超时" }, -1)
        }
        val code = process.exitValue()
        return Result(code == 0, output, code)
    }
}
