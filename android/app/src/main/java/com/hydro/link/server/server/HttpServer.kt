package com.hydro.link.server.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hydro.link.server.model.*
import com.hydro.link.server.service.LockAccessibilityService
import com.hydro.link.server.service.MusicController
import com.hydro.link.server.util.DataManager
import com.hydro.link.server.util.NetworkUtil
import com.hydro.link.server.util.Authorizer
import com.hydro.link.server.util.RootShell
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class HydroHttpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    private val gson = Gson()
    private val dataManager = DataManager.get(context)

    /** HydroKit 插件平台（商店 + 运行时 + worker 通道 + 宿主 + 路由）。 */
    val hydroKit: com.hydro.link.server.hydrokit.HydroKit by lazy {
        com.hydro.link.server.hydrokit.HydroKit(
            context = context,
            capabilities = com.hydro.link.server.hydrokit.AndroidKitCapabilities(
                context = context,
                port = { currentPort },
                dataManagerProvider = { dataManager },
                commandRunner = { cmd -> runCommandForPlugins(cmd) },
                onInputUnavailable = {
                    // 无障碍与 Root/Shell 都不可用：插件请求控制设备时在手机端提示一次
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            android.widget.Toast.makeText(
                                context.applicationContext,
                                "无可用权限，无法控制设备",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (_: Exception) {
                        }
                    }
                },
            ),
            hostAssets = com.hydro.link.server.hydrokit.HostAssetProvider { name ->
                try { context.assets.open("hydrokit/$name").use { it.readBytes() } } catch (e: Exception) { null }
            },
            log = { level, message -> addLog(message, level) },
        )
    }

    /** 插件 worker 宿主（WebView）：每个插件一个，隐藏运行。 */
    val pluginHosts: com.hydro.link.server.hydrokit.PluginHostManager by lazy {
        com.hydro.link.server.hydrokit.PluginHostManager(
            context = context,
            store = hydroKit.store,
            bridge = hydroKit.bridge,
            portProvider = { currentPort },
            log = { level, message -> addLog(message, level) },
        )
    }

    /**
     * 封面图路由（`/api/music/art`）：像素处理在 [MusicArtProcessor]（纯函数、有单测），
     * 这里只负责 Bitmap ↔ ARGB 缓冲的转换与编解码。
     */
    private val musicArtRoute: MusicArtRoute by lazy {
        MusicArtRoute(
            provider = {
                val art = musicController.albumArt() ?: return@MusicArtRoute null
                MusicArtSource(art.source, art.bitmap.toPixels())
            },
            encodePng = { it.toBitmap().compressToBytes(android.graphics.Bitmap.CompressFormat.PNG, 100) },
            encodeJpeg = { it.toBitmap().compressToBytes(android.graphics.Bitmap.CompressFormat.JPEG, 86) },
            log = { msg -> addLog(msg, "warning") },
        )
    }

    private fun android.graphics.Bitmap.toPixels(): MusicArtProcessor.Pixels {
        val bmp = if (config == android.graphics.Bitmap.Config.ARGB_8888) {
            this
        } else {
            copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: this
        }
        val buf = IntArray(bmp.width * bmp.height)
        bmp.getPixels(buf, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return MusicArtProcessor.Pixels(buf, bmp.width, bmp.height)
    }

    private fun MusicArtProcessor.Pixels.toBitmap(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)

    private fun android.graphics.Bitmap.compressToBytes(
        format: android.graphics.Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (!compress(format, quality, out)) throw IllegalStateException("封面编码失败（$format）")
        return out.toByteArray()
    }

    /** 实际监听端口（start 之后确定）；插件宿主、ABI 上下文与插件 WebUI 用它拼 URL。 */
    @Volatile
    private var activePort: Int = port

    /** 供 UI 读取真实监听端口（可能由系统随机分配）。 */
    val currentPort: Int get() = activePort

    /** 供 kit 能力端点执行命令：与 /api/commands/{id}/run 同一套逻辑（授权器：无/Root/Shizuku）。 */
    private fun runCommandForPlugins(command: String): Triple<Boolean, String, Int> {
        val kind = Authorizer.kindOf(dataManager.loadConfig().authorizer)
        val r = Authorizer.exec(command, kind)
        return Triple(r.ok, r.output, r.code)
    }
    private val musicController = MusicController(context)
    private val logBuffer = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val maxLogs = 100

    companion object {
        private const val VERSION = "1.2.2"

        /** logcat tag（`adb logcat -s HydroLink`）。 */
        private const val TAG_LOGCAT = "HydroLink"

        /**
         * 给**缺少 charset 的请求体**补上 `charset=utf-8`。
         *
         * NanoHTTPD 的 `ContentType.getEncoding()` 对非 text 系（`text/…`）的类型默认给
         * **US-ASCII**，于是 `Content-Type: application/json` 的请求体会被逐字节按
         * ASCII 解码 —— 中文和 emoji 全变成 U+FFFD（真机上的表现：插件里的歌名
         * 是一串方块，事件里输入的中文存进插件存储也是方块）。
         *
         * 手表容器的 fetch 与插件胶水发的都是不带 charset 的 application/json，
         * 第三方插件更不会带，所以**在服务端统一兜住**，而不是指望所有客户端都写对。
         *
         * 必须在 `session.parseBody()` **之前**调用：解码就发生在那里。
         */
        fun normalizeRequestBodyCharset(headers: MutableMap<String, String>) {
            val key = headers.keys.firstOrNull { it.equals("content-type", ignoreCase = true) } ?: return
            val value = headers[key] ?: return
            if (value.contains("charset", ignoreCase = true)) return
            headers[key] = "$value; charset=utf-8"
        }
    }

    fun addLog(message: String, level: String = "info") {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry("[$timestamp] $message", level)
        logBuffer.add(entry)
        if (logBuffer.size > maxLogs) logBuffer.removeAt(0)
        // 同时镜像到 logcat：真机上排查插件问题时，`adb logcat -s HydroLink`
        // 就能看到 HydroKit 的每一步（宿主加载/worker 连上/请求路由），
        // 不必让用户去截应用内日志的图。release 包也一样有用，所以不按 DEBUG 关。
        when (level) {
            "error" -> android.util.Log.e(TAG_LOGCAT, message)
            "warning" -> android.util.Log.w(TAG_LOGCAT, message)
            else -> android.util.Log.i(TAG_LOGCAT, message)
        }
    }

    fun getLogs(): List<LogEntry> = logBuffer.toList()

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        // NanoHTTPD 支持端口 0（随机分配）：start 之后才能拿到真实端口
        activePort = listeningPort
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        // Handle CORS preflight (OPTIONS) requests
        if (method == Method.OPTIONS) {
            val corsResp = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(corsResp)
            return corsResp
        }

        try {
            // Parse body for POST
            var rawBody: String? = null
            val bodyJson: JsonObject? = if (method == Method.POST) {
                try {
                    // 先补 charset：NanoHTTPD 对没有 charset 的请求体按 US-ASCII 解码，
                    // 中文会全变 U+FFFD（见 normalizeRequestBodyCharset 的注释）
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        normalizeRequestBodyCharset(session.headers as MutableMap<String, String>)
                    }.onFailure { addLog("请求头 charset 归一化失败: ${it.message}", "warning") }
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val jsonStr = files["postData"]
                    rawBody = jsonStr
                    jsonStr?.let {
                        try { gson.fromJson(it, JsonObject::class.java) } catch (e: Exception) { null }
                    }
                } catch (e: Exception) {
                    null
                }
            } else null

            // Normalize URI: strip trailing slash (except root)
            val cleanUri = if (uri.length > 1 && uri.endsWith("/")) uri.dropLast(1) else uri

            val response = when {
                // ---- Public endpoints (no auth) - support both /api/xxx and /xxx ----
                // Ping
                (cleanUri == "/api/ping" || cleanUri == "/ping") && method == Method.GET ->
                    jsonResp(mapOf("status" to "ok"))

                // Version
                (cleanUri == "/api/version" || cleanUri == "/version") && method == Method.GET ->
                    jsonResp(mapOf(
                        "status" to "ok",
                                                "version" to VERSION
                    ))

                // LAN Discovery (HTTP 直接探测)
                (cleanUri == "/api/discover/lan" || cleanUri == "/discover/lan") && method == Method.GET -> {
                    val devices = kotlinx.coroutines.runBlocking {
                        com.hydro.link.server.service.LanDiscovery.discover(context)
                    }
                    val devList = devices.map { d ->
                        mapOf(
                            "name" to d.name,
                            "host" to d.host,
                            "port" to d.port,
                            "device_type" to d.deviceType
                        )
                    }
                    jsonResp(mapOf("status" to "ok", "devices" to devList))
                }

                // PID
                (cleanUri == "/api/pid" || cleanUri == "/pid") && method == Method.GET ->
                    jsonResp(mapOf(
                        "status" to "ok",
                        "pid" to android.os.Process.myPid()
                    ))

                // ---- Pairing ----
                (cleanUri == "/api/pair" || cleanUri == "/pair") && method == Method.POST ->
                    handlePair(bodyJson)

                cleanUri.startsWith("/api/check/") && method == Method.GET -> {
                    val deviceId = cleanUri.removePrefix("/api/check/")
                    handleCheckDevice(deviceId)
                }
                cleanUri.startsWith("/check/") && method == Method.GET -> {
                    val deviceId = cleanUri.removePrefix("/check/")
                    handleCheckDevice(deviceId)
                }

                (cleanUri == "/api/auth" || cleanUri == "/auth") && method == Method.GET ->
                    handleAuth(headers)

                (cleanUri == "/api/pending_pair" || cleanUri == "/pending_pair") && method == Method.GET ->
                    handleGetPendingPairs()

                (cleanUri == "/api/pair/respond" || cleanUri == "/pair/respond") && method == Method.POST ->
                    handleRespondPair(bodyJson)

                // ---- Trusted endpoints ----
                (cleanUri == "/api/status" || cleanUri == "/status") && method == Method.GET ->
                    withTrusted(headers) { handleStatus() }

                (cleanUri == "/api/lock" || cleanUri == "/lock") && method == Method.POST ->
                    withTrusted(headers) { handleLock() }

                (cleanUri == "/api/performance" || cleanUri == "/performance") && method == Method.GET ->
                    withTrusted(headers) { handlePerformance() }

                // ---- Music ----
                (cleanUri == "/api/music" || cleanUri == "/music") && method == Method.GET ->
                    withTrusted(headers) { handleGetMusic() }

                (cleanUri == "/api/music/control" || cleanUri == "/music/control") && method == Method.POST ->
                    withTrusted(headers) { handleMusicControl(bodyJson) }

                // 封面图：与桌面 desktop/internal/api/media.go 同一契约。
                // 不带信任校验——手表渲染背景图时是渲染器直接取 URL，带不了
                // X-Device-Id 头（桌面端同样是公开的）。
                (cleanUri == "/api/music/art" || cleanUri == "/music/art") && method == Method.GET -> {
                    val result = musicArtRoute.handle(
                        session.parameters.entries.associate { (k, v) -> k to (v.firstOrNull() ?: "") }
                    )
                    val status = Response.Status.values().firstOrNull { it.requestStatus == result.status }
                        ?: Response.Status.OK
                    newFixedLengthResponse(
                        status, result.mime,
                        ByteArrayInputStream(result.bytes), result.bytes.size.toLong(),
                    ).apply { addHeader("Cache-Control", "no-store") }
                }

                (cleanUri == "/api/commands" || cleanUri == "/commands") && method == Method.GET ->
                    withTrusted(headers) { handleGetCommands() }

                cleanUri.matches(Regex("/api/commands/\\d+/run")) && method == Method.POST -> {
                    val cmdId = cleanUri.split("/")[3].toInt()
                    withTrusted(headers) { handleRunCommand(cmdId) }
                }
                cleanUri.matches(Regex("/commands/\\d+/run")) && method == Method.POST -> {
                    val cmdId = cleanUri.split("/")[2].toInt()
                    withTrusted(headers) { handleRunCommand(cmdId) }
                }

                // ---- HydroKit（插件平台）----
                // /api/hydro/list、/api/hydro/{pkg}/{surface}/{item}/{view|refresh|event}、
                // /api/hydro/webui/...、/api/hydro/worker/{poll,respond}
                cleanUri.startsWith("/api/hydro") -> {
                    val req = com.hydro.link.server.hydrokit.HydroHttpRequest(
                        method = method.name,
                        path = cleanUri,
                        query = session.parameters.entries.associate { (k, v) -> k to (v.firstOrNull() ?: "") },
                        headers = headers.entries.associate { (k, v) -> k.lowercase() to v },
                        body = rawBody,
                    )
                    val out = hydroKit.handle(req)
                    if (out == null) {
                        jsonResp(mapOf("status" to "error", "message" to "Not found"), Response.Status.NOT_FOUND)
                    } else {
                        val status = Response.Status.values().firstOrNull { it.requestStatus == out.status }
                            ?: Response.Status.OK
                        val resp = if (out.bytes != null) {
                            // 二进制（如 wasm）：必须按字节返回，不能走字符串编码
                            newFixedLengthResponse(
                                status, out.contentType,
                                ByteArrayInputStream(out.bytes), out.bytes.size.toLong(),
                            )
                        } else {
                            newFixedLengthResponse(status, out.contentType, out.body)
                        }
                        out.headers.forEach { (k, v) -> resp.addHeader(k, v) }
                        resp
                    }
                }

                // ---- IME 输入法服务 ----
                cleanUri.startsWith("/api/ime") -> {
                    val jsonElem = handleImeInternal(cleanUri, method.name, bodyJson)
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(jsonElem))
                }

                // ---- Root / health check ----
                cleanUri == "/" || cleanUri == "" -> {
                    jsonResp(mapOf("status" to "ok", "service" to "HydroLinkServer", "version" to VERSION))
                }

                else -> jsonResp(mapOf("status" to "error", "message" to "Not found"), Response.Status.NOT_FOUND)
            }

            // Add CORS and other headers to every response
            addCorsHeaders(response)
            return response

        } catch (e: Exception) {
            addLog("服务器错误: ${e.message}", "error")
            val errResp = jsonResp(mapOf("status" to "error", "message" to "Internal error"), Response.Status.INTERNAL_ERROR)
            addCorsHeaders(errResp)
            return errResp
        }
    }

    /**
     * Add CORS headers and connection management headers to a response.
     */
    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Device-ID, Authorization")
        response.addHeader("Access-Control-Max-Age", "86400")
        response.addHeader("Connection", "close")
    }

    // ---- Auth helper ----
    private fun withTrusted(headers: Map<String, String>, block: () -> Response): Response {
        // Check accept_all mode
        val config = dataManager.loadConfig()
        if (config.acceptAll) return block()

        val deviceId = headers["x-device-id"] ?: ""
        if (deviceId.isEmpty() || deviceId.length > 100) {
            // 手表执行命令必带这个头；没有就说明请求没带设备标识（或走的传输通道丢了头）
            addLog("请求被拒：缺少 X-Device-ID（可能是互联通道丢了请求头）", "warning")
            return jsonResp(mapOf("status" to "error", "message" to "Missing device ID"), Response.Status.UNAUTHORIZED)
        }
        if (!dataManager.isTrusted(deviceId)) {
            val short = deviceId.take(12)
            addLog("请求被拒：设备未受信任（$short…）——在「设备」页确认已配对", "warning")
            return jsonResp(mapOf("status" to "error", "message" to "Device not trusted"), Response.Status.FORBIDDEN)
        }
        return block()
    }

    // ---- Internal request handler (for interconnect transport) ----

    /**
     * 互联通道下的 `/api/hydro/…` 路由：手表的请求经由 XMS Wearable 转发到这里
     * （[com.hydro.link.server.hydrokit.HydroKit] 的插件路由），响应必须是 JSON 对象
     * —— 互联通道只能传 JSON，二进制资源（wasm/图片）走不了这条路。
     */
    private fun handleHydroInternal(
        cleanPath: String,
        method: String,
        bodyJson: JsonObject?,
    ): com.google.gson.JsonElement {
        val req = com.hydro.link.server.hydrokit.HydroInterconnect.toRequest(
            rawPath = cleanPath,
            method = method,
            body = bodyJson?.toString(),
        )
        // 响应包装（二进制/空正文/非 JSON）在 HydroInterconnect 里，有单测
        return com.hydro.link.server.hydrokit.HydroInterconnect.toJsonElement(gson, hydroKit.handle(req))
    }

    /**
     * 互联通道与 HTTP 下的统一 `/api/ime/...` 路由：
     * 手表通过互联通道或网络调用输入法服务（手写识别 / 拼音输入等）。
     */
    private fun handleImeInternal(
        cleanPath: String,
        method: String,
        bodyJson: JsonObject?,
    ): com.google.gson.JsonElement {
        val rest = cleanPath.removePrefix("/api/ime").removePrefix("/ime").trimStart('/')
        return when {
            rest == "handwriting" || rest.startsWith("handwriting") -> {
                if (method != "POST") {
                    return gson.toJsonTree(mapOf("error" to "method not allowed", "ok" to false))
                }
                val candidates = kotlinx.coroutines.runBlocking {
                    com.hydro.link.server.ime.ImeManager.recognize(bodyJson)
                }
                gson.toJsonTree(mapOf("ok" to true, "candidates" to candidates))
            }
            rest == "status" || rest.isEmpty() -> {
                gson.toJsonTree(mapOf("ok" to true, "handwriting" to true, "pinyin" to false))
            }
            else -> {
                gson.toJsonTree(mapOf("error" to "unknown ime endpoint: $rest", "ok" to false))
            }
        }
    }

    /**
     * Handle an API request from the InterconnectHandler (UDP transport).
     * Routes to the same handlers as serve() but returns a JsonElement
     * instead of an HTTP Response.  Bypasses device-trust checks since
     * interconnect already implies a trusted companion-app relationship.
     */
    fun handleInternalRequest(path: String, method: String, body: com.google.gson.JsonElement?): com.google.gson.JsonElement? {
        val cleanPath = if (path.startsWith("/api")) path else if (path.startsWith("/")) "/api$path" else "/api/$path"
        val bodyJson = if (body is JsonObject) body else null

        return try {
            when {
                // ---- HydroKit（插件平台）----
                // 手表在互联通道下会把请求转发到这里（deviceService 里 HTTP 与
                // interconnect 两条路都会走），所以插件路由必须在这里也生效；
                // 另外互联通道没有 HTTP 头，X-Hydro-Store 改从 query/body 的
                // "store" 字段读取（见 vela deviceService.hydro）。
                cleanPath.startsWith("/api/hydro") -> handleHydroInternal(cleanPath, method, bodyJson)

                // ---- IME 输入法服务（手写识别 / 拼音等）----
                cleanPath.startsWith("/api/ime") -> handleImeInternal(cleanPath, method, bodyJson)

                // Health check
                cleanPath == "/api/ping" || cleanPath == "/ping" ->
                    gson.toJsonTree(mapOf("status" to "ok", "app" to "hydrolink", "version" to VERSION))

                // System status
                cleanPath == "/api/status" || cleanPath == "/status" ->
                    gson.toJsonTree(buildStatusMap())

                // Lock
                cleanPath == "/api/lock" || cleanPath == "/lock" -> {
                    val success = LockAccessibilityService.instance?.lockScreen() ?: false
                    gson.toJsonTree(mapOf("status" to if (success) "ok" else "error"))
                }

                // Performance
                cleanPath == "/api/performance" || cleanPath == "/performance" ->
                    gson.toJsonTree(buildPerformanceMap())

                // Music
                cleanPath == "/api/music" || cleanPath == "/music" ->
                    gson.toJsonTree(buildMusicMap())

                cleanPath == "/api/music/control" || cleanPath == "/music/control" -> {
                    val action = bodyJson?.get("action")?.asString ?: ""
                    val success = musicController.control(action)
                    gson.toJsonTree(mapOf("status" to if (success) "ok" else "error"))
                }

                // Commands
                cleanPath == "/api/commands" || cleanPath == "/commands" ->
                    // 与 HTTP 路径共用同一份形状（必须带数字 id）
                    gson.toJsonTree(mapOf("status" to "ok", "commands" to commandsListJson()))

                cleanPath.matches(Regex("/api/commands/\\d+/run")) || cleanPath.matches(Regex("/commands/\\d+/run")) -> {
                    val cmdId = cleanPath.split("/").last { it.matches(Regex("\\d+")) }.toInt()
                    gson.toJsonTree(runCommandResult(cmdId).second)
                }

                else -> null
            }
        } catch (e: Exception) {
            gson.toJsonTree(mapOf("status" to "error", "message" to (e.message ?: "Internal error")))
        }
    }

    /** Build the status response map (shared between HTTP and interconnect). */
    private fun buildStatusMap(): Map<String, Any> {
        return mapOf(
            "status" to "running",
            "trusted_count" to dataManager.loadTrustedDevices().size,
            "system" to getSystemInfo(),
            "battery" to getBatteryInfo()
        )
    }

    /** Build the performance response map. */
    private fun buildPerformanceMap(): Map<String, Any?> {
        return try {
            val cpuUsage = getCpuUsage()
            val memInfo = getMemoryInfo()
            mapOf(
                "status" to "ok",
                "cpu" to cpuUsage,
                "mem_percent" to memInfo.first,
                "mem_used" to memInfo.second,
                "mem_total" to memInfo.third,
                "temp" to null
            )
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to (e.message ?: "Unknown error"))
        }
    }

    /** Build the music info map. */
    private fun buildMusicMap(): Map<String, Any> {
        val info = musicController.getActiveMediaInfo()
        return if (info != null) {
            mapOf("status" to "ok", "has_media" to true, "title" to info.title, "artist" to info.artist, "playing" to info.playing)
        } else {
            mapOf("status" to "ok", "has_media" to false, "title" to "", "artist" to "", "playing" to false)
        }
    }

    // ---- Pairing handlers ----
    private fun handlePair(body: JsonObject?): Response {
        if (body == null) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid JSON"), Response.Status.BAD_REQUEST)
        }

        val deviceId = body.get("device_id")?.asString?.trim() ?: ""
        val deviceName = body.get("device_name")?.asString?.trim()?.take(50) ?: "Unknown"

        if (deviceId.isEmpty() || deviceId.length > 100) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid device_id"), Response.Status.BAD_REQUEST)
        }

        // 拒绝不是永久的：新的配对请求先把历史拒绝清掉，重新弹窗问用户
        // （与桌面端 handlePairRequest 的 RemoveRejected 同一策略）
        dataManager.removeRejected(deviceId)

        // Check if already trusted
        if (dataManager.isTrusted(deviceId)) {
            addLog("设备 ${deviceId.take(8)}... 已信任", "success")
            val config = dataManager.loadConfig()
            return jsonResp(mapOf(
                "status" to "trusted",
                "message" to "Device already trusted",
                "encryption_key" to config.encryptionKey
            ))
        }

        // Accept all mode
        val config = dataManager.loadConfig()
        if (config.acceptAll) {
            dataManager.addTrustedDevice(deviceId, deviceName)
            addLog("自动信任设备: ${deviceId.take(8)}... ($deviceName)", "success")
            return jsonResp(mapOf(
                "status" to "trusted",
                "message" to "Auto accepted",
                "encryption_key" to config.encryptionKey
            ))
        }

        // Pending
        addLog("配对请求: ${deviceId.take(8)}...", "warning")
        dataManager.addPendingPair(deviceId, deviceName)
        return jsonResp(mapOf("status" to "pending", "message" to "Waiting for user confirmation"))
    }

    private fun handleCheckDevice(deviceId: String): Response {
        if (deviceId.isEmpty() || deviceId.length > 100) {
            return jsonResp(mapOf("status" to "error"), Response.Status.BAD_REQUEST)
        }

        if (dataManager.isRejected(deviceId)) {
            return jsonResp(mapOf("status" to "rejected"))
        }

        if (dataManager.isTrusted(deviceId)) {
            val config = dataManager.loadConfig()
            return jsonResp(mapOf(
                "status" to "trusted",
                "encryption_key" to config.encryptionKey
            ))
        }

        return jsonResp(mapOf("status" to "pending"))
    }

    private fun handleAuth(headers: Map<String, String>): Response {
        val deviceId = headers["x-device-id"] ?: ""
        if (deviceId.isEmpty() || deviceId.length > 100) {
            return jsonResp(mapOf("status" to "error", "message" to "Missing device ID"), Response.Status.BAD_REQUEST)
        }
        return jsonResp(mapOf(
            "status" to if (dataManager.isTrusted(deviceId)) "authorized" else "unauthorized"
        ))
    }

    private fun handleGetPendingPairs(): Response {
        val pairs = dataManager.loadPendingPairs()
        return jsonResp(mapOf("status" to "ok", "pairs" to pairs))
    }

    private fun handleRespondPair(body: JsonObject?): Response {
        if (body == null) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid JSON"), Response.Status.BAD_REQUEST)
        }

        val deviceId = body.get("device_id")?.asString?.trim() ?: ""
        val action = body.get("action")?.asString?.trim() ?: ""
        val deviceName = body.get("device_name")?.asString?.trim()?.take(50) ?: "Unknown"

        if (deviceId.isEmpty() || action !in listOf("accept", "reject")) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid params"), Response.Status.BAD_REQUEST)
        }

        dataManager.removePendingPair(deviceId)

        if (action == "accept") {
            dataManager.addTrustedDevice(deviceId, deviceName)
            addLog("已信任设备: ${deviceId.take(8)}...", "success")
        } else {
            dataManager.addRejected(deviceId)
            addLog("拒绝设备: ${deviceId.take(8)}...", "error")
        }

        return jsonResp(mapOf("status" to "ok"))
    }

    // ---- Status ----
    private fun handleStatus(): Response {
        val trustedCount = dataManager.loadTrustedDevices().size
        return jsonResp(mapOf(
            "status" to "running",
            "trusted_count" to trustedCount,
            "system" to getSystemInfo(),
            "battery" to getBatteryInfo()
        ))
    }

    // ---- Lock ----
    private fun handleLock(): Response {
        val service = LockAccessibilityService.instance
        if (service == null) {
            addLog("锁屏失败: 无障碍服务未启用", "error")
            return jsonResp(mapOf("status" to "error", "message" to "Accessibility service not enabled"), Response.Status.INTERNAL_ERROR)
        }

        val success = service.lockScreen()
        if (success) {
            addLog("设备已锁屏", "success")
            return jsonResp(mapOf("status" to "ok", "message" to "Screen locked"))
        } else {
            addLog("锁屏失败", "error")
            return jsonResp(mapOf("status" to "error", "message" to "Lock failed"), Response.Status.INTERNAL_ERROR)
        }
    }

    // ---- Performance ----
    private fun handlePerformance(): Response {
        try {
            val cpuUsage = getCpuUsage()
            val memInfo = getMemoryInfo()
            return jsonResp(mapOf(
                "status" to "ok",
                "cpu" to cpuUsage,
                "mem_percent" to memInfo.first,
                "mem_used" to memInfo.second,
                "mem_total" to memInfo.third,
                "temp" to null
            ))
        } catch (e: Exception) {
            return jsonResp(mapOf("status" to "error", "message" to "Failed to get performance"), Response.Status.INTERNAL_ERROR)
        }
    }

    // ---- Music ----
    private fun handleGetMusic(): Response {
        val info = musicController.getActiveMediaInfo()
        return if (info != null) {
            jsonResp(mapOf(
                "status" to "ok",
                "has_media" to true,
                "title" to info.title,
                "artist" to info.artist,
                "playing" to info.playing
            ))
        } else {
            jsonResp(mapOf(
                "status" to "ok",
                "has_media" to false,
                "title" to "",
                "artist" to "",
                "playing" to false
            ))
        }
    }

    private fun handleMusicControl(body: JsonObject?): Response {
        if (body == null) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid JSON"), Response.Status.BAD_REQUEST)
        }
        val action = body.get("action")?.asString?.trim() ?: ""
        if (action !in listOf("play", "pause", "playpause", "next", "previous")) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid action"), Response.Status.BAD_REQUEST)
        }
        val success = musicController.control(action)
        return if (success) {
            jsonResp(mapOf("status" to "ok"))
        } else {
            jsonResp(mapOf("status" to "error", "message" to "No active media session"), Response.Status.INTERNAL_ERROR)
        }
    }

    // ---- Commands ----
    /**
     * 命令列表的统一形状：**必须带数字 id**（手表用它拼 /api/commands/{id}/run）。
     * 互联通道原来直接回 dataManager.loadCommands()（只有 name/command，没有 id），
     * 手表拿到 undefined 就拼出 /api/commands/undefined/run → 服务端匹配不上 →
     * 手表上显示"Not found"。两条通道共用这一个函数，避免再漂移。
     */
    private fun commandsListJson(): List<Map<String, Any?>> =
        dataManager.loadCommands().mapIndexed { i, cmd ->
            mapOf(
                "id" to i,
                "name" to cmd.name.take(50),
                "command" to cmd.command.take(200),
            )
        }

    /**
     * 执行一条命令（HTTP 与互联通道共用）。
     * 语义：**跑起来了就算成功**（回 returncode + output），只有压根没法跑才 error。
     * 返回 (HTTP 状态码, 响应体)。
     */
    private fun runCommandResult(cmdId: Int): Pair<Int, Map<String, Any?>> {
        val commands = dataManager.loadCommands()
        if (cmdId < 0 || cmdId >= commands.size) {
            return 400 to mapOf("status" to "error", "message" to "Invalid command ID")
        }
        val cmd = commands[cmdId]
        val cmdStr = cmd.command.trim()
        if (cmdStr.isEmpty()) {
            return 400 to mapOf("status" to "error", "message" to "Empty command")
        }
        val kind = Authorizer.kindOf(dataManager.loadConfig().authorizer)
        addLog("收到执行请求: #$cmdId ${cmd.name}（授权器=${kind.label}）", "info")
        addLog("执行命令(${kind.label}): ${cmd.name}", if (kind == Authorizer.Kind.NONE) "info" else "warning")
        val r = Authorizer.exec(cmdStr, kind)
        if (r.code < 0) {
            addLog("命令无法执行: ${r.output}", "error")
            return 500 to mapOf("status" to "error", "message" to r.output.take(200))
        }
        addLog("命令完成: 返回码 ${r.code}｜输出: ${r.output.take(200)}", if (r.code == 0) "success" else "warning")
        return 200 to mapOf("status" to "ok", "returncode" to r.code, "output" to r.output.take(1000))
    }

    private fun handleGetCommands(): Response =
        jsonResp(mapOf("status" to "ok", "commands" to commandsListJson()))

    private fun handleRunCommand(cmdId: Int): Response {
        val (code, body) = runCommandResult(cmdId)
        val status = Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.OK
        return jsonResp(body, status)
    }

    private fun getSystemInfo(): SystemInfo {
        val hostname = try {
            Build.MODEL ?: "Android Device"
        } catch (e: Exception) {
            "Android Device"
        }
        val version = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return SystemInfo(hostname, version)
    }

    private fun getBatteryInfo(): BatteryInfo {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            return BatteryInfo(true, percent, charging)
        } catch (e: Exception) {
            return BatteryInfo(true, 0, false)
        }
    }

    /**
     * CPU usage via /proc/stat delta sampling. The old `top -n 1` parse returns
     * 0 on many devices (top unavailable / different output format).
     */
    private fun getCpuUsage(): Float {
        return try {
            val c1 = readCpuTicks() ?: return 0f
            Thread.sleep(500)
            val c2 = readCpuTicks() ?: return 0f
            val totalDelta = c2.total - c1.total
            if (totalDelta <= 0) return 0f
            val idleDelta = c2.idle - c1.idle
            ((totalDelta - idleDelta).toFloat() / totalDelta) * 100f
        } catch (e: Exception) {
            0f
        }
    }

    private data class CpuTicks(val total: Long, val idle: Long)

    private fun readCpuTicks(): CpuTicks? {
        return try {
            for (line in java.io.File("/proc/stat").readLines()) {
                if (line.startsWith("cpu ")) {
                    val parts = line.trim().split(Regex("\\s+")).drop(1)
                    if (parts.size < 4) return null
                    val user = parts[0].toLongOrNull() ?: 0L
                    val nice = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val system = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val idle = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                    val iowait = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    val irq = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    val softirq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                    val steal = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                    val total = user + nice + system + idle + iowait + irq + softirq + steal
                    return CpuTicks(total, idle + iowait)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getMemoryInfo(): Triple<Float, Long, Long> {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalMB = memInfo.totalMem / (1024 * 1024)
            val usedMB = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
            val percent = (usedMB.toFloat() / totalMB.toFloat()) * 100
            return Triple(percent, usedMB, totalMB)
        } catch (e: Exception) {
            return Triple(0f, 0L, 0L)
        }
    }

    // ---- JSON response helper ----
    private fun jsonResp(data: Any, status: Response.Status = Response.Status.OK): Response {
        val json = gson.toJson(data)
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", json)
        return response
    }
}
