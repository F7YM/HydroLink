package com.hydro.link.server.plugin

import com.hydro.link.server.store.Store
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 插件运行时（对应 Go `internal/hydrokit`）。
 *
 * 这一层负责：清单注册表、`/api/hydro/list` 的输出形状、view 分发、worker 桥。
 *
 * **JS/WASM 不在本进程里执行**（与 Go 一致）：插件代码跑在浏览器的隐藏 worker 里，
 * 服务端只提供通道（poll/respond）与宿主页资源（`resources/hydrokit/worker.{html,js}`，
 * 与安卓端同一份文件）。所以这里不需要任何 JS/WASM 引擎。
 */
class PluginRuntime(private val store: Store) {

    val pluginStore = PluginStore(store)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 已注册插件：id → 清单。注册 = 从磁盘装载（与 Go 的 registerDiskPlugin 同一时机）。 */
    private val registered = LinkedHashMap<String, PluginInfo>()

    /** worker 桥：JS 插件的实际执行在浏览器侧 worker 里，这里只维护通道。 */
    val bridge = WorkerBridge()

    /** 插件清单的合法性检查（对应 Go 的 `Manifest.Validate`）。 */
    fun registerFromDisk(id: String): Boolean {
        val info = pluginStore.readInstalled(File(pluginStore.pluginsDir(), id)) ?: return false
        val problems = validate(info)
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("；"))
        registered[id] = info
        return true
    }

    /** 重扫插件目录：新增的注册、消失的注销（对应 Go 的 reloadPlugins）。 */
    fun reload(): List<String> {
        val installed = pluginStore.listPlugins()
        val ids = installed.map { it.id }.toSet()
        registered.keys.filterNot { it in ids }.forEach { registered.remove(it) }
        var loaded = 0
        for (info in installed) {
            try {
                registered[info.id] = info
                loaded++
            } catch (e: Exception) {
                // 坏插件跳过，不影响其它（与 Go 一致）
            }
        }
        return registered.keys.sorted()
    }

    fun list(): List<PluginInfo> = registered.values.sortedBy { it.id }

    fun lookup(id: String): PluginInfo? = registered[id]

    fun install(path: String): PluginInfo {
        val info = pluginStore.install(path)
        registered[info.id] = info
        return info
    }

    fun uninstall(id: String) {
        pluginStore.uninstall(id)
        registered.remove(id)
    }

    /** 清单校验：必填项与唯一性（对应 Go 的 Validate）。 */
    private fun validate(info: PluginInfo): List<String> {
        val problems = mutableListOf<String>()
        if (info.id.isBlank()) problems += "清单缺少 id"
        if (info.name.isBlank()) problems += "清单缺少 name"
        if (info.version.isBlank()) problems += "清单缺少 version"
        if (info.entry.isBlank()) problems += "清单缺少 entry"
        if (info.apps.isEmpty() && info.widgets.isEmpty()) problems += "清单没有 apps 或 widgets"
        val appIds = info.apps.map { it.id }
        if (appIds.size != appIds.toSet().size) problems += "apps 的 id 重复"
        val widgetIds = info.widgets.map { it.id }
        if (widgetIds.size != widgetIds.toSet().size) problems += "widgets 的 id 重复"
        return problems
    }

    /** `GET /api/hydro/list` 的响应（协议形状：嵌套的 package + apps/widgets）。 */
    fun listResponse(baseUrl: String): String {
        val plugins = list().map { info ->
            buildString {
                // 协议里的 package 只有这三个字段：Go 的 hydrokit.Package 就是
                // {id, name, version}，author/runtime 是给 GUI 用的，不在协议里
                append("{\"package\":{\"id\":${quote(info.id)},")
                append("\"name\":${quote(info.name)},")
                append("\"version\":${quote(info.version)}}")
                if (info.apps.isNotEmpty()) {
                    append(",\"apps\":[")
                    append(info.apps.joinToString(",") { itemJson(it, baseUrl, info.id) })
                    append("]")
                }
                if (info.widgets.isNotEmpty()) {
                    append(",\"widgets\":[")
                    append(info.widgets.joinToString(",") { itemJson(it, baseUrl, info.id) })
                    append("]")
                }
                // web：是否带 WebUI（web/index.html）。桌面 UI 用它决定是否显示「界面」
                // 按钮；false 时省略（与 Go 的 omitempty 对齐）。
                if (info.hasWeb) append(",\"web\":true")
                append("}")
            }
        }
        return "{\"plugins\":[${plugins.joinToString(",")}]}"
    }

    private fun itemJson(item: Item, baseUrl: String, pkg: String): String = buildString {
        append("{\"id\":${quote(item.id)},")
        append("\"name\":${quote(item.name)}")
        val icon = item.icon.trim()
        // 相对路径的图标要转成 worker asset 的 URL（手表渲染器直接取 URL，带不了设备头）
        if (icon.isNotEmpty() && !icon.contains("://") && !icon.startsWith("data:")) {
            if (baseUrl.isNotEmpty()) {
                val resolved = "$baseUrl/api/hydro/worker/asset?package=" +
                    java.net.URLEncoder.encode(pkg, "UTF-8") +
                    "&path=" + java.net.URLEncoder.encode(icon, "UTF-8")
                append(",\"icon\":${quote(resolved)}")
            }
        } else if (icon.isNotEmpty()) {
            append(",\"icon\":${quote(icon)}")
        }
        if (item.refreshMs > 0) append(",\"refreshMs\":${item.refreshMs}")
        append("}")
    }

    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** 读插件包里的文件（webui 静态资源与 worker asset 都走它，带路径逃逸防护）。 */
    fun readPluginFile(pkg: String, rel: String): FileResult {
        val info = lookup(pkg) ?: return FileResult.NotFound
        val base = info.dir.canonicalPath
        val file = File(info.dir, rel).canonicalFile
        // 越界一律 400「非法路径」（Go 的 store 也是这么分的：非法路径 400、不存在 404）
        if (!file.path.startsWith("$base${File.separator}")) return FileResult.IllegalPath
        return if (file.isFile) FileResult.Found(file) else FileResult.NotFound
    }

    /** 读文件的三种结果：非法路径要单独报 400。 */
    sealed interface FileResult {
        data class Found(val file: File) : FileResult
        data object NotFound : FileResult
        data object IllegalPath : FileResult
    }

    fun stop() {
        bridge.stop()
    }
}

