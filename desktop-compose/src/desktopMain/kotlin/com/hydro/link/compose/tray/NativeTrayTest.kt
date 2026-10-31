package com.hydro.link.compose.tray

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    println("[Test] 正在测试 NativeTray 启动与生命周期...")
    val activateLatch = CountDownLatch(1)
    val quitLatch = CountDownLatch(1)

    val tray = NativeTray(
        onActivate = {
            println("[Test] 触发 onActivate")
            activateLatch.countDown()
        },
        onQuit = {
            println("[Test] 触发 onQuit")
            quitLatch.countDown()
        },
    )

    val started = tray.start()
    println("[Test] tray.start() 结果: $started, isRunning=${tray.isRunning()}")
    if (!started) {
        println("[FAIL] 托盘未成功启动")
        System.exit(1)
    }

    // 等待 1 秒检查进程运行状态
    Thread.sleep(1000)
    println("[Test] 运行中状态: ${tray.isRunning()}")

    // 测试 stop
    tray.stop()
    Thread.sleep(300)
    println("[Test] stop 之后状态: isRunning=${tray.isRunning()}")

    if (!tray.isRunning()) {
        println("[PASS] NativeTray 启动与停止测试通过！")
    } else {
        println("[FAIL] NativeTray 停止失败")
        System.exit(1)
    }
}
