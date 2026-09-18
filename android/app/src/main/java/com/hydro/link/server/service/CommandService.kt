package com.hydro.link.server.service

import android.util.Log
import com.hydro.link.server.ICommandService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 辅助服务（历史遗留：原先用于 Shizuku 的 UserService）。
 * Executes shell commands via Runtime.exec().
 */
class CommandService : ICommandService.Stub() {

    companion object {
        private const val TAG = "CommandService"
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(cmd: String): String {
        Log.d(TAG, "Executing: $cmd")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText() +
                    BufferedReader(InputStreamReader(process.errorStream)).readText()
            val returnCode = process.waitFor()
            process.destroy()
            "RETURN_CODE:$returnCode\n$output"
        } catch (e: Exception) {
            "RETURN_CODE:-1\n${e.message ?: "error"}"
        }
    }
}
