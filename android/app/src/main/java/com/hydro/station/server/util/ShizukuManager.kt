package com.hydro.station.server.util

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.hydro.station.server.BuildConfig
import com.hydro.station.server.ICommandService
import rikka.shizuku.Shizuku

/**
 * Manages Shizuku integration: checking availability, requesting permission,
 * binding UserService, and executing commands via Shizuku.
 *
 * All Shizuku API calls are wrapped in try-catch(Throwable) because the
 * Shizuku library can throw RuntimeException if Shizuku isn't installed.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1001

    private var commandService: ICommandService? = null
    private var isBinding = false
    private var pendingCommand: String? = null
    private var pendingCallback: ((success: Boolean, output: String, returnCode: Int) -> Unit)? = null
    private var initialized = false

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        commandService = null
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, if (granted) "Shizuku permission granted" else "Shizuku permission denied")
        if (granted) {
            bindService()
        }
        onPermissionResult?.invoke(granted)
        onPermissionResult = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                commandService = ICommandService.Stub.asInterface(binder)
                Log.d(TAG, "CommandService connected")
                pendingCommand?.let { cmd ->
                    pendingCallback?.let { cb ->
                        executeInternal(cmd, cb)
                    }
                    pendingCommand = null
                    pendingCallback = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandService = null
            Log.d(TAG, "CommandService disconnected")
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, "com.hydro.station.server.service.CommandService")
        )
            .daemon(false)
            .processNameSuffix("command")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /**
     * Lazy initialization — call from SettingsActivity, NOT from Application.onCreate().
     * Registers Shizuku listeners. Safe to call multiple times.
     */
    fun init() {
        if (initialized) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            initialized = true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init Shizuku", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            unbindService()
        } catch (e: Throwable) {
            // ignore
        }
        initialized = false
    }

    /**
     * Check if Shizuku is running (binder available).
     */
    fun isShizukuRunning(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Check if Shizuku permission is granted.
     */
    fun hasPermission(): Boolean {
        return try {
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Request Shizuku permission if not granted.
     * Returns true if permission was already granted, false if requesting or failed.
     */
    fun requestPermission(callback: ((Boolean) -> Unit)? = null): Boolean {
        try {
            if (Shizuku.isPreV11()) {
                callback?.invoke(false)
                return false
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                callback?.invoke(true)
                return true
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // User previously denied — they need to manually grant in Shizuku app
                callback?.invoke(false)
                return false
            }
            // Set callback and request
            onPermissionResult = callback
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            return false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request permission", e)
            callback?.invoke(false)
            return false
        }
    }

    /**
     * Check if Shizuku is fully ready (running + permission granted).
     */
    fun isReady(): Boolean {
        return isShizukuRunning() && hasPermission()
    }

    /**
     * Bind the UserService.
     */
    private fun bindService() {
        if (isBinding || commandService != null) return
        try {
            if (Shizuku.getVersion() < 10) return
            isBinding = true
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind UserService", e)
            isBinding = false
        }
    }

    /**
     * Unbind the UserService.
     */
    private fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Throwable) {
            // ignore
        }
        commandService = null
        isBinding = false
    }

    /**
     * Execute a command via Shizuku.
     * If the service isn't connected yet, the command will be queued.
     */
    fun executeCommand(
        cmd: String,
        callback: (success: Boolean, output: String, returnCode: Int) -> Unit
    ) {
        if (!isReady()) {
            callback(false, "Shizuku not ready", -1)
            return
        }

        if (commandService != null) {
            executeInternal(cmd, callback)
        } else {
            // Queue the command and bind
            pendingCommand = cmd
            pendingCallback = callback
            bindService()
        }
    }

    private fun executeInternal(
        cmd: String,
        callback: (success: Boolean, output: String, returnCode: Int) -> Unit
    ) {
        val service = commandService
        if (service == null) {
            callback(false, "Service not connected", -1)
            return
        }

        try {
            val result = service.exec(cmd)
            // Parse "RETURN_CODE:xxx\n<output>"
            val lines = result.split("\n", limit = 2)
            val returnCode = lines[0].removePrefix("RETURN_CODE:").toIntOrNull() ?: -1
            val output = if (lines.size > 1) lines[1] else ""
            callback(true, output, returnCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Command execution failed", e)
            callback(false, e.message ?: "error", -1)
        }
    }

    /**
     * Execute a command synchronously via Shizuku.
     * Returns a Triple of (success, output, returnCode).
     * Must NOT be called on the main thread.
     */
    fun executeCommandSync(cmd: String): Triple<Boolean, String, Int> {
        if (!isReady()) {
            return Triple(false, "Shizuku not ready", -1)
        }

        // Ensure service is bound
        if (commandService == null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var bindSuccess = false

            pendingCommand = null
            pendingCallback = null

            try {
                if (Shizuku.getVersion() < 10) {
                    return Triple(false, "Shizuku version too old", -1)
                }
                isBinding = true

                val tempConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            commandService = ICommandService.Stub.asInterface(binder)
                            bindSuccess = true
                        }
                        latch.countDown()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        commandService = null
                    }
                }
                Shizuku.bindUserService(userServiceArgs, tempConnection)
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                try { Shizuku.unbindUserService(userServiceArgs, tempConnection, false) } catch (e: Throwable) {}
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to bind for sync command", e)
                return Triple(false, "Failed to bind service", -1)
            }

            if (!bindSuccess || commandService == null) {
                return Triple(false, "Service connection timeout", -1)
            }
        }

        val service = commandService ?: return Triple(false, "Service not connected", -1)

        return try {
            val result = service.exec(cmd)
            val lines = result.split("\n", limit = 2)
            val returnCode = lines[0].removePrefix("RETURN_CODE:").toIntOrNull() ?: -1
            val output = if (lines.size > 1) lines[1] else ""
            Triple(true, output, returnCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Sync command execution failed", e)
            Triple(false, e.message ?: "error", -1)
        }
    }
}
