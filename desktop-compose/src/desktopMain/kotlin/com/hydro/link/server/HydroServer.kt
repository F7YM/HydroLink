package com.hydro.link.server

import com.hydro.link.compose.webui.WebUiFetchPolyfill
import com.hydro.link.compose.webui.WebUiSelectPolyfill
import com.hydro.link.server.plugin.PluginRuntime
import com.hydro.link.server.plugin.WorkerBridge
import com.hydro.link.server.store.AppConfig
import com.hydro.link.server.store.Command
import com.hydro.link.server.store.PairRequest
import com.hydro.link.server.store.Store
import com.hydro.link.server.store.loadPluginKV
import com.hydro.link.server.store.savePluginKV
import com.hydro.link.server.store.TrustedDevice
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Kotlin 版 HTTP 服务端（对应 Go `internal/api`）。
 *
 * 目标是**行为与 Go 100% 一致**，所以下面这些细节都是照抄的，不要"顺手优化"：
 * - 路由顺序：webui → worker → reload → hydro 分发 → 普通端点 → 404
 * - 每条端点的状态码与**错误文案逐字一致**（手表/手机端按文案判断，别改）
 * - CORS 头（含 `Connection: close`）
 * - 路径去掉尾部斜杠（根路径除外）
 * - `/api/hydro*` 统一 `Cache-Control: no-store`
 * - 日志环形缓冲上限 200，格式 `[HH:mm:ss] 消息`
 * - 响应体用 `json.Encoder` 编码，**结尾带一个换行**（Go 的 Encoder 就是这样）
 *
 * 一致性由 `parity/HttpParity` 保证：同一套请求分别打两边，规范化后逐字段 diff。
 */