/**
 * Worker 通道（对应 Go `internal/hydrokit/bridge.go` + `api/worker.go`）。
 *
 * JS 插件在**浏览器侧 worker** 里执行，服务端只提供长轮询通道：
 * - worker 侧 `GET /api/hydro/worker/poll?package=<id>` 挂起等活，窗口 **25s**
 * - 服务端每 **800ms** 重新检查一次（Go 的 pollInterval）
 * - 单个请求 **10s** 超时（Go 的 timeout）
 * - worker 用 `POST /api/hydro/worker/respond` 回结果
 *
 * 语义照抄这些数字——手表端的 worker 胶水就是按这套超时写的。
 */
class WorkerBridge(
    private val requestTimeoutMs: Long = 10_000,
    private val pollWindowMs: Long = 25_000,
    private val pollIntervalMs: Long = 800,
) {
    private class Conn {
        val queue = ArrayDeque<Pending>()
        val pending = HashMap<Long, Pending>()
        var nextId = 1L
    }

    private class Pending(
        val id: Long,
        val surface: String,
        val item: String,
        val req: String,
    ) {
        @Volatile var responded: String? = null
        @Volatile var error: String? = null
    }

    private val lock = Any()
    private val conns = mutableMapOf<String, Conn>()

    @Volatile
    var onNeedWake: ((String) -> Unit)? = null

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
        synchronized(lock) {
            conns.clear()
        }
    }

    /** 是否已连上（界面上的「在线/离线」）。 */
    fun isConnected(pkg: String): Boolean = synchronized(lock) { !stopped && conns.containsKey(pkg) }

    fun connectedPackages(): Set<String> = synchronized(lock) { if (stopped) emptySet() else conns.keys.toSet() }

    /**
     * worker 侧来取活。挂起最多 [pollWindowMs]，期间每 [pollIntervalMs] 醒一次
     * （与 Go 的 poll 循环一致：worker 断开时立刻返回空，而不是干等 25s）。
     * 空闲时返回 "{}"，有活时返回 `{"req":{"id":...,"surface":...,"item":...,"req":...}}`。
     */
    fun poll(pkg: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, pollWindowMs)
        while (!stopped && System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                if (stopped) return "{}"
                val conn = conns.getOrPut(pkg) { Conn() }
                val pending = conn.queue.removeFirstOrNull()
                if (pending != null) {
                    conn.pending[pending.id] = pending
                    return "{\"req\":{\"id\":${pending.id},\"surface\":${jsonString(pending.surface)},\"item\":${jsonString(pending.item)},\"req\":${pending.req}}}"
                }
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                break
            }
        }
        return "{}"
    }

    sealed interface RespondResult {
        data object Ok : RespondResult
        data object UnknownPackage : RespondResult
        data object UnknownReq : RespondResult
    }

    /** worker 回结果。 */
    fun respond(pkg: String, id: Long, resultJson: String?, errorMsg: String?): RespondResult = synchronized(lock) {
        val conn = conns[pkg] ?: return RespondResult.UnknownPackage
        val pending = conn.pending.remove(id) ?: return RespondResult.UnknownReq
        if (!errorMsg.isNullOrBlank()) {
            pending.error = errorMsg
        }
        pending.responded = resultJson ?: "null"
        RespondResult.Ok
    }

    /** 服务端向插件发请求并等结果（最多 [requestTimeoutMs]）。 */
    fun call(pkg: String, surface: String, item: String, req: String): String {
        val conn = synchronized(lock) { conns[pkg] }
        if (conn == null) {
            // worker 不在线：先叫醒（宿主会去拉起隐藏 worker），再按 Go 的语义报离线
            onNeedWake?.invoke(pkg)
            throw IllegalStateException("worker is offline")
        }
        val pending = synchronized(lock) {
            val id = conn.nextId++
            val p = Pending(id, surface, item, req)
            conn.queue.addLast(p)
            p
        }
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val responded = pending.responded
            if (responded != null) {
                val err = pending.error
                if (err != null) throw IllegalStateException("worker 返回错误: $err")
                return responded
            }
            Thread.sleep(20)
        }
        synchronized(lock) { conn.pending.remove(pending.id) }
        throw IllegalStateException("worker 超时（${requestTimeoutMs}ms）")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
