package com.hydro.link.server.parity

import java.io.File

/**
 * 存储层一致性测试：**同一个操作序列分别打在 Go 与 Kotlin 的 store 上，diff 落盘的文件**。
 *
 * 这是整个「服务端用 Kotlin 重写」的地基——没有它，「行为一致」只是嘴上说说。
 * 为什么先做它：后面每一层（HTTP、系统能力、插件运行时）都靠它来证明没走样。
 *
 * 怎么跑：
 * 1. 用真插件包初始化一个 Go 目录（`go run ./cmd/parityinit <dir>`，见 desktop/cmd/parityinit）
 * 2. 跑本测试：同一串操作分别作用于 Go 目录的副本与新建的 Kotlin 目录
 * 3. 逐文件 diff；有任何差异就打印双方内容并失败
 *
 * 注意：时间戳类字段（trusted_at）不可比——用固定的设备 ID 并在比对时忽略它，
 * 由 [normalize] 统一处理，别让测试因为时钟飘了就红。
 */
object StoreParity {

    /** 一致性测试跑的操作序列（两边必须得到同样的文件内容）。 */
    fun scenario(store: StoreLike) {
        // 配对：新增 → 重复新增不重复登记 → 拒绝 → 移除拒绝
        check(store.addPendingPair("dev-a", "手表 A")) { "第一次配对请求应当是新的" }
        check(!store.addPendingPair("dev-a", "手表 A")) { "同一设备的重复请求不应当重复登记" }
        check(store.addPendingPair("dev-b", "手表 B")) { "第二个设备应当登记成功" }
        store.addRejected("dev-c")
        check(store.isRejected("dev-c")) { "拒绝过的设备应当被记住" }
        store.removeRejected("dev-c")
        check(!store.isRejected("dev-c")) { "移除拒绝后不应再被记住" }

        // 允许配对：进入已配对列表，并从待确认里摘掉
        store.addTrustedDevice("dev-a", "手表 A")
        store.addTrustedDevice("dev-a", "手表 A") // 重复：应保持一条
        store.removePendingPair("dev-a")
        check(store.isTrusted("dev-a")) { "允许后应当已信任" }
        check(store.loadPendingPairs().none { it.deviceId == "dev-a" }) { "允许后待确认列表里不该再有它" }

        // 快捷命令：增 → 改 → 删 → 越界删（Go 侧越界不报错也不改文件）
        store.addCommand("重启服务", "systemctl restart hydrolink")
        store.addCommand("锁屏", "loginctl lock-session")
        val commands = store.loadCommands()
        store.saveCommands(commands.mapIndexed { i, c -> if (i == 0) c.copy(command = "true") else c })
        store.removeCommand(0)
        store.removeCommand(99)
        check(store.loadCommands().size == 1) { "越界删除不应影响列表" }

        // 设置：改端口 / accept_all / 市场源；tray_enabled 的三态（缺省 / 显式 false）
        val cfg = store.loadConfig()
        store.saveConfig(
            cfg.copy(
                lastPort = 41000,
                acceptAll = true,
                marketSource = "https://cdn.jsdelivr.net/gh/F7YM/hydromarket-index@main",
                trayEnabled = false,
            )
        )
        val after = store.loadConfig()
        check(after.lastPort == 41000 && after.acceptAll) { "设置没有正确落盘" }
        check(after.trayEnabled == false) { "tray_enabled 显式 false 应当被读回来" }
    }

    /**
     * 落盘文件比对。返回差异描述列表（空 = 一致）。
     *
     * 逐字节比内容，但会先跑 [normalize]：把 `trusted_at` 这类时钟字段替换成占位符，
     * 否则两个实现各写各的时间，测试就成了「比时钟」而不是比行为。
     */
    fun diff(goDir: File, kotlinDir: File): List<String> {
        val interesting = listOf(
            "config.json",
            "commands.json",
            "trusted_devices.json",
            "pending_pair.json",
            "rejected_devices.json",
        )
        val problems = mutableListOf<String>()
        for (name in interesting) {
            val a = normalize(File(goDir, name))
            val b = normalize(File(kotlinDir, name))
            if (a == null && b == null) continue
            if (a == null || b == null) {
                problems += "$name: 只有一边存在（go=${a != null}, kotlin=${b != null}）"
                continue
            }
            if (a != b) {
                problems += "$name 内容不一致：\n--- Go ---\n$a\n--- Kotlin ---\n$b"
            }
        }
        return problems
    }

    /** 读文件并把时钟字段换成占位符；文件不存在返回 null。 */
    private fun normalize(file: File): String? {
        if (!file.isFile) return null
        return file.readText()
            .replace(TIMESTAMP, "<time>")
            .replace(ENCRYPTION_KEY, "<key>")
    }

    private val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val ENCRYPTION_KEY = Regex("""[0-9a-f]{64}""")

    /** 存储的最小接口：Go 那侧用 adapter 实现，Kotlin 那侧直接用 [com.hydro.link.server.store.Store]。 */
    interface StoreLike {
        fun addPendingPair(deviceId: String, deviceName: String): Boolean
        fun loadPendingPairs(): List<com.hydro.link.server.store.PairRequest>
        fun removePendingPair(deviceId: String)
        fun addRejected(deviceId: String)
        fun isRejected(deviceId: String): Boolean
        fun removeRejected(deviceId: String)
        fun addTrustedDevice(deviceId: String, deviceName: String)
        fun isTrusted(deviceId: String): Boolean
        fun addCommand(name: String, command: String)
        fun loadCommands(): List<com.hydro.link.server.store.Command>
        fun saveCommands(commands: List<com.hydro.link.server.store.Command>)
        fun removeCommand(index: Int)
        fun loadConfig(): com.hydro.link.server.store.AppConfig
        fun saveConfig(cfg: com.hydro.link.server.store.AppConfig)
    }
}
