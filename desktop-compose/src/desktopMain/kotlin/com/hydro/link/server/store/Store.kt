package com.hydro.link.server.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 已信任设备（对应 Go `store.TrustedDevice`）。字段名必须与 Go 侧 JSON 完全一致。 */
@Serializable
data class TrustedDevice(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("trusted_at") val trustedAt: String = "",
)

/** 配对请求（`store.PairRequest`）。 */
@Serializable
data class PairRequest(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
)

/** 快捷命令（`store.Command`）。 */
@Serializable
data class Command(
    @SerialName("name") val name: String = "",
    @SerialName("command") val command: String = "",
)

/**
 * 应用配置（`store.Config`）。
 *
 * 字段与 JSON 键**逐个对齐 Go 侧**，包括那几个看起来没用的：
 * - `encryption_key` 缺省时自动生成（与安卓端同一套：两个去横线 UUID 拼接）
 * - `use_shizuku` 桌面上不用，但老配置里有，丢掉就等于改坏用户数据
 * - `tray_enabled` 是**指针语义**（null = 默认开，只有显式 false 才关），所以不能用
 *   `Boolean` 否则读不出「没这个键」和「显式关」的区别
 */
@Serializable
data class AppConfig(
    @SerialName("encryption_key") val encryptionKey: String = "",
    @SerialName("last_port") val lastPort: Int = 0,
    @SerialName("accept_all") val acceptAll: Boolean = false,
    @SerialName("use_shizuku") val useShizuku: Boolean = false,
    @SerialName("market_source") val marketSource: String = "",
    @SerialName("tray_enabled") val trayEnabled: Boolean? = null,
    @SerialName("last_update_check_date") val lastUpdateCheckDate: String = "",
    @SerialName("last_update_ignore_date") val lastUpdateIgnoreDate: String = "",
)

/**
 * 数据存储（对应 Go `internal/store`）。
 *
 * 目录解析、文件名、JSON 缩进、原子写、缺省回退全部照 Go 的行为来——这是「两端一致」的
 * 地基：同一个数据目录两端都能读写，切换实现不会丢用户数据。
 */
class Store private constructor(val dir: File) {

    private val trustedFile = File(dir, "trusted_devices.json")
    private val commandsFile = File(dir, "commands.json")
    private val configFile = File(dir, "config.json")
    private val rejectedFile = File(dir, "rejected_devices.json")
    private val pendingFile = File(dir, "pending_pair.json")

    /** Go 侧 store 用一把大锁把所有读写串起来；这里同样串行化，避免交错写坏文件。 */
    private val lock = Any()

    val pluginsDir: File get() = File(dir, "plugins")
    val pluginsStorageDir: File get() = File(dir, "plugins-storage")
    val marketplaceDir: File get() = File(dir, "marketplace")
    val cacheDir: File get() = File(dir, "cache")

    // ---- 已配对设备 ----

    fun loadTrustedDevices(): List<TrustedDevice> = synchronized(lock) {
        loadJson(trustedFile, emptyList<TrustedDevice>())
    }

    fun saveTrustedDevices(devices: List<TrustedDevice>) = synchronized(lock) {
        saveJson(trustedFile, devices)
    }

    fun isTrusted(deviceId: String): Boolean =
        loadTrustedDevices().any { it.deviceId == deviceId }