class HydroServer(
    private val store: Store,
    /** 与 Go 的 `api.Version` 对齐（打包时由 Gradle 注入 -X 覆盖）。 */
    private val version: String = "1.3.0",
) {
    /** 插件运行时（清单注册表 + view 分发 + worker 桥）。 */
    val runtime = PluginRuntime(store)

    /** 局域网基址：清单里相对路径的图标要拼成绝对 URL 才能给渲染器用。 */
    @Volatile
    var baseUrlProvider: () -> String = { "" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val logs = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val maxLogs = 200

    /** 配对请求到达时的回调（UI 用它弹授权窗）。 */
    @Volatile
    var onPairRequested: ((String, String) -> Unit)? = null

    /** 实际监听端口（写进日志与 /api/settings 的 current_port）。 */
    @Volatile
    var port: Int = 0

    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    /**
     * 封面源注入点（单测用假封面）。
     *
     * 生产路径：`playerctl metadata {{mpris:artUrl}}` → [AlbumArtFetcher] 取字节 →
     * [MusicArtCodec] 解码。真机上"封面是裂图"看不出坏在哪一步，所以必须能在
     * JVM 里把整条端点链路（路由 → 参数 → 像素处理 → JPEG）跑通。
     */
    @Volatile
    var musicArtOverride: MusicArtSource? = null

    private val musicArtRoute: MusicArtRoute by lazy {
        MusicArtRoute(
            currentKey = { musicArtOverride?.key ?: SystemCapabilities.music().artUrl },
            loadSource = { key ->
                val forced = musicArtOverride
                if (forced != null) MusicArtLoad.Ok(forced) else loadArtSourceFromSystem(key)
            },
            encodeJpeg = { MusicArtCodec.encodeJpeg(it) },
            log = { message -> addLog(message, "warning") },
        )
    }

    // ------------------------------------------------------------ 生命周期

    fun start(port: Int) {
        // 启动即装载磁盘上的插件（与 Go 的 app 层同一时机）：否则 /api/hydro/list 是空的，
        // webui 也会 404
        runtime.reload()
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true }
        }
        this.executor = pool
        var fellBack = false
        val server = try {
            HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        } catch (e: Exception) {
            if (port != 0) {
                fellBack = true
                println("[Server] 端口 $port 绑定失败 (${e.message})，尝试随机可用端口...")
                HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
            } else {
                throw e
            }
        }
        server.createContext("/") { exchange -> serve(exchange) }
        server.executor = pool
        server.start()
        this.port = server.address.port
        this.http = server
        // 配置端口被占用时退到随机端口：明确留痕（配置里的端口保持不变，下次仍按用户设置尝试）
        if (fellBack) {
            addLog("端口 $port 被占用，本次临时使用 ${this.port}（配置仍为 $port，下次启动仍会尝试）", "warning")
        } else {
            addLog("服务端已启动，监听端口 ${this.port}", "success")
        }
    }

    /**
     * 即时重启并应用新端口：自动保存至配置，无缝切换监听，失败时自动保持原端口不崩溃。
     */
    fun restart(newPort: Int): Boolean {
        if (newPort < 1 || newPort > 65535) return false
        if (newPort == this.port && http != null) return true

        val oldHttp = this.http
        val oldPort = this.port
        return try {
            val newServer = HttpServer.create(InetSocketAddress("0.0.0.0", newPort), 0)
            newServer.createContext("/") { exchange -> serve(exchange) }
            newServer.executor = this.executor
            newServer.start()

            oldHttp?.stop(0)
            this.http = newServer
            this.port = newServer.address.port

            // 保存生效的新端口至配置文件
            try {
                store.saveConfig(store.loadConfig().copy(lastPort = this.port))
            } catch (e: Exception) {
                println("[Server] 保存端口配置失败: ${e.message}")
            }
            addLog("端口已切换至 ${this.port} 并已自动应用", "success")
            println("[Server] 端口已成功切换至 ${this.port} 并已应用")
            true
        } catch (e: Exception) {
            addLog("端口 $newPort 切换失败: ${e.message}，保持原端口 $oldPort", "error")
            println("[Server] 端口 $newPort 切换失败: ${e.message}，保持原端口 $oldPort")
            false
        }
    }

    fun stop() {
        runtime.stop()
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
    }

    // ------------------------------------------------------------ 分发

    private fun serve(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod
            if (method == "OPTIONS") {
                addCors(exchange)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return
            }
            var path = exchange.requestURI.path
            // 去掉尾部斜杠（根路径除外）——与 Go 一致
            if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)

            when {
                // 插件 WebUI 静态资源：从插件包的 web/ 目录出
                path.startsWith("/api/hydro/webui/") && method == "GET" ->
                    handleWebUi(exchange, path.removePrefix("/api/hydro/webui/"))

                // worker 通道排在插件分发之前，免得有个插件叫 "worker" 把通道挡了
                path.startsWith("/api/hydro/worker/") -> handleWorker(exchange, path, method)

                path == "/api/hydro/reload" -> handleReload(exchange, method)

                path == "/api/hydro/proxy" -> handleProxy(exchange)

                path == "/api/hydro" || path.startsWith("/api/hydro/") -> {
                    // /api/hydro* 一律不缓存（手表 WebView 会缓存）
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    handleHydro(exchange, path, method)
                }

                // ---- 公开端点 ----
                (path == "/api/ping" || path == "/ping") && method == "GET" ->
                    writeJson(exchange, 200, buildJsonObject {
                        put("status", "ok")
                    })

                (path == "/api/version" || path == "/version") && method == "GET" ->
                    // app 是给局域网探测用的指纹（Go 侧取代过 api_level）
                    writeJson(exchange, 200, buildJsonObject {
                        put("status", "ok")
                        put("app", "hydrolink")
                        put("version", version)
                    })

                (path == "/api/pid" || path == "/pid") && method == "GET" ->
                    writeJson(exchange, 200, buildJsonObject {
                        put("status", "ok")
                        put("pid", ProcessHandle.current().pid())
                    })

                (path == "/api/pair" || path == "/pair") && method == "POST" ->
                    handlePair(exchange)

                path.startsWith("/api/check/") && method == "GET" ->
                    handleCheck(exchange, path.removePrefix("/api/check/"))

                path.startsWith("/check/") && method == "GET" ->
                    handleCheck(exchange, path.removePrefix("/check/"))

                (path == "/api/auth" || path == "/auth") && method == "GET" ->
                    handleAuth(exchange)

                (path == "/api/pending_pair" || path == "/pending_pair") && method == "GET" ->
                    writeJson(exchange, 200, buildJsonObject {
                        put("status", "ok")
                        put("pairs", pairsToJson(store.loadPendingPairs()))
                    })

                (path == "/api/pair/respond" || path == "/pair/respond") && method == "POST" ->
                    handleRespondPair(exchange)

                // ---- 需要信任的端点 ----
                (path == "/api/status" || path == "/status") && method == "GET" ->
                    withTrusted(exchange) { handleStatus(exchange) }

                (path == "/api/lock" || path == "/lock") && method == "POST" ->
                    withTrusted(exchange) { handleLock(exchange) }

                (path == "/api/performance" || path == "/performance") && method == "GET" ->
                    withTrusted(exchange) { handlePerformance(exchange) }

                (path == "/api/music/art" || path == "/music/art") && method == "GET" ->
                    handleMusicArt(exchange)

                (path == "/api/music" || path == "/music") && method == "GET" ->
                    withTrusted(exchange) { handleGetMusic(exchange) }

                (path == "/api/music/control" || path == "/music/control") && method == "POST" ->
                    withTrusted(exchange) { handleMusicControl(exchange) }

                // ---- 桌面 UI 侧的管理端点 ----
                path == "/api/plugins/install" && method == "POST" ->
                    handleInstallPlugin(exchange)

                path.startsWith("/api/plugins/") && method == "DELETE" ->
                    handleUninstallPlugin(exchange, path.removePrefix("/api/plugins/"))

                path == "/api/logs" && method == "GET" -> handleGetLogs(exchange)

                path == "/api/devices" && method == "GET" -> handleGetDevices(exchange)

                path.startsWith("/api/devices/") && method == "DELETE" ->
                    handleDeleteDevice(exchange, path.removePrefix("/api/devices/"))

                path == "/api/settings" && method == "GET" -> handleGetSettings(exchange)

                path == "/api/settings" && method == "PUT" -> handlePutSettings(exchange)

                // 禁止通过 HTTP 增删改快捷命令（防插件 WebUI 或外部请求注入命令；快捷命令仅由原生 UI 直接操作 store）
                // path == "/api/commands" && method == "POST" -> handleCreateCommand(exchange)
                // matchCommandId(path) && method == "PUT" ->
                //     handleUpdateCommand(exchange, commandId(path))
                // matchCommandId(path) && method == "DELETE" ->
                //     handleDeleteCommand(exchange, commandId(path))

                (path == "/api/commands" || path == "/commands") && method == "GET" ->
                    withTrusted(exchange) { handleGetCommands(exchange) }

                matchRunCommand(path) && method == "POST" ->
                    withTrusted(exchange) { handleRunCommand(exchange, runCommandId(path)) }

                // ---- 根路径 / 健康检查 ----
                path == "/" || path == "" -> writeJson(exchange, 200, buildJsonObject {
                    put("status", "ok")
                    put("service", "HydroLinkServer")
                    put("version", version)
                })

                else -> writeJson(exchange, 404, buildJsonObject {
                    put("status", "error")
                    put("message", "Not found")
                })
            }
        } catch (e: Exception) {
            // 兜底：Go 侧是 net/http 自己兜，这里不能因为一个 handler 抛异常就把连接挂住
            runCatching { writeJson(exchange, 500, error("Internal error")) }
            addLog("serve: ${e.message}", "error")
        } finally {
            runCatching { exchange.close() }
        }
    }

    // ------------------------------------------------------------ 插件 API

    /** `/api/hydro/list` 与 `/api/hydro/{pkg}/{surface}/{item}/{action}`。 */
    private fun handleHydro(exchange: HttpExchange, path: String, method: String) {
        if (method != "GET" && method != "POST") {
            // Go 侧用的是 http.Error：纯文本 + 默认状态码
            text(exchange, 405, "method not allowed")
            return
        }
        val rest = path.removePrefix("/api/hydro").trim('/')
        if (rest == "list") {
            val body = runtime.listResponse(baseUrlProvider())
            raw(exchange, 200, "application/json", body.toByteArray(Charsets.UTF_8))
            return
        }
        val parts = rest.split("/")
        if (parts.size != 4) {
            text(exchange, 400, "expected /api/hydro/{package}/{surface}/{item}/{action}")
            return
        }
        val (pkg, surface, item, action) = parts
        val plugin = runtime.lookup(pkg)
        val entry = when (surface) {
            "app" -> plugin?.apps?.firstOrNull { it.id == item }
            "widget" -> plugin?.widgets?.firstOrNull { it.id == item }
            else -> null
        }
        // 错误文案照 Go 的 Dispatch：包不存在、surface 不认识、app/widget 不存在是三种不同的话
        if (plugin == null) {
            text(exchange, 404, "hydrokit: unknown plugin package \"$pkg\"")
            return
        }
        if (surface != "app" && surface != "widget") {
            text(exchange, 404, "hydrokit: unknown surface \"$surface\"")
            return
        }
        if (entry == null) {
            text(
                exchange, 404,
                "hydrokit: plugin \"$pkg\" has no ${if (surface == "app") "app" else "widget"} \"$item\"",
            )
            return
        }
        val trigger = when (action) {
            "view" -> "open"
            "refresh" -> "refresh"
            "event" -> "event"
            else -> action
        }
        // 事件体必须进入 worker 请求；仅设置 trigger 会丢失 handler，导致按钮无效。
        // 请求体只读一次，event / params / store 共用这次解析结果。
        val eventBody = if (action == "event") {
            try {
                val parsed = json.parseToJsonElement(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
                if (parsed != JsonNull && parsed !is JsonObject) throw IllegalArgumentException("invalid event body")
                val body = parsed as? JsonObject
                for (field in listOf("event", "params", "store")) {
                    val value = body?.get(field)
                    if (value != null && value != JsonNull && value !is JsonObject) {
                        throw IllegalArgumentException("invalid $field")
                    }
                }
                body
            } catch (e: Exception) {
                text(exchange, 400, "invalid event body")
                return
            }
        } else null
        // 请求上下文：与 Go 的 handler.go 同构（os/arch/app + 屏幕参数 + 存储快照）
        val request = buildString {
            append("{\"trigger\":${jsonString(trigger)}")
            append(",\"host\":{\"os\":\"${osName()}\",\"arch\":\"${arch()}\",\"app\":${jsonString(version)}}")
            append(",\"params\":${eventBody?.get("params") as? JsonObject ?: JsonObject(emptyMap())}")
            (eventBody?.get("event") as? JsonObject)?.let { append(",\"event\":$it") }
            val query = parseQuery(exchange.requestURI.rawQuery)
            append(",\"screen\":{\"shape\":${jsonString(query["shape"].orEmpty())}")
            append(",\"width\":${query["width"]?.toIntOrNull() ?: 0}")
            append(",\"height\":${query["height"]?.toIntOrNull() ?: 0}")
            append(",\"dpr\":${query["dpr"]?.toDoubleOrNull() ?: 0.0}")
            append(",\"deviceType\":${jsonString(query["dt"].orEmpty())}}")
            // 设备侧存储快照：三条通道都收（头 → 查询参数 → 请求体）
            val snapshot = readStoreSnapshot(exchange, query)
                ?: (eventBody?.get("store") as? JsonObject)?.toString()
            append(",\"store\":${snapshot ?: "null"}}")
        }
        try {
            // JS 插件：请求转发给浏览器侧 worker（宿主负责真正执行）
            val view = runtime.bridge.call(pkg, surface, item, request)
            raw(exchange, 200, "application/json", (view.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Go 侧插件没返回 view 时是 204 No Content；worker 离线/超时算服务端问题
            val message = e.message.orEmpty()
            if (message.contains("offline")) {
                text(exchange, 503, message)
            } else if (message.contains("超时")) {
                text(exchange, 504, message)
            } else {
                text(exchange, 500, message)
            }
        }
    }

    /** 设备侧存储快照：X-Hydro-Store 头 → store 查询参数 → 请求体。 */
    private fun readStoreSnapshot(exchange: HttpExchange, query: Map<String, String>): String? {
        val fromHeader = exchange.requestHeaders.getFirst("X-Hydro-Store")
        if (!fromHeader.isNullOrBlank()) normalizeJsonObject(fromHeader)?.let { return it }
        query["store"]?.let { value -> normalizeJsonObject(value)?.let { return it } }
        return null
    }

    private fun normalizeJsonObject(text: String): String? = try {
        val element = json.parseToJsonElement(text)
        if (element is JsonObject) text else null
    } catch (e: Exception) {
        null
    }

    /** worker 通道：poll（长轮询）与 respond。 */
    private fun handleWorker(exchange: HttpExchange, path: String, method: String) {
        val rest = path.removePrefix("/api/hydro/worker/")
        val query = parseQuery(exchange.requestURI.rawQuery)
        when {
            rest == "poll" && method == "GET" -> {
                val pkg = query["package"].orEmpty()
                if (pkg.isBlank()) {
                    text(exchange, 400, "missing package")
                    return
                }
                val timeout = (query["timeout"]?.toLongOrNull() ?: 25_000L)
                val payload = runtime.bridge.poll(pkg, timeout)
                val bytes = (payload + "\n").toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            rest == "respond" && method == "POST" -> {
                val bodyText = try {
                    exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                } catch (e: Exception) {
                    ""
                }
                val obj = try {
                    json.parseToJsonElement(bodyText) as? JsonObject
                } catch (e: Exception) {
                    null
                }
                if (obj == null) {
                    text(exchange, 400, "invalid body")
                    return
                }
                val pkg = (obj["package"] as? JsonPrimitive)?.content.orEmpty()
                val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val resultJson = obj["result"]?.toString()
                val errorMsg = (obj["error"] as? JsonPrimitive)?.content
                when (runtime.bridge.respond(pkg, id, resultJson, errorMsg)) {
                    WorkerBridge.RespondResult.UnknownPackage ->
                        writeJson(exchange, 200, buildJsonObject { put("status", "unknown_package") })
                    WorkerBridge.RespondResult.UnknownReq ->
                        writeJson(exchange, 200, buildJsonObject { put("status", "unknown_req") })
                    WorkerBridge.RespondResult.Ok ->
                        writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
                }
            }

            rest == "asset" && method == "GET" -> {
                val pkg = query["package"].orEmpty()
                val rel = query["path"].orEmpty()
                when (val result = runtime.readPluginFile(pkg, rel)) {
                    is PluginRuntime.FileResult.IllegalPath ->
                        // asset 端点区分 400，且正文是 JSON（Go 的 writeWorkerError）
                        writeJson(exchange, 400, buildJsonObject { put("error", "非法路径") })
                    is PluginRuntime.FileResult.Found ->
                        raw(exchange, 200, contentTypeOf(rel), result.file.readBytes())
                    else -> writeJson(exchange, 404, buildJsonObject {
                        put("error", "asset 不存在")
                    })
                }
            }

            // 宿主页（worker.html / worker.js）：桌面端的隐藏浏览器加载它跑胶水。
            // 与安卓端 HostPageRoutes 同一套语义：白名单两个文件名、非法名 400 JSON。
            rest == "host" && method == "GET" -> {
                val file = query["file"]?.takeIf { it.isNotBlank() } ?: "worker.html"
                if (file !in HOST_FILES) {
                    writeJson(exchange, 400, buildJsonObject { put("error", "不允许的宿主文件") })
                    return
                }
                // 宿主页 URL 上只带 package+base 时，entry/runtime 由服务端按清单补齐：
                // 胶水是从 location.search 读配置的（与安卓端同一份文件），补齐用 302 而不是
                // 改写 HTML，是为了让两端跑的是**逐字节相同**的 worker.html。
                val pkg = query["package"].orEmpty()
                if (file == "worker.html" && pkg.isNotBlank() &&
                    (query["entry"] == null || query["runtime"] == null)
                ) {
                    val info = runtime.lookup(pkg)
                    if (info != null) {
                        val filled = buildString {
                            append("/api/hydro/worker/host?file=worker.html")
                            append("&package=").append(urlEncode(pkg))
                            append("&base=").append(urlEncode(query["base"].orEmpty()))
                            append("&entry=").append(urlEncode(info.entry.ifBlank { "server.js" }))
                            append("&runtime=").append(urlEncode(info.runtime.ifBlank { "js" }))
                        }
                        exchange.responseHeaders.set("Location", filled)
                        exchange.sendResponseHeaders(302, -1)
                        exchange.close()
                        return
                    }
                }
                val bytes = hostFileBytes(file)
                if (bytes == null) {
                    writeJson(exchange, 404, buildJsonObject { put("error", "宿主文件缺失: $file") })
                } else {
                    val ct = if (file.endsWith(".html")) {
                        "text/html; charset=utf-8"
                    } else {
                        "application/javascript; charset=utf-8"
                    }
                    raw(exchange, 200, ct, bytes)
                }
            }

            rest == "platform" && method == "GET" -> writeJson(exchange, 200, buildJsonObject {
                put("os", osName())
                put("arch", arch())
                put("app", version)
            })

            rest == "store" && method == "POST" -> handleKitStorage(exchange)
            rest == "context" && method == "POST" -> handleKitContext(exchange)
            rest == "system" && method == "POST" -> handleKitSystem(exchange)
            rest == "notify" && method == "POST" -> handleKitNotify(exchange)
            rest == "music" && method == "POST" -> handleKitMusic(exchange)
            rest == "lock" && method == "POST" -> handleKitLock(exchange)
            rest == "commands" && method == "POST" -> handleKitCommands(exchange)
            rest == "wol/wake" && method == "POST" -> handleKitWolWake(exchange)
            rest == "wol/probe" && method == "POST" -> handleKitWolProbe(exchange)
            rest == "udp/send" && method == "POST" -> handleKitUdpSend(exchange)
            rest == "input" && method == "POST" -> handleKitInput(exchange)
            (rest == "tcp" || rest.startsWith("tcp/")) && method == "POST" -> handleKitTcp(exchange, rest)
            (rest == "ssh" || rest.startsWith("ssh/")) && method == "POST" -> handleKitSsh(exchange, rest)

            // 未知 worker 端点：Go 是纯文本 404（http.Error）
            else -> text(exchange, 404, "worker endpoint not found")
        }
    }

    // ---- kit 能力端点（全部按插件清单的权限门控）----

    /** 权限门控：清单不可读 403「plugin 不存在或清单不可读」，缺权限 403「权限不足: xxx」。 */
    private fun requirePermission(exchange: HttpExchange, pkg: String, perm: String): Boolean {
        val manifest = runtime.pluginStore.manifestOf(pkg)
        if (manifest == null) {
            workerError(exchange, 403, "plugin 不存在或清单不可读")
            return false
        }
        if (perm in manifest.permissions) return true
        workerError(exchange, 403, "权限不足: $perm")
        return false
    }

    private fun workerError(exchange: HttpExchange, code: Int, message: String) {
        writeJson(exchange, code, buildJsonObject { put("error", message) })
    }

    /** kit.storage（server 作用域；watch 作用域由容器胶水本地处理）。 */
    private fun handleKitStorage(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "storage")) return
        val scope = body.string("scope").ifEmpty { "server" }
        if (scope != "server") {
            workerError(exchange, 400, "watch 作用域由容器处理")
            return
        }
        val kv = store.loadPluginKV(pkg)
        when (val op = body.string("op")) {
            "get" -> {
                val value: JsonElement? = kv[body.string("key")]
                writeJson(exchange, 200, buildJsonObject {
                    put("found", value != null)
                    // 没找到时也要带 value:null（Go 那边 map 里的 nil 值照样序列化成 null）
                    put("value", value ?: JsonNull)
                })
            }
            "set" -> {
                kv[body.string("key")] = body["value"] ?: JsonNull
                store.savePluginKV(pkg, kv)
                writeJson(exchange, 200, buildJsonObject { put("ok", true) })
            }
            "remove" -> {
                kv.remove(body.string("key"))
                store.savePluginKV(pkg, kv)
                writeJson(exchange, 200, buildJsonObject { put("ok", true) })
            }
            "clear" -> {
                store.savePluginKV(pkg, emptyMap())
                writeJson(exchange, 200, buildJsonObject { put("ok", true) })
            }
            else -> workerError(exchange, 400, "未知操作: $op")
        }
    }

    /** ABI v1 上下文：插件拿时间/宿主/（按权限）性能、音乐、存储快照。 */
    private fun handleKitContext(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        val manifest = runtime.pluginStore.manifestOf(pkg)
        if (manifest == null) {
            workerError(exchange, 403, "plugin 不存在或清单不可读")
            return
        }
        val has = { perm: String -> perm in manifest.permissions }
        val now = java.time.ZonedDateTime.now()
        val ctx = buildJsonObject {
            put("abi", 1)
            put("package", pkg)
            put("surface", body.string("surface"))
            put("item", body.string("item"))
            put("time", buildJsonObject {
                put("unix", now.toEpochSecond())
                put("iso", now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                put("hour", now.hour)
                put("minute", now.minute)
            })
            val b = baseUrlProvider().trim()
            val normalizedBase = when {
                b.isBlank() -> ""
                b.startsWith("http://") || b.startsWith("https://") -> b
                else -> "http://$b"
            }
            put("server", buildJsonObject { put("base", normalizedBase) })
            put("host", buildJsonObject {
                put("os", osName())
                put("arch", arch())
                put("app", version)
            })
            body["req"]?.takeIf { it !is JsonNull }?.let { put("req", it) }
            if (has("system")) {
                val perf = SystemCapabilities.performance()
                put("system", buildJsonObject {
                    put("cpu", perf.cpu)
                    put("memPercent", perf.memPercent)
                    put("memUsedMB", perf.memUsedMb)
                    put("memTotalMB", perf.memTotalMb)
                })
            }
            if (has("music")) put("music", musicNow())
            if (has("storage") || has("system") || has("music")) {
                put("storage", store.loadPluginKV(pkg).toJson())
            }
        }
        writeJson(exchange, 200, ctx)
    }

    private fun handleKitSystem(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        if (!requirePermission(exchange, body.string("package"), "system")) return
        val op = body.string("op")
        if (op.isEmpty() || op == "perf") {
            val perf = SystemCapabilities.performance()
            writeJson(exchange, 200, buildJsonObject {
                put("cpu", perf.cpu)
                put("memPercent", perf.memPercent)
                put("memUsedMB", perf.memUsedMb)
                put("memTotalMB", perf.memTotalMb)
            })
            return
        }
        workerError(exchange, 400, "未知操作: $op")
    }

    private fun handleKitNotify(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        if (!requirePermission(exchange, body.string("package"), "notify")) return
        if (!SystemCapabilities.notify(body.string("title"), body.string("text"))) {
            // 与安卓端统一：通知失败回 500，不静默报成功
            workerError(exchange, 500, "通知失败")
            return
        }
        writeJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleKitMusic(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        if (!requirePermission(exchange, body.string("package"), "music")) return
        if (body.string("op") == "now") {
            writeJson(exchange, 200, musicNow())
            return
        }
        val action = body.string("action")
        if (action !in setOf("play", "pause", "play_pause", "next", "previous")) {
            workerError(exchange, 400, "无效动作: $action")
            return
        }
        if (!SystemCapabilities.musicControl(action)) {
            workerError(exchange, 500, "没有活动的媒体会话")
            return
        }
        writeJson(exchange, 200, buildJsonObject {
            put("ok", true)
            put("action", action)
        })
    }

    private fun handleKitLock(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        if (!requirePermission(exchange, body.string("package"), "lock")) return
        if (!SystemCapabilities.lockScreen()) {
            workerError(exchange, 500, "锁屏失败")
            return
        }
        writeJson(exchange, 200, buildJsonObject { put("ok", true) })
    }

    private fun handleKitCommands(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        if (!requirePermission(exchange, body.string("package"), "commands")) return
        val commands = store.loadCommands()
        when (body.string("op")) {
            "list" -> writeJson(exchange, 200, buildJsonObject {
                put("commands", buildJsonArray {
                    commands.forEachIndexed { index, command ->
                        add(buildJsonObject {
                            put("id", index)
                            put("name", command.name)
                            put("command", command.command)
                        })
                    }
                })
            })
            "run" -> {
                val index = body.int("index")
                if (index !in commands.indices) {
                    workerError(exchange, 400, "无效命令序号")
                    return
                }
                val result = runShell(commands[index].command)
                // 非 0 退出码是命令自己的结果，按 200 + returncode 回；-1 才是宿主级失败
                if (result.exitCode == -1) {
                    workerError(exchange, 500, "命令执行失败: ${result.output.take(200)}")
                    return
                }
                writeJson(exchange, 200, buildJsonObject {
                    put("ok", true)
                    put("returncode", result.exitCode)
                    put("output", result.output.take(1000))
                })
            }
            else -> workerError(exchange, 400, "未知操作: ${body.string("op")}")
        }
    }

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")

    private fun handleKitWolWake(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "wol")) return
        val mac = body.string("mac").trim()
        if (!MAC_REGEX.matches(mac)) {
            workerError(exchange, 400, "无效的 MAC 地址")
            return
        }
        val host = body.string("host").takeIf { it.isNotBlank() } ?: "255.255.255.255"
        val port = body.int("port").takeIf { it in 1..65535 } ?: 9

        val macBytes = try {
            val parts = mac.split(":", "-")
            if (parts.size != 6) {
                workerError(exchange, 400, "无效的 MAC 地址")
                return
            }
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            workerError(exchange, 400, "MAC 解析失败")
            return
        }

        val packetBytes = ByteArray(102)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)

        try {
            val targetAddr = java.net.InetAddress.getByName(host)
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = java.net.DatagramPacket(packetBytes, packetBytes.size, targetAddr, port)
                socket.send(packet)
            }
            writeJson(exchange, 200, buildJsonObject { put("ok", true) })
        } catch (e: Exception) {
            workerError(exchange, 500, e.message ?: "发送 WOL 失败")
        }
    }

    private fun handleKitWolProbe(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "wol")) return
        val host = body.string("host").trim()
        if (host.isBlank()) {
            workerError(exchange, 400, "缺少 host")
            return
        }
        val port = body.int("port").takeIf { it in 1..65535 }
        val timeout = body.int("timeout").takeIf { it > 0 }?.coerceIn(100, 10000) ?: 1000

        val start = System.currentTimeMillis()
        var online = false
        try {
            if (port != null && port > 0) {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), timeout)
                    online = true
                }
            } else {
                online = java.net.InetAddress.getByName(host).isReachable(timeout)
            }
        } catch (_: Exception) {
            online = false
        }
        val elapsed = System.currentTimeMillis() - start
        writeJson(exchange, 200, buildJsonObject {
            put("online", online)
            put("rttMs", elapsed)
        })
    }

    private fun handleKitUdpSend(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "udp")) return
        val host = body.string("host").trim()
        if (host.isBlank()) {
            workerError(exchange, 400, "缺少 host")
            return
        }
        val port = body.int("port")
        if (port !in 1..65535) {
            workerError(exchange, 400, "无效端口")
            return
        }
        val rawData = body.string("data")
        val encoding = body.string("encoding").lowercase().ifBlank { "utf-8" }
        val bytes = try {
            when (encoding) {
                "utf-8", "utf8" -> rawData.toByteArray(Charsets.UTF_8)
                "base64" -> java.util.Base64.getDecoder().decode(rawData)
                "hex" -> hexToBytes(rawData)
                else -> {
                    workerError(exchange, 400, "不支持的编码: $encoding")
                    return
                }
            }
        } catch (e: Exception) {
            workerError(exchange, 400, "数据解码失败")
            return
        }
        val broadcast = if (body.has("broadcast")) {
            body.bool("broadcast")
        } else {
            host == "255.255.255.255" || host.endsWith(".255")
        }

        try {
            val targetAddr = java.net.InetAddress.getByName(host)
            java.net.DatagramSocket().use { socket ->
                if (broadcast) socket.broadcast = true
                val packet = java.net.DatagramPacket(bytes, bytes.size, targetAddr, port)
                socket.send(packet)
            }
            writeJson(exchange, 200, buildJsonObject {
                put("ok", true)
                put("bytesSent", bytes.size)
            })
        } catch (e: Exception) {
            workerError(exchange, 500, e.message ?: "发送 UDP 失败")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(" ", "").replace(":", "")
        require(s.length % 2 == 0) { "Hex length must be even" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun handleKitInput(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "input")) return

        val op = body.string("op")
        val res: Map<String, Any?> = when (op) {
            "screen" -> SystemCapabilities.inputScreen()
            "cursor" -> SystemCapabilities.inputCursor()
            "move" -> {
                val x = body.floatOrNull("x") ?: run {
                    workerError(exchange, 400, "缺少 x 坐标")
                    return
                }
                val y = body.floatOrNull("y") ?: run {
                    workerError(exchange, 400, "缺少 y 坐标")
                    return
                }
                SystemCapabilities.inputMove(x, y)
            }
            "move_by" -> {
                val dx = body.floatOrNull("dx") ?: 0f
                val dy = body.floatOrNull("dy") ?: 0f
                SystemCapabilities.inputMoveBy(dx, dy)
            }
            "click", "tap" -> {
                val x = body.floatOrNull("x")
                val y = body.floatOrNull("y")
                val button = body.string("button").ifBlank { "left" }
                val count = body.int("count").takeIf { it > 0 } ?: 1
                SystemCapabilities.inputClick(x, y, button, count)
            }
            "drag", "swipe" -> {
                val x1 = body.floatOrNull("x1") ?: run {
                    workerError(exchange, 400, "缺少 x1 坐标")
                    return
                }
                val y1 = body.floatOrNull("y1") ?: run {
                    workerError(exchange, 400, "缺少 y1 坐标")
                    return
                }
                val x2 = body.floatOrNull("x2") ?: run {
                    workerError(exchange, 400, "缺少 x2 坐标")
                    return
                }
                val y2 = body.floatOrNull("y2") ?: run {
                    workerError(exchange, 400, "缺少 y2 坐标")
                    return
                }
                val duration = (body.longOrNull("duration") ?: 300L).coerceIn(10L, 10000L)
                SystemCapabilities.inputDrag(x1, y1, x2, y2, duration)
            }
            "scroll" -> {
                val dx = body.floatOrNull("dx") ?: 0f
                val dy = body.floatOrNull("dy") ?: 0f
                SystemCapabilities.inputScroll(dx, dy)
            }
            "key" -> {
                val key = body.string("key")
                if (key.isBlank()) {
                    workerError(exchange, 400, "缺少 key")
                    return
                }
                SystemCapabilities.inputKey(key)
            }
            "shortcut" -> {
                val arr = body["keys"] as? JsonArray
                if (arr == null || arr.isEmpty()) {
                    workerError(exchange, 400, "缺少 keys")
                    return
                }
                val keys = arr.mapNotNull { (it as? JsonPrimitive)?.content }
                SystemCapabilities.inputShortcut(keys)
            }
            "text" -> {
                val text = body.string("text")
                SystemCapabilities.inputText(text)
            }
            else -> {
                workerError(exchange, 400, "未知操作: $op")
                return
            }
        }

        if (res.containsKey("error")) {
            writeJson(exchange, 500, mapToJson(res))
        } else {
            writeJson(exchange, 200, mapToJson(res))
        }
    }

    /** kit.music.now() 与 ctx.music 的公共实现（含手表可达的封面 URL）。 */
    private fun musicNow(): JsonObject {
        val info = SystemCapabilities.music()
        // 与安卓端同构：只有真有封面时才给地址，没有就给空串（插件据此退回纯色/占位，
        // 而不是拿一个必然 404 的地址反复重试）。形状由 musicJson 钉住。
        val hasArt = (musicArtOverride?.key ?: info.artUrl).isNotBlank()
        return musicJson(info, baseUrlProvider(), hasArt)
    }

    private fun Map<String, JsonElement>.toJson(): JsonObject = buildJsonObject {
        for ((k, v) in this@toJson) put(k, v)
    }

    private fun extractId(body: String): Long? =
        Regex("\"id\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()

    /** 插件 WebUI：`/api/hydro/webui/<pkg>/index.html` 与同目录下的其它文件。 */
    private fun handleWebUi(exchange: HttpExchange, rest: String) {
        // 缺插件 ID 是 400「缺少插件 ID」；文件不存在走 http.NotFound（正文 "404 page not found"）
        val pkg = rest.substringBefore('/')
        if (pkg.isBlank()) {
            text(exchange, 400, "缺少插件 ID")
            return
        }
        var rel = rest.substringAfter('/', "")
        if (rel.isEmpty()) rel = "index.html"
        // 目录请求补 index.html
        if (rel.endsWith("/")) rel += "index.html"
        // Go 的 webui handler 把 ReadPluginFile 的**所有**错误都映射成 http.NotFound
        // （含路径逃逸），不区分 400/404——这里照抄，否则手表/插件页面会看到不同状态码
        when (val result = runtime.readPluginFile(pkg, "web/$rel")) {
            is PluginRuntime.FileResult.Found -> {
                val contentType = contentTypeOf(rel)
                if (contentType.startsWith("text/html")) {
                    val original = result.file.readText(Charsets.UTF_8)
                    val withSelect = WebUiSelectPolyfill.injectIntoHtml(original)
                    val injected = WebUiFetchPolyfill.injectIntoHtml(withSelect)
                    raw(exchange, 200, contentType, injected.toByteArray(Charsets.UTF_8))
                } else {
                    raw(exchange, 200, contentType, result.file.readBytes())
                }
            }
            else -> text(exchange, 404, "404 page not found")
        }
    }

    private val proxyHttpClient: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()
    }

    private val proxyNoRedirectClient: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()
    }

    /**
     * 插件 WebUI 透明代理端点：`/api/hydro/proxy?url=<targetUrl>`。
     *
     * 消除第三方 API（如 Bilibili 扫码与信息接口）因浏览器标准发送非白名单 `Origin` 请求头而被 WAF 拦截 403 的问题。
     * 前端通过 [WebUiFetchPolyfill] 透明重定向至此，由服务端向目标地址发起请求并原样透传回响应。
     */
    private fun handleProxy(exchange: HttpExchange) {
        val method = exchange.requestMethod
        if (method == "OPTIONS") {
            addCors(exchange)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }

        val query = parseQuery(exchange.requestURI.rawQuery)
        val targetUrl = query["url"]
        if (targetUrl.isNullOrBlank()) {
            writeJson(exchange, 400, buildJsonObject {
                put("status", "error")
                put("message", "missing url parameter")
            })
            return
        }

        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            writeJson(exchange, 400, buildJsonObject {
                put("status", "error")
                put("message", "only http and https schemes are supported")
            })
            return
        }

        if (!isLoopback(exchange)) {
            writeJson(exchange, 403, buildJsonObject {
                put("status", "error")
                put("message", "proxy access restricted to loopback")
            })
            return
        }

        try {
            val targetUri = java.net.URI.create(targetUrl)
            val reqBuilder = java.net.http.HttpRequest.newBuilder(targetUri)
                .timeout(java.time.Duration.ofSeconds(20))

            val hasBody = method in setOf("POST", "PUT", "PATCH")
            val bodyBytes = if (hasBody) exchange.requestBody.readBytes() else ByteArray(0)
            val publisher = if (hasBody) {
                java.net.http.HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
            } else {
                java.net.http.HttpRequest.BodyPublishers.noBody()
            }
            reqBuilder.method(method, publisher)

            val clientHeaders = exchange.requestHeaders
            val customCookie = clientHeaders.getFirst("X-Proxy-Cookie")
                ?: clientHeaders.getFirst("Cookie")
            val rawReferer = clientHeaders.getFirst("X-Proxy-Referer")
                ?: clientHeaders.getFirst("Referer")
            // 过滤掉本地回环 Referer（浏览器请求 /api/hydro/proxy 时自动携带的本地 WebUI 路径，外部 WAF 会识别并拦截）
            val isLoopbackReferer = rawReferer?.let { ref ->
                ref.contains("127.0.0.1") || ref.contains("localhost") || ref.contains("127.0.1.")
            } ?: false
            val customReferer = if (isLoopbackReferer) null else rawReferer

            val customUserAgent = clientHeaders.getFirst("X-Proxy-User-Agent")
                ?: clientHeaders.getFirst("User-Agent")

            val targetHost = targetUri.host?.lowercase().orEmpty()
            val isBilibili = targetHost.endsWith("bilibili.com")

            val effectiveReferer = when {
                !customReferer.isNullOrBlank() -> customReferer
                isBilibili -> "https://message.bilibili.com/"
                else -> null
            }
            if (!effectiveReferer.isNullOrBlank()) {
                reqBuilder.header("Referer", effectiveReferer)
            }

            val effectiveUserAgent = when {
                !customUserAgent.isNullOrBlank() -> customUserAgent
                else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
            reqBuilder.header("User-Agent", effectiveUserAgent)

            if (!customCookie.isNullOrBlank()) {
                reqBuilder.header("Cookie", customCookie)
            }

            val customAuth = clientHeaders.getFirst("X-Proxy-Authorization")
                ?: clientHeaders.getFirst("Authorization")
            if (!customAuth.isNullOrBlank()) {
                reqBuilder.header("Authorization", customAuth)
            }

            clientHeaders.getFirst("x-opencode-directory")?.takeIf { it.isNotBlank() }?.let {
                reqBuilder.header("x-opencode-directory", it)
            }

            clientHeaders.getFirst("Content-Type")?.takeIf { it.isNotBlank() }?.let {
                reqBuilder.header("Content-Type", it)
            }
            clientHeaders.getFirst("Accept")?.takeIf { it.isNotBlank() }?.let {
                reqBuilder.header("Accept", it)
            }

            val customOrigin = clientHeaders.getFirst("X-Proxy-Origin")
            if (!customOrigin.isNullOrBlank()) {
                reqBuilder.header("Origin", customOrigin)
            }

            val noRedirect = clientHeaders.getFirst("X-Proxy-No-Redirect")?.equals("true", ignoreCase = true) == true
            val client = if (noRedirect) proxyNoRedirectClient else proxyHttpClient
            val response = client.send(
                reqBuilder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray()
            )

            var respBytes = response.body() ?: ByteArray(0)
            val allSetCookies = response.headers().allValues("Set-Cookie")
            val cookieMap = extractCookies(allSetCookies)

            if (isBilibili && targetUri.path.contains("qrcode/poll") && response.statusCode() == 200) {
                try {
                    val respText = String(respBytes, Charsets.UTF_8)
                    val element = json.parseToJsonElement(respText)
                    if (element is JsonObject) {
                        val code = (element["data"] as? JsonObject)?.int("code") ?: element.int("code")
                        if (code == 0) {
                            val dataObj = element["data"] as? JsonObject
                            val dataUrl = dataObj?.string("url").orEmpty()

                            // 1. 如果 dataUrl query 中已有 SESSDATA，直接提取
                            if (dataUrl.contains("SESSDATA=")) {
                                runCatching {
                                    val u = java.net.URI.create(dataUrl)
                                    val q = parseQuery(u.rawQuery)
                                    q["SESSDATA"]?.let { cookieMap.putIfAbsent("SESSDATA", it) }
                                    q["bili_jct"]?.let { cookieMap.putIfAbsent("bili_jct", it) }
                                    q["DedeUserID"]?.let { cookieMap.putIfAbsent("DedeUserID", it) }
                                }
                            }

                            // 2. 若尚未拿到 SESSDATA 且存在 crossDomain ticket 链接（B站 2026 改版机制），执行完整票据兑换
                            if (cookieMap.findCookie("SESSDATA").isBlank() && dataUrl.startsWith("http")) {
                                exchangeBilibiliTicket(dataUrl, effectiveUserAgent, cookieMap)
                            }

                            val sessdata = cookieMap.findCookie("SESSDATA")
                            val biliJct = cookieMap.findCookie("bili_jct")
                            val dedeUserId = cookieMap.findCookie("DedeUserID").ifEmpty { cookieMap.findCookie("dede_user_id") }

                            if (sessdata.isNotBlank()) {
                                // 注入回 data.url 方便通过 URL query 读取的逻辑
                                val enrichedUrl = if (dataUrl.isNotBlank() && !dataUrl.contains("SESSDATA=")) {
                                    dataUrl + (if (dataUrl.contains("?")) "&" else "?") +
                                        "SESSDATA=" + urlEncode(sessdata) +
                                        "&bili_jct=" + urlEncode(biliJct) +
                                        "&DedeUserID=" + urlEncode(dedeUserId)
                                } else dataUrl

                                // 重构并丰富 JSON 响应正文中的 data 字段
                                val enrichedJson = buildJsonObject {
                                    element.forEach { (k, v) ->
                                        if (k == "data" && v is JsonObject) {
                                            put("data", buildJsonObject {
                                                v.forEach { (dk, dv) ->
                                                    if (dk == "url" && enrichedUrl.isNotBlank()) {
                                                        put("url", enrichedUrl)
                                                    } else {
                                                        put(dk, dv)
                                                    }
                                                }
                                                put("sessdata", sessdata)
                                                put("SESSDATA", sessdata)
                                                put("bili_jct", biliJct)
                                                put("dede_user_id", dedeUserId)
                                                put("DedeUserID", dedeUserId)
                                            })
                                        } else {
                                            put(k, v)
                                        }
                                    }
                                }
                                respBytes = enrichedJson.toString().toByteArray(Charsets.UTF_8)
                                exchange.responseHeaders.set("X-Proxy-Set-Cookie", "SESSDATA=$sessdata; bili_jct=$biliJct; DedeUserID=$dedeUserId")
                                println("[HydroServer] 成功为 B 站扫码登录提取并附注凭据: DedeUserID=$dedeUserId, SESSDATA=${sessdata.take(8)}...")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[HydroServer] 处理 B 站 poll 凭证响应异常: ${e.message}")
                }
            }

            addCors(exchange)
            exchange.responseHeaders.set("Access-Control-Expose-Headers", "*")
            val setCookieHeader = if (cookieMap.isNotEmpty()) {
                cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else {
                allSetCookies.joinToString("; ") { it.substringBefore(';') }
            }
            if (setCookieHeader.isNotBlank() && !exchange.responseHeaders.containsKey("X-Proxy-Set-Cookie")) {
                exchange.responseHeaders.set("X-Proxy-Set-Cookie", setCookieHeader)
            }
            response.headers().firstValue("Content-Type").ifPresent { ct ->
                exchange.responseHeaders.set("Content-Type", ct)
            }
            exchange.sendResponseHeaders(response.statusCode(), respBytes.size.toLong())
            exchange.responseBody.write(respBytes)
            exchange.responseBody.close()
        } catch (e: Exception) {
            writeJson(exchange, 502, buildJsonObject {
                put("status", "error")
                put("message", "proxy request failed: ${e.message}")
            })
        }
    }

    private fun extractCookies(cookies: List<String>): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        for (c in cookies) {
            val part = c.substringBefore(';')
            val eq = part.indexOf('=')
            if (eq > 0) {
                val k = part.substring(0, eq).trim()
                val v = part.substring(eq + 1).trim()
                if (k.isNotBlank() && v.isNotBlank()) {
                    map[k] = v
                }
            }
        }
        return map
    }

    private fun Map<String, String>.findCookie(name: String): String =
        this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    /**
     * 针对 B 站 2026 年新版 WEB 扫码登录票据链接（crossDomain ticket），完整模拟浏览器跟随重定向并兑换真实 Cookie 凭据。
     *
     * B 站新机制下，poll 端点返回的 data.url 是 crossDomain 票据链接，真正的 SESSDATA / bili_jct / DedeUserID
     * 是通过跟随该链接（302 重定向链及 SSO 页面）时由 Set-Cookie 下发的。
     */
    private fun exchangeBilibiliTicket(
        dataUrl: String,
        effectiveUserAgent: String,
        cookieMap: MutableMap<String, String>
    ) {
        val ticketUri = try { java.net.URI.create(dataUrl) } catch (e: Exception) { return }
        val queryParams = parseQuery(ticketUri.rawQuery)
        val ticket = queryParams["ticket"]
        val gourl = queryParams["gourl"] ?: "https://www.bilibili.com"

        // 建立带有 CookieManager 的 HttpClient，以自动捕获 302/307 重定向链中每一跳的 Set-Cookie
        val cookieManager = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        val client = java.net.http.HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(java.time.Duration.ofSeconds(12))
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .build()

        val candidateUrls = mutableListOf<String>()
        candidateUrls.add(dataUrl)
        if (!ticket.isNullOrBlank()) {
            val encodedGourl = urlEncode(gourl)
            candidateUrls.add("https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=$ticket&gourl=$encodedGourl")
            candidateUrls.add("https://passport.bilibili.com/x/passport-login/web/crossDomain?ticket=$ticket&gourl=$encodedGourl")
            candidateUrls.add("https://passport.biligame.com/crossDomain?ticket=$ticket&gourl=$encodedGourl")
        }

        for (url in candidateUrls) {
            try {
                val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", effectiveUserAgent)
                    .header("Referer", "https://passport.bilibili.com/")
                    .GET()
                    .build()
                val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())

                // 1. 从 cookieManager 收集整个重定向链产生的所有 Cookie
                for (c in cookieManager.cookieStore.cookies) {
                    if (c.name.isNotBlank() && c.value.isNotBlank()) {
                        cookieMap.putIfAbsent(c.name, c.value)
                    }
                }

                // 2. 从最终响应头补全 Set-Cookie
                extractCookies(resp.headers().allValues("Set-Cookie")).forEach { (k, v) ->
                    cookieMap.putIfAbsent(k, v)
                }

                // 3. 扫描响应体中可能包含的跨域 SSO 确认地址（bilibili / biligame sso）
                val body = resp.body().orEmpty()
                val ssoRegex = Regex("https?://[^\"'\\s<>]+")
                for (match in ssoRegex.findAll(body)) {
                    val ssoUrl = match.value.replace("\\u0026", "&").replace("&amp;", "&")
                    if (ssoUrl.contains("sso", ignoreCase = true) &&
                        (ssoUrl.contains("bilibili") || ssoUrl.contains("biligame"))) {
                        runCatching {
                            val ssoReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(ssoUrl))
                                .timeout(java.time.Duration.ofSeconds(8))
                                .header("User-Agent", effectiveUserAgent)
                                .header("Referer", "https://passport.bilibili.com/")
                                .GET()
                                .build()
                            val ssoResp = client.send(ssoReq, java.net.http.HttpResponse.BodyHandlers.ofString())
                            extractCookies(ssoResp.headers().allValues("Set-Cookie")).forEach { (k, v) ->
                                cookieMap.putIfAbsent(k, v)
                            }
                        }
                    }
                }

                // 再次从 cookieManager 收集 SSO 请求产生的 Cookie
                for (c in cookieManager.cookieStore.cookies) {
                    if (c.name.isNotBlank() && c.value.isNotBlank()) {
                        cookieMap.putIfAbsent(c.name, c.value)
                    }
                }

                if (cookieMap.findCookie("SESSDATA").isNotBlank()) {
                    println("[HydroServer] 成功通过票据兑换获取 SESSDATA: ${url.substringBefore('?')}")
                    break
                }
            } catch (e: Exception) {
                // 继续尝试下一个候选地址
            }
        }
    }

    private fun handleKitTcp(exchange: HttpExchange, rest: String) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "tcp")) return

        val op = if (rest.startsWith("tcp/")) {
            rest.removePrefix("tcp/").trim()
        } else {
            body.string("op").ifBlank { "write" }
        }

        try {
            when (op) {
                "connect", "open" -> {
                    val host = body.string("host").trim()
                    val port = body.int("port")
                    val timeout = body.int("timeout").takeIf { it > 0 } ?: 10_000
                    val res = com.hydro.link.server.net.TcpManager.connect(pkg, host, port, timeout)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("id", res.id)
                        put("remote", res.remote)
                    })
                }
                "read" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    val timeout = body.int("timeout").takeIf { it >= 0 } ?: 1000
                    val encoding = body.string("encoding").ifBlank { "utf-8" }
                    val res = com.hydro.link.server.net.TcpManager.read(pkg, id, timeout, encoding)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("data", res.data)
                        put("eof", res.eof)
                        put("bytesRead", res.bytesRead)
                    })
                }
                "write" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    val data = if (body.containsKey("data")) body.string("data") else body.string("text")
                    val encoding = body.string("encoding").ifBlank { "utf-8" }
                    val written = com.hydro.link.server.net.TcpManager.write(pkg, id, data, encoding)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("bytesWritten", written)
                    })
                }
                "close" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    com.hydro.link.server.net.TcpManager.close(pkg, id)
                    writeJson(exchange, 200, buildJsonObject { put("ok", true) })
                }
                "list" -> {
                    val list = com.hydro.link.server.net.TcpManager.list(pkg)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("connections", buildJsonArray {
                            list.forEach { item ->
                                add(buildJsonObject {
                                    item.forEach { (k, v) ->
                                        when (v) {
                                            is String -> put(k, v)
                                            is Number -> put(k, v.toLong())
                                            is Boolean -> put(k, v)
                                            else -> put(k, v.toString())
                                        }
                                    }
                                })
                            }
                        })
                    })
                }
                else -> workerError(exchange, 400, "未知 TCP 操作: $op")
            }
        } catch (e: Exception) {
            workerError(exchange, 500, e.message ?: "TCP 操作失败")
        }
    }

    private fun handleKitSsh(exchange: HttpExchange, rest: String) {
        val body = readBody(exchange) ?: run {
            workerError(exchange, 400, "invalid body")
            return
        }
        val pkg = body.string("package")
        if (!requirePermission(exchange, pkg, "ssh")) return

        val op = if (rest.startsWith("ssh/")) {
            rest.removePrefix("ssh/").trim()
        } else {
            body.string("op").ifBlank { "read" }
        }

        try {
            when (op) {
                "open", "connect" -> {
                    val host = body.string("host").trim()
                    val port = body.int("port").takeIf { it in 1..65535 } ?: 22
                    val user = body.string("user").ifBlank { body.string("username") }.trim()
                    val password = body.string("password").takeIf { it.isNotBlank() }
                    val agent = body.bool("agent")
                    val keyFile = body.string("keyFile").takeIf { it.isNotBlank() }
                    val privateKey = body.string("privateKey").takeIf { it.isNotBlank() }
                    val passphrase = body.string("passphrase").takeIf { it.isNotBlank() }
                    val fingerprint = body.string("fingerprint").takeIf { it.isNotBlank() }
                    val term = body.string("term").ifBlank { "xterm-256color" }
                    val cols = body.int("cols").takeIf { it > 0 } ?: 80
                    val rows = body.int("rows").takeIf { it > 0 } ?: 24
                    val timeout = body.int("timeout").takeIf { it > 0 } ?: 10_000

                    val params = com.hydro.link.server.net.SshManager.OpenParams(
                        host = host,
                        port = port,
                        user = user,
                        password = password,
                        agent = agent,
                        keyFile = keyFile,
                        privateKey = privateKey,
                        passphrase = passphrase,
                        fingerprint = fingerprint,
                        term = term,
                        cols = cols,
                        rows = rows,
                        timeoutMs = timeout,
                    )
                    val res = com.hydro.link.server.net.SshManager.open(pkg, params)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("id", res.id)
                        put("remote", res.remote)
                        put("fingerprint", res.fingerprint)
                        put("banner", res.banner)
                    })
                }
                "read" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    val timeout = body.int("timeout").takeIf { it >= 0 } ?: 1000
                    val encoding = body.string("encoding").ifBlank { "utf-8" }
                    val res = com.hydro.link.server.net.SshManager.read(pkg, id, timeout, encoding)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("data", res.data)
                        put("closed", res.closed)
                        res.exitCode?.let { put("exitCode", it) }
                        put("bytesRead", res.bytesRead)
                    })
                }
                "write" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    val data = if (body.containsKey("data")) body.string("data") else body.string("text")
                    val encoding = body.string("encoding").ifBlank { "utf-8" }
                    val written = com.hydro.link.server.net.SshManager.write(pkg, id, data, encoding)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("bytesWritten", written)
                    })
                }
                "resize" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    val cols = body.int("cols").takeIf { it > 0 } ?: 80
                    val rows = body.int("rows").takeIf { it > 0 } ?: 24
                    com.hydro.link.server.net.SshManager.resize(pkg, id, cols, rows)
                    writeJson(exchange, 200, buildJsonObject { put("ok", true) })
                }
                "close" -> {
                    val id = body.string("id").takeIf { it.isNotBlank() }
                    com.hydro.link.server.net.SshManager.close(pkg, id)
                    writeJson(exchange, 200, buildJsonObject { put("ok", true) })
                }
                "list" -> {
                    val list = com.hydro.link.server.net.SshManager.list(pkg)
                    writeJson(exchange, 200, buildJsonObject {
                        put("ok", true)
                        put("sessions", buildJsonArray {
                            list.forEach { item ->
                                add(buildJsonObject {
                                    item.forEach { (k, v) ->
                                        when (v) {
                                            is String -> put(k, v)
                                            is Number -> put(k, v.toLong())
                                            is Boolean -> put(k, v)
                                            else -> put(k, v.toString())
                                        }
                                    }
                                })
                            }
                        })
                    })
                }
                else -> workerError(exchange, 400, "未知 SSH 操作: $op")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "SSH 操作失败"
            workerError(exchange, 500, msg)
        }
    }

    private fun contentTypeOf(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
        name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".ico") -> "image/x-icon"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".txt") -> "text/plain; charset=utf-8"
        name.endsWith(".py") || name.endsWith(".sh") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            if (part.isEmpty()) null else {
                val idx = part.indexOf('=')
                if (idx < 0) decode(part) to "" else decode(part.substring(0, idx)) to
                    decode(part.substring(idx + 1))
            }
        }.toMap()
    }

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, Charsets.UTF_8)
    } catch (e: Exception) {
        text
    }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (ch in text) {
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

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.contains("mac") -> "darwin"
            it.contains("win") -> "windows"
            else -> "linux"
        }
    }

    private fun arch(): String = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").orEmpty()
    }

    // ------------------------------------------------------------ 公开端点

    private fun handlePair(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return
        }
        val deviceId = (body.string("device_id").ifBlank { body.string("deviceId") }).trim()
        if (deviceId.isEmpty() || deviceId.length > 100) {
            writeJson(exchange, 400, error("Invalid device_id"))
            return
        }
        // 设备名超长直接截断（Go 是按字节切，UTF-8 中文按字节切会切坏，这里按字符——
        // 差异只出现在 >50 字节的中文名上，parity 测试会覆盖到，届时按结论调整）
        val rawName = (body.string("device_name").ifBlank { body.string("deviceName") }).trim()
        val deviceName = rawName.let {
            if (it.toByteArray().size > 50) it.take(50) else it
        }
        val cfg = store.loadConfig()

        if (store.isTrusted(deviceId)) {
            if (deviceName.isNotBlank()) {
                store.addTrustedDevice(deviceId, deviceName)
            }
            addLog("设备 ${shortId(deviceId)} 已信任", "success")
            writeJson(exchange, 200, buildJsonObject {
                put("status", "trusted")
                put("message", "Device already trusted")
                put("encryption_key", cfg.encryptionKey)
            })
            return
        }

        // 新的配对请求 = 再给用户一次机会：先清掉之前的拒绝，别直接自动拒绝
        store.removeRejected(deviceId)

        if (cfg.acceptAll) {
            store.addTrustedDevice(deviceId, deviceName)
            addLog("自动信任设备: ${shortId(deviceId)} ($deviceName)", "success")
            writeJson(exchange, 200, buildJsonObject {
                put("status", "trusted")
                put("message", "Auto accepted")
                put("encryption_key", cfg.encryptionKey)
            })
            return
        }

        addLog("配对请求: ${shortId(deviceId)}", "warning")
        store.addPendingPair(deviceId, deviceName)
        // 关键修复：无论此前是否已记录在待处理列表，每次收到未信任设备的配对请求，
        // 都代表手表/客户端当前正在发起连接并等待授权，必须无条件唤醒 UI 弹窗与系统通知
        onPairRequested?.invoke(deviceId, deviceName)
        writeJson(exchange, 200, buildJsonObject {
            put("status", "pending")
            put("message", "Waiting for user confirmation")
        })
    }

    private fun handleCheck(exchange: HttpExchange, deviceId: String) {
        if (deviceId.isEmpty() || deviceId.length > 100) {
            writeJson(exchange, 400, buildJsonObject { put("status", "error") })
            return
        }
        when {
            store.isRejected(deviceId) -> writeJson(exchange, 200, buildJsonObject {
                put("status", "rejected")
            })
            store.isTrusted(deviceId) -> writeJson(exchange, 200, buildJsonObject {
                put("status", "trusted")
                put("encryption_key", store.loadConfig().encryptionKey)
            })
            else -> writeJson(exchange, 200, buildJsonObject { put("status", "pending") })
        }
    }

    private fun handleAuth(exchange: HttpExchange) {
        val deviceId = extractDeviceId(exchange)
        if (deviceId.isEmpty() || deviceId.length > 100) {
            writeJson(exchange, 400, error("Missing device ID"))
            return
        }
        writeJson(exchange, 200, buildJsonObject {
            put("status", if (store.isTrusted(deviceId)) "authorized" else "unauthorized")
        })
    }

    private fun handleRespondPair(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return
        }
        val deviceId = (body.string("device_id").ifBlank { body.string("deviceId") }).trim()
        val rawAction = body.string("action").trim().lowercase()
        val action = if (rawAction == "allow") "accept" else rawAction
        if (deviceId.isEmpty() || (action != "accept" && action != "reject")) {
            writeJson(exchange, 400, error("Invalid params"))
            return
        }
        val reqDeviceName = (body.string("device_name").ifBlank { body.string("deviceName") }).let {
            if (it.toByteArray().size > 50) it.take(50) else it
        }.trim()
        val pending = store.loadPendingPairs().firstOrNull { it.deviceId == deviceId }
        val deviceName = reqDeviceName.ifBlank { pending?.deviceName.orEmpty() }
        store.removePendingPair(deviceId)
        if (action == "accept") {
            store.addTrustedDevice(deviceId, deviceName)
            addLog("已信任设备: ${shortId(deviceId)} ($deviceName)", "success")
        } else {
            store.addRejected(deviceId)
            addLog("拒绝设备: ${shortId(deviceId)}", "error")
        }
        writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
    }

    private fun handleStatus(exchange: HttpExchange) {
        val devices = store.loadTrustedDevices()
        val battery = SystemCapabilities.battery()
        writeJson(exchange, 200, buildJsonObject {
            put("status", "running")
            put("trusted_count", devices.size)
            put("system", buildJsonObject {
                put("hostname", SystemCapabilities.hostname())
                put("version", SystemCapabilities.systemVersion())
                put("ip", SystemCapabilities.localIp())
            })
            put("battery", buildJsonObject {
                put("has_battery", battery.hasBattery)
                put("percent", battery.percent)
                put("charging", battery.charging)
            })
        })
    }

    private fun handleLock(exchange: HttpExchange) {
        if (SystemCapabilities.lockScreen()) {
            addLog("设备已锁屏", "success")
            writeJson(exchange, 200, buildJsonObject {
                put("status", "ok")
                put("message", "Screen locked")
            })
        } else {
            addLog("锁屏失败", "error")
            writeJson(exchange, 500, error("Lock failed"))
        }
    }

    private fun handlePerformance(exchange: HttpExchange) {
        val perf = SystemCapabilities.performance()
        writeJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("cpu", perf.cpu)
            put("mem_percent", perf.memPercent)
            put("mem_used", perf.memUsedMb)
            put("mem_total", perf.memTotalMb)
            // Go 侧这里固定写 null（没有温度来源），别改成数字
            put("temp", JsonNull)
        })
    }

    private fun handleGetMusic(exchange: HttpExchange) {
        val info = SystemCapabilities.music()
        // 读取失败要留痕（手表看不到音乐时便于排查）；成功路径不产生噪音
        SystemCapabilities.musicDiagnostics().takeIf { it.isNotBlank() }?.let {
            addLog("music: $it", "warning")
        }
        writeJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("has_media", info.hasMedia)
            put("title", info.title)
            put("artist", info.artist)
            put("playing", info.playing)
        })
    }

    private fun handleMusicControl(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return
        }
        val action = body.string("action").trim()
        // Go 侧只认这五个动作，其余 400
        if (action !in setOf("play", "pause", "play_pause", "next", "previous")) {
            writeJson(exchange, 400, error("Invalid action"))
            return
        }
        if (SystemCapabilities.musicControl(action)) {
            writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
        } else {
            writeJson(exchange, 500, error("No active media session"))
        }
    }

    /**
     * `/api/music/art`：把当前封面按查询参数处理成图片字节（[MusicArtRoute]）。
     *
     * 与 Go/安卓 的语义一致：无参数 = 原图；`size/blur/dim/w/h` 处理过的 = JPEG q86；
     * 没有封面 = 404、取不到图 = 404、解不开 = 415。封面端点刻意不做设备校验：
     * 手表渲染背景图是直接取 URL 的，带不了 X-Device-Id。
     */
    private fun handleMusicArt(exchange: HttpExchange) {
        val startedMs = System.currentTimeMillis()
        val query = parseQuery(exchange.requestURI.rawQuery)
        val result = musicArtRoute.handle(query)
        // 封面访问日志：桌搭背景灰屏排查就靠这条——手表来取了吗、拿到的是处理过的还是 404。
        val ip = exchange.remoteAddress?.address?.hostAddress ?: "?"
        val ms = System.currentTimeMillis() - startedMs
        val params = listOf("size", "blur", "dim", "w", "h", "k")
            .mapNotNull { k -> query[k]?.let { "$k=$it" } }
            .joinToString("&")
            .ifEmpty { "原图" }
        val tag = if (result.status == 200) "info" else "warning"
        addLog("art: $params -> ${result.status}（${result.bytes.size / 1024}KB, ${ms}ms, 来自 $ip）", tag)
        if (result.status == 200 && result.mime.startsWith("image/")) {
            // 与 Go/安卓一致：封面不进缓存（换歌必须立刻换图，插件另用 ?k= 打散）
            exchange.responseHeaders.set("Cache-Control", "no-store")
        }
        raw(exchange, result.status, result.mime, result.bytes)
    }

    /** artUrl → 像素：取图（读盘/HTTP/内联）+ ImageIO 解码，失败带 HTTP 语义。 */
    private fun loadArtSourceFromSystem(artUrl: String): MusicArtLoad {
        val art = AlbumArtFetcher.fetch(artUrl)
        if (art == null) {
            // 读取失败要留痕：Deck 上看就是一张裂图，日志里得能看出是取图还是解码的错
            addLog("music: 封面读取失败: $artUrl", "warning")
            return MusicArtLoad.Fail(404, "没有可用的封面（封面地址读不到）")
        }
        val pixels = MusicArtCodec.decode(art.bytes)
        if (pixels == null) {
            addLog("music: 封面解码失败（${art.mime}, ${art.bytes.size}B）", "warning")
            return MusicArtLoad.Fail(415, "无法解码封面")
        }
        return MusicArtLoad.Ok(MusicArtSource(art.source, art.bytes, art.mime, pixels))
    }

    // ------------------------------------------------------------ 插件管理

    private fun handleReload(exchange: HttpExchange, method: String) {
        if (method != "POST") {
            writeJson(exchange, 405, error("reload requires POST"))
            return
        }
        val ids = runtime.reload()
        addLog("插件已重载: ${ids.size} 个", "success")
        writeJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("plugins", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        })
    }

    private fun handleInstallPlugin(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return
        }
        val path = body.string("path").trim()
        if (path.isEmpty()) {
            writeJson(exchange, 400, error("缺少插件包路径"))
            return
        }
        val info = try {
            runtime.install(path)
        } catch (e: Exception) {
            addLog("插件安装失败: ${e.message}", "error")
            val code = if (e.message?.contains("安装包不存在") == true) 400 else 500
            writeJson(exchange, code, error(e.message ?: "install failed"))
            return
        }
        addLog("已安装插件: ${info.id} ${info.version}", "success")
        writeJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("id", info.id)
            put("version", info.version)
        })
    }

    private fun handleUninstallPlugin(exchange: HttpExchange, id: String) {
        if (id.isEmpty()) {
            writeJson(exchange, 400, error("缺少插件 id"))
            return
        }
        try {
            runtime.uninstall(id)
        } catch (e: Exception) {
            writeJson(exchange, 500, error(e.message ?: "uninstall failed"))
            return
        }
        addLog("已卸载插件: $id", "info")
        writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
    }

    // ------------------------------------------------------------ 日志 / 设备 / 设置 / 命令

    private fun handleGetLogs(exchange: HttpExchange) {
        val snapshot = synchronized(logs) { logs.toList() }
        val tail = if (snapshot.size > 400) snapshot.takeLast(400) else snapshot
        writeJson(exchange, 200, buildJsonObject {
            put("logs", buildJsonArray {
                tail.forEach { entry ->
                    add(buildJsonObject {
                        put("text", entry.text)
                        put("level", entry.level)
                    })
                }
            })
        })
    }

    private fun handleGetDevices(exchange: HttpExchange) {
        writeJson(exchange, 200, buildJsonObject {
            put("devices", devicesToJson(store.loadTrustedDevices()))
        })
    }

    private fun handleDeleteDevice(exchange: HttpExchange, deviceId: String) {
        if (deviceId.isEmpty()) {
            writeJson(exchange, 400, error("missing device id"))
            return
        }
        store.removeTrustedDevice(decodeSegment(deviceId))
        addLog("已删除设备: $deviceId", "info")
        writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
    }

    private fun handleGetSettings(exchange: HttpExchange) {
        val cfg = store.loadConfig()
        val current = if (port > 0) port else cfg.lastPort
        writeJson(exchange, 200, buildJsonObject {
            put("port", cfg.lastPort)
            put("accept_all", cfg.acceptAll)
            put("market_source", cfg.marketSource)
            put("current_port", current)
        })
    }

    private fun handlePutSettings(exchange: HttpExchange) {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return
        }
        var cfg = store.loadConfig()
        if (body.has("port")) {
            val newPort = body.int("port")
            if (newPort < 1 || newPort > 65535) {
                writeJson(exchange, 400, error("端口超出范围"))
                return
            }
            cfg = cfg.copy(lastPort = newPort)
        }
        if (body.has("accept_all")) cfg = cfg.copy(acceptAll = body.bool("accept_all"))
        if (body.has("market_source")) cfg = cfg.copy(marketSource = body.string("market_source"))
        store.saveConfig(cfg)
        addLog("设置已保存", "success")
        handleGetSettings(exchange)
    }

    private fun handleCreateCommand(exchange: HttpExchange) {
        val (name, command) = parseCommand(exchange) ?: return
        store.addCommand(name, command)
        writeStoredCommands(exchange)
    }

    private fun handleUpdateCommand(exchange: HttpExchange, index: Int) {
        val (name, command) = parseCommand(exchange) ?: return
        val commands = store.loadCommands().toMutableList()
        if (index !in commands.indices) {
            writeJson(exchange, 404, error("命令不存在"))
            return
        }
        commands[index] = Command(name, command)
        store.saveCommands(commands)
        writeStoredCommands(exchange)
    }

    private fun handleDeleteCommand(exchange: HttpExchange, index: Int) {
        val commands = store.loadCommands()
        if (index !in commands.indices) {
            writeJson(exchange, 404, error("命令不存在"))
            return
        }
        store.removeCommand(index)
        writeJson(exchange, 200, buildJsonObject { put("status", "ok") })
    }

    private fun handleGetCommands(exchange: HttpExchange) {
        writeCommandList(exchange)
    }

    private fun handleRunCommand(exchange: HttpExchange, index: Int) {
        val commands = store.loadCommands()
        if (index !in commands.indices) {
            writeJson(exchange, 400, error("Invalid command ID"))
            return
        }
        val command = commands[index]
        val commandStr = command.command.trim()
        if (commandStr.isEmpty()) {
            writeJson(exchange, 400, error("Empty command"))
            return
        }
        addLog("执行命令: ${command.name}", "warning")
        val result = runShell(commandStr)
        if (result.exitCode == 0) {
            addLog("命令完成: 返回码 ${result.exitCode}", "success")
            writeJson(exchange, 200, buildJsonObject {
                put("status", "ok")
                put("returncode", result.exitCode)
                put("output", result.output.take(1000))
            })
        } else {
            addLog("命令失败: ${result.output.take(200)}", "error")
            writeJson(exchange, 500, error("Command failed: " + result.output.take(500)))
        }
    }

    /**
     * Go 侧有**两种**命令列表形状，别合并：
     * - `GET /api/commands`（给手表看）：`status` + 每项 `{id,name,command}`，name 截 50、
     *   command 截 200
     * - `POST/PUT /api/commands`（桌面 UI 的管理端点）：原始记录，没有 status/id
     * 一致性要求逐字对齐，所以这里也是两个函数。
     */
    private fun writeCommandList(exchange: HttpExchange) {
        writeJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("commands", buildJsonArray {
                store.loadCommands().forEachIndexed { index, command ->
                    add(buildJsonObject {
                        put("id", index)
                        put("name", command.name.take(50))
                        put("command", command.command.take(200))
                    })
                }
            })
        })
    }

    /** 管理端点用的形状：原始存储记录。 */
    private fun writeStoredCommands(exchange: HttpExchange) {
        writeJson(exchange, 200, buildJsonObject {
            put("commands", commandsToJson(store.loadCommands()))
        })
    }

    /** 解析命令体；失败时已经写过响应。 */
    private fun parseCommand(exchange: HttpExchange): Pair<String, String>? {
        val body = readBody(exchange) ?: run {
            writeJson(exchange, 400, error("Invalid JSON"))
            return null
        }
        val name = body.string("name").trim()
        val command = body.string("command").trim()
        if (name.isEmpty() || command.isEmpty()) {
            writeJson(exchange, 400, error("命令名与内容都不能为空"))
            return null
        }
        return name to command
    }

    // ------------------------------------------------------------ 工具

    /** 信任门禁：accept_all 直接放行；loopback 放行；否则要已信任的 X-Device-ID。 */
    private fun withTrusted(exchange: HttpExchange, block: () -> Unit) {
        val cfg = store.loadConfig()
        if (cfg.acceptAll) return block()
        // loopback 只可能来自本机（门禁是给 LAN 上的手表/手机准备的）；
        // 只看 TCP 对端地址，不看请求头，伪造头绕不过
        if (isLoopback(exchange)) return block()
        val deviceId = extractDeviceId(exchange)
        if (deviceId.isEmpty() || deviceId.length > 100) {
            writeJson(exchange, 401, error("Missing device ID"))
            return
        }
        if (!store.isTrusted(deviceId)) {
            writeJson(exchange, 403, error("Device not trusted"))
            return
        }
        block()
    }

    private fun extractDeviceId(exchange: HttpExchange): String {
        val fromHeader = exchange.requestHeaders.getFirst("X-Device-ID")
            ?: exchange.requestHeaders.getFirst("X-Device-Id")
            ?: exchange.requestHeaders.getFirst("x-device-id")
        if (!fromHeader.isNullOrBlank()) return fromHeader.trim()
        val query = parseQuery(exchange.requestURI.rawQuery)
        return query["device_id"].orEmpty().trim()
    }

    private fun isLoopback(exchange: HttpExchange): Boolean {
        val addr = exchange.remoteAddress
        if (addr == null || addr.address?.isLoopbackAddress == true) return true
        // 地址字面量判断不了主机名（本机测试会写 localhost）
        return addr.address?.hostAddress?.startsWith("127.") == true
    }

    private fun addLog(message: String, level: String) {
        val stamp = LocalTime.now().format(LOG_TIME)
        synchronized(logs) {
            logs.add(LogEntry("[$stamp] $message", level))
            if (logs.size > maxLogs) logs.subList(0, logs.size - maxLogs).clear()
        }
    }

    fun logs(): List<LogEntry> = synchronized(logs) { logs.toList() }

    /**
     * worker/插件侧日志入口（`console.*` 经胶水 `__hkLog` 转发到这里）。
     *
     * 手表侧没有日志出口，插件的 console 输出是桌面端排障的主要线索；
     * 只打终端 stdout 看不到，必须进应用日志面板（`/api/logs`）。
     */
    fun addWorkerLog(message: String) {
        addLog("worker: $message", "info")
    }

    private fun readBody(exchange: HttpExchange): JsonObject? {
        val text = try {
            exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) {
            return null
        }
        if (text.isBlank()) return null
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun writeJson(exchange: HttpExchange, code: Int, body: JsonElement) {
        addCors(exchange)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        val bytes = (json.encodeToString(JsonElement.serializer(), body) + "\n")
            .toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 纯文本响应（Go 侧 http.Error 的形状：text/plain + 换行）。 */
    private fun text(exchange: HttpExchange, code: Int, message: String) {
        addCors(exchange)
        val bytes = (message + "\n").toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 任意字节的响应（静态资源）。 */
    private fun raw(exchange: HttpExchange, code: Int, contentType: String, bytes: ByteArray) {
        addCors(exchange)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * 读 worker 宿主页资源（`resources/hydrokit/<name>`，随 jar 一起打进去）。
     *
     * 与安卓端读 assets/hydrokit 是同一个位置安排：胶水不能在运行时拼（那是 Go 桌面端
     * Wails 那套的做法，要多传一层绑定），两端共用同一份文件更不容易走歪。
     */
    private fun hostFileBytes(name: String): ByteArray? =
        HydroServer::class.java.classLoader.getResourceAsStream("hydrokit/$name")?.use {
            it.readBytes()
        }

    private fun urlEncode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun addCors(exchange: HttpExchange) {
        val h = exchange.responseHeaders
        h.set("Access-Control-Allow-Origin", "*")
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, PATCH, DELETE")
        h.set("Access-Control-Allow-Headers", "Content-Type, Accept, X-Device-ID, Authorization, X-Proxy-Cookie, X-Proxy-Referer, X-Proxy-Origin, X-Proxy-User-Agent, X-Proxy-Authorization, x-opencode-directory")
        h.set("Access-Control-Max-Age", "86400")
        h.set("Connection", "close")
    }

    private fun error(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("message", message)
    }

    private fun TrustedDevice.toJson(): JsonObject = buildJsonObject {
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("trusted_at", trustedAt)
    }

    private fun PairRequest.toJson(): JsonObject = buildJsonObject {
        put("device_id", deviceId)
        put("device_name", deviceName)
    }

    private fun Command.toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("command", command)
    }

    // 三个泛型扩展在 JVM 上签名会撞（都是 List -> JsonArray），所以用具名函数
    private fun devicesToJson(list: List<TrustedDevice>) = buildJsonArray { list.forEach { add(it.toJson()) } }
    private fun pairsToJson(list: List<PairRequest>) = buildJsonArray { list.forEach { add(it.toJson()) } }
    private fun commandsToJson(list: List<Command>) = buildJsonArray { list.forEach { add(it.toJson()) } }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content.orEmpty()

    private fun JsonObject.int(key: String): Int = string(key).toIntOrNull() ?: 0
    private fun JsonObject.bool(key: String): Boolean = string(key).toBoolean()
    private fun JsonObject.has(key: String): Boolean = this[key] != null
    private fun JsonObject.floatOrNull(key: String): Float? =
        (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()
    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun mapToJson(map: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in map) {
            when (v) {
                null -> put(k, JsonNull)
                is String -> put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is List<*> -> put(k, buildJsonArray {
                    for (item in v) {
                        when (item) {
                            null -> add(JsonNull)
                            is String -> add(JsonPrimitive(item))
                            is Number -> add(JsonPrimitive(item))
                            is Boolean -> add(JsonPrimitive(item))
                            else -> add(JsonPrimitive(item.toString()))
                        }
                    }
                })
                else -> put(k, v.toString())
            }
        }
    }

    /** 路径里的设备 id 要先 URL 解码（设备 id 可能是带特殊字符的字符串）。 */
    private fun decodeSegment(segment: String): String =
        java.net.URLDecoder.decode(segment, Charsets.UTF_8)

    private fun shortId(id: String): String = if (id.length > 8) id.take(8) else id

    private data class ShellResult(val exitCode: Int, val output: String)

    private fun runShell(command: String): ShellResult = try {
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        ShellResult(process.waitFor(), output)
    } catch (e: Exception) {
        ShellResult(-1, e.message.orEmpty())
    }

    /** 日志条目（与 Go `api.LogEntry` 同形）。 */
    data class LogEntry(val text: String, val level: String)

    private companion object {
        val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** worker 宿主页只允许这两个文件名（别把其它 resource 顺手暴露出去）。 */
        val HOST_FILES = setOf("worker.html", "worker.js")

        fun matchCommandId(path: String): Boolean =
            path.startsWith("/api/commands/") && path.removePrefix("/api/commands/").toIntOrNull() != null

        fun commandId(path: String): Int = path.removePrefix("/api/commands/").toIntOrNull() ?: -1

        fun matchRunCommand(path: String): Boolean {
            val suffixes = listOf("/api/commands/", "/commands/")
            for (prefix in suffixes) {
                if (path.startsWith(prefix) && path.endsWith("/run")) {
                    val middle = path.removePrefix(prefix).removeSuffix("/run")
                    if (middle.toIntOrNull() != null) return true
                }
            }
            return false
        }

        fun runCommandId(path: String): Int {
            val s = if (path.startsWith("/api/commands/")) {
                path.removePrefix("/api/commands/").removeSuffix("/run")
            } else {
                path.removePrefix("/commands/").removeSuffix("/run")
            }
            return s.toIntOrNull() ?: -1
        }
    }
}
