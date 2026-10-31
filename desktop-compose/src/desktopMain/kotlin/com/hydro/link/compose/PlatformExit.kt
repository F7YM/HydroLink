package com.hydro.link.compose

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import kotlin.system.exitProcess

/**
 * 跨平台干净退出工具。
 *
 * ## 为什么不直接用 [exitProcess] / [System.exit]
 *
 * 在 Linux 下加载了 WebKitGTK 4.1 之后，libc 的 `exit()` 会执行 `__run_exit_handlers`，
 * 触发 WebKit 的 `webkit_web_context` / `WebsiteDataStore` 全局 atexit 析构器。
 * WebKit 的析构器在多线程环境下会断言失败并调用 `WTFCrashWithInfo` -> `abort()`，
 * 导致一个本已完全正常退出的程序退出码变成 134 (SIGABRT)。
 *
 * 调用系统的 `_exit(code)` 系统调用能直接关闭所有文件句柄并终止全进程，
 * 明确不执行 libc 的 atexit 钩子，实现真正的 0 等待、0 残留、状态码恒为 0 的优雅退出。
 */
object PlatformExit {

    fun exitCleanly(code: Int = 0): Nothing {
        runCatching {
            val linker = Linker.nativeLinker()
            val lookup = linker.defaultLookup()
            lookup.find("_exit").ifPresent { addr ->
                val handle = linker.downcallHandle(
                    addr,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
                )
                handle.invokeExact(code)
            }
        }
        // 兜底：如果不支持 FFM 则使用 halt(code) 或 exitProcess(code)
        runCatching { Runtime.getRuntime().halt(code) }
        exitProcess(code)
    }
}