    /** 已存在则检查是否需要补全或更新名称（Go 侧也是「已信任就直接成功」）。 */
    fun addTrustedDevice(deviceId: String, deviceName: String) = synchronized(lock) {
        val devices = loadTrustedDevices().toMutableList()
        val idx = devices.indexOfFirst { it.deviceId == deviceId }
        if (idx >= 0) {
            val existing = devices[idx]
            if (existing.deviceName.isBlank() && deviceName.isNotBlank()) {
                devices[idx] = existing.copy(deviceName = deviceName)
                saveJson(trustedFile, devices)
            }
            return@synchronized
        }
        devices += TrustedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            // Go 的格式是 "2006-01-02 15:04:05"（本地时区、无毫秒）
            trustedAt = LocalDateTime.now().format(TRUSTED_AT),
        )
        saveJson(trustedFile, devices)
    }

    fun removeTrustedDevice(deviceId: String) = synchronized(lock) {
        val kept = loadTrustedDevices().filterNot { it.deviceId == deviceId }
        saveJson(trustedFile, kept)
    }

    // ---- 拒绝过的设备 ----

    fun isRejected(deviceId: String): Boolean = loadRejected().contains(deviceId)

    private fun loadRejected(): List<String> = synchronized(lock) {
        loadJson(rejectedFile, emptyList<String>())
    }

    fun addRejected(deviceId: String) = synchronized(lock) {
        val list = loadRejected().toMutableList()
        if (!list.contains(deviceId)) list += deviceId
        saveJson(rejectedFile, list)
    }

    fun removeRejected(deviceId: String) = synchronized(lock) {
        saveJson(rejectedFile, loadRejected().filterNot { it == deviceId })
    }

    // ---- 待确认的配对请求 ----

    fun loadPendingPairs(): List<PairRequest> = synchronized(lock) {
        loadJson(pendingFile, emptyList<PairRequest>())
    }

    /** 返回 true = 新请求；false = 已在列表里（Go 侧同名设备不重复登记，但若设备名有更新则同步刷新）。 */
    fun addPendingPair(deviceId: String, deviceName: String): Boolean = synchronized(lock) {
        val pairs = loadPendingPairs().toMutableList()
        val index = pairs.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            val existing = pairs[index]
            if (deviceName.isNotBlank() && existing.deviceName != deviceName) {
                pairs[index] = existing.copy(deviceName = deviceName)
                saveJson(pendingFile, pairs)
            }
            return@synchronized false
        }
        pairs += PairRequest(deviceId, deviceName)
        saveJson(pendingFile, pairs)
        true
    }

    fun removePendingPair(deviceId: String) = synchronized(lock) {
        saveJson(pendingFile, loadPendingPairs().filterNot { it.deviceId == deviceId })
    }

    // ---- 快捷命令 ----

    fun loadCommands(): List<Command> = synchronized(lock) {
        loadJson(commandsFile, emptyList<Command>())
    }

    fun saveCommands(commands: List<Command>) = synchronized(lock) {
        saveJson(commandsFile, commands)
    }

    fun addCommand(name: String, command: String) = synchronized(lock) {
        saveJson(commandsFile, loadCommands() + Command(name, command))
    }

    /** 越界时**不报错也不改文件**（Go 侧就是这个行为：界面点错索引不该崩服务）。 */
    fun removeCommand(index: Int) = synchronized(lock) {
        val commands = loadCommands().toMutableList()
        if (index !in commands.indices) return@synchronized
        commands.removeAt(index)
        saveJson(commandsFile, commands)
    }

    // ---- 配置 ----

    fun loadConfig(): AppConfig = synchronized(lock) {
        val cfg = loadJson(configFile, AppConfig())
        // Go 侧：last_port 缺失/为 0 时兜底 9000（读出来就补齐，返回值不是原文件内容）
        if (cfg.lastPort == 0) cfg.copy(lastPort = DEFAULT_PORT) else cfg
    }

    fun saveConfig(cfg: AppConfig) = synchronized(lock) {
        saveJson(configFile, cfg)
    }

    // ---- JSON 读写（语义照 Go）----

    private inline fun <reified T> loadJson(file: File, default: T): T {
        if (!file.isFile) return default
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return default
        }
        // Go 侧：文件存在但为空 → 当作默认值（不是报错）
        if (text.isEmpty()) return default
        return try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            default
        }
    }

    private inline fun <reified T> saveJson(file: File, value: T) {
        file.parentFile?.mkdirs()
        // Go 侧是 MarshalIndent(v, "", "  ")：两格缩进。格式一致才能让两端 diff 出来一样
        val text = json.encodeToString(value)
        // 原子写：先写 .tmp 再 rename，避免半截文件（Go 侧同名同做法）
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // rename 在某些文件系统上跨目录会失败，退化成覆盖写
            file.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_PORT = 9000

        /**
         * 缩进两格 + 保留 `null`（`tray_enabled: null` 也要写出来，Go 的指针语义靠它区分
         * 「默认开」和「显式关」）；其余未知字段忽略，Go 侧加字段不会让旧版 Kotlin 崩。
         */
        internal val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }

        internal val TRUSTED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 数据目录：`os.UserConfigDir()/hydrolink`，取不到就退回 `~/.hydrolink`。
         * 与 Go 侧完全一致（Linux/macOS 是 `$XDG_CONFIG_HOME` 或 `~/.config`）。
         */
        fun dataDir(): File {
            val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home")?.let { File(it, ".config").path }
            if (!configHome.isNullOrBlank()) return File(configHome, "hydrolink")
            val home = System.getProperty("user.home") ?: return File(".hydrolink")
            return File(home, ".hydrolink")
        }

        /**
         * 打开（或创建）一个 Store。
         *
         * 与 Go `NewInDir` 一样：建目录、确保 config 存在，没有加密键就生成一个
         * （两个去横线的 UUID 拼接，和安卓端同一套方案）。
         */
        fun open(dir: File = dataDir()): Store {
            dir.mkdirs()
            val store = Store(dir)
            val cfg = store.loadConfig()
            if (cfg.encryptionKey.isBlank()) {
                store.saveConfig(cfg.copy(encryptionKey = newEncryptionKey()))
            }
            return store
        }

        /** 与安卓端同一套：两个去横线 UUID 拼起来的 64 位十六进制。 */
        fun newEncryptionKey(): String =
            UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "")
    }
}

// ---------------------------------------------------------------------------
// 插件存储（KV）：对应 Go 的 `LoadPluginKV` / `SavePluginKV`
// ---------------------------------------------------------------------------

/**
 * 插件的 KV 存储（`plugins-storage/<id>.json`）。
 *
 * 卸载插件**不删**这个文件（与安卓端一致：重装后设置还在）。
 */
fun Store.loadPluginKV(pluginId: String): MutableMap<String, JsonElement> {
    val file = File(File(dir, "plugins-storage"), "$pluginId.json")
    if (!file.isFile) return mutableMapOf()
    return try {
        val obj = Store.json.parseToJsonElement(file.readText()) as? JsonObject
            ?: return mutableMapOf()
        obj.toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }
}

fun Store.savePluginKV(pluginId: String, kv: Map<String, JsonElement>) {
    // Go 侧对插件 ID 有正则校验（防路径逃逸），这里同样拒
    if (!PLUGIN_ID.matches(pluginId)) {
        throw IllegalArgumentException("无效的插件 ID: \"$pluginId\"")
    }
    val dir = File(dir, "plugins-storage")
    dir.mkdirs()
    val file = File(dir, "$pluginId.json")
    val tmp = File(dir, "$pluginId.json.tmp")
    tmp.writeText(Store.json.encodeToString(JsonObject.serializer(), JsonObject(kv)))
    if (!tmp.renameTo(file)) {
        file.writeText(tmp.readText())
        tmp.delete()
    }
}

private val PLUGIN_ID = Regex("^[A-Za-z0-9._-]{1,100}$")
