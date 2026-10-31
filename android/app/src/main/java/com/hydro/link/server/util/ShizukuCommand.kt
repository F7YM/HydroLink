package com.hydro.link.server.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.hydro.link.server.BuildConfig
import com.hydro.link.server.IShellService
import com.hydro.link.server.service.ShizukuShellService
import rikka.shizuku.Shizuku

/**
 * Shizuku 命令行通道：Shizuku 13.x 只公开 UserService 这一条执行路径
 * （`Shizuku.newProcess` 是 private），所以这里绑定我们自己的
 * [ShizukuShellService]（由 Shizuku 在 shell 身份下启动）来跑命令。
 */
object ShizukuCommand {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var service: IShellService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShellService.Stub.asInterface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky {
                if (Authorizer.shizukuGranted()) bindIfNeeded()
            }
        } catch (_: Throwable) {
        }
    }

    fun isBound(): Boolean = service != null

    /** 绑定用户服务（异步；重复调用安全，确保在主线程发起绑定）。 */
    fun bindIfNeeded() {
        val context = appContext ?: return
        if (service != null) return
        val bindTask = Runnable {
            if (service != null) return@Runnable
            try {
                Shizuku.bindUserService(
                    Shizuku.UserServiceArgs(
                        ComponentName(context, ShizukuShellService::class.java)
                    )
                        .daemon(false)
                        .processNameSuffix("shell")
                        .debuggable(BuildConfig.DEBUG)
                        .version(1),
                    connection,
                )
            } catch (_: Throwable) {
                // Shizuku 未运行/未授权：状态由 Authorizer.statusText 反映
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            bindTask.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(bindTask)
        }
    }

    /** 执行命令；未连接时先绑定并等待最多 2s。 */
    fun exec(command: String, timeoutMs: Long): Authorizer.Result {
        if (service == null) {
            bindIfNeeded()
            var waited = 0
            while (service == null && waited < 2_000) {
                Thread.sleep(100)
                waited += 100
            }
        }
        val remote = service
            ?: return Authorizer.Result(false, "Shizuku 服务未连接（请确认已授权）", -1)
        return try {
            val raw = remote.runCommand(command, timeoutMs.toInt())
            val separator = raw.indexOf('\n')
            if (separator < 0) {
                Authorizer.Result(false, raw, -1)
            } else {
                val code = raw.substring(0, separator).toIntOrNull() ?: -1
                val output = raw.substring(separator + 1)
                Authorizer.Result(code == 0, output, code)
            }
        } catch (t: Throwable) {
            Authorizer.Result(false, "Shizuku 执行失败: ${t.message}", -1)
        }
    }
}
