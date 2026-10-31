package com.hydro.link.compose.parity

import com.hydro.link.server.parity.StoreParity
import com.hydro.link.server.store.AppConfig
import com.hydro.link.server.store.Command
import com.hydro.link.server.store.PairRequest
import com.hydro.link.server.store.Store
import java.io.File

/**
 * 跑存储层一致性测试。
 *
 * 流程（每次都从零开始，保证可重复跑）：
 * 1. 用真 Go 代码初始化一个目录（装真插件包），得到「真实起点」
 * 2. 把这份目录复制一份给 Kotlin，两边从**完全相同的起点**出发
 * 3. 同一串操作分别落在 Go 与 Kotlin 上
 * 4. 逐字节 diff 五个 JSON（时钟/密钥字段先归一化）
 *
 * 用法：`./gradlew storeParity`
 *
 * 为什么要有这个：「服务端用 Kotlin 重写、行为要与 Go 100% 一致」这条要求，光靠人读代码
 * 保证不了——两个实现各写各的，迟早在某条边界上分叉。这个测试把「一致」变成可执行判据。
 * 而且它必须**能抓到差异**才有意义：改动 Kotlin 侧让结果偏一点（比如 JSON 缩进），
 * 这个测试要立刻变红。
 */
fun main() {
    val workRoot = File(System.getenv("HYDROLINK_PARITY_DIR") ?: "/tmp/hydrolink-parity")
    val goDir = File(workRoot, "go")
    val kotlinDir = File(workRoot, "kotlin")

    val desktopDir = sequenceOf("desktop", "../desktop")
        .map { File(it).canonicalFile }
        .firstOrNull { File(it, "go.mod").isFile }
        ?: run {
            println("[FAIL] 找不到 desktop/go.mod（从仓库根或 desktop-compose 目录跑）")
            return
        }

    // 1) 全新初始化 Go 目录（场景不幂等，所以每次都重建，不能复用上次的）
    goDir.deleteRecursively()
    if (go(desktopDir, "run", "./cmd/parityinit", goDir.absolutePath) != 0) {
        println("[FAIL] Go 侧初始化失败")
        return
    }

    // 2) Kotlin 从同一份起点出发
    kotlinDir.deleteRecursively()
    kotlinDir.mkdirs()
    goDir.copyRecursively(kotlinDir, overwrite = true)

    // 3) 同一串操作
    try {
        StoreParity.scenario(Store.open(kotlinDir).adapter())
    } catch (e: Exception) {
        println("[FAIL] Kotlin 侧执行场景时断言失败：${e.message}")
        return
    }
    if (go(desktopDir, "run", "./cmd/parityinit", goDir.absolutePath, "--scenario") != 0) {
        println("[FAIL] Go 侧执行场景失败")
        return
    }

    // 4) diff
    val problems = StoreParity.diff(goDir, kotlinDir)
    if (problems.isEmpty()) {
        println("[PASS] 存储层与 Go 行为一致（$goDir vs $kotlinDir）")
    } else {
        println("[FAIL] 存储层与 Go 不一致：")
        problems.forEach { println("  · $it") }
    }
}

/** 跑一条 go 命令，回显输出，失败时把输出打出来。 */
private fun go(desktopDir: File, vararg args: String): Int {
    val pb = ProcessBuilder(listOf("go") + args)
        .directory(desktopDir)
        .redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) println("go ${args.joinToString(" ")} -> $code\n$output")
    return code
}

/** 把 Kotlin 的 [Store] 适配成一致性测试要的接口。 */
private fun Store.adapter(): StoreParity.StoreLike {
    // 用具名引用 store：直接写 loadPendingPairs() 会在适配器里自我递归
    // （返回类型也推不出来，编译器报「recursive problem」）
    val s = this
    return object : StoreParity.StoreLike {
        override fun addPendingPair(deviceId: String, deviceName: String): Boolean =
            s.addPendingPair(deviceId, deviceName)

        override fun loadPendingPairs(): List<PairRequest> = s.loadPendingPairs()
        override fun removePendingPair(deviceId: String) = s.removePendingPair(deviceId)
        override fun addRejected(deviceId: String) = s.addRejected(deviceId)
        override fun isRejected(deviceId: String): Boolean = s.isRejected(deviceId)
        override fun removeRejected(deviceId: String) = s.removeRejected(deviceId)
        override fun addTrustedDevice(deviceId: String, deviceName: String) =
            s.addTrustedDevice(deviceId, deviceName)

        override fun isTrusted(deviceId: String): Boolean = s.isTrusted(deviceId)
        override fun addCommand(name: String, command: String) = s.addCommand(name, command)
        override fun loadCommands(): List<Command> = s.loadCommands()
        override fun saveCommands(commands: List<Command>) = s.saveCommands(commands)
        override fun removeCommand(index: Int) = s.removeCommand(index)
        override fun loadConfig(): AppConfig = s.loadConfig()
        override fun saveConfig(cfg: AppConfig) = s.saveConfig(cfg)
    }
}
