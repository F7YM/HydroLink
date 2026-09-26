package com.hydro.link.server.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hydro.link.server.IShellService
import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务：由 Shizuku 在 shell（或 root）身份下启动，命令在这里执行。
 * 只暴露一个 [runCommand]，返回 `"$exitCode\n$output"`。
 */
class ShizukuShellService : Service() {

    private val binder = object : IShellService.Stub() {
        override fun runCommand(command: String, timeoutMs: Int): String {
            return try {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(
                    if (timeoutMs <= 0) 15_000L else timeoutMs.toLong(),
                    TimeUnit.MILLISECONDS,
                )
                if (!finished) {
                    process.destroy()
                    "-1\n$output\n执行超时"
                } else {
                    "${process.exitValue()}\n$output"
                }
            } catch (e: Exception) {
                "-1\n执行失败: ${e.message}"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
