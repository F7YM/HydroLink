package com.hydro.station.server.service

import android.util.Log
import com.hydro.station.server.ICommandService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UserService that runs in Shizuku's process (root/shell uid).
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
