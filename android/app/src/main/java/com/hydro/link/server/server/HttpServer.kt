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
import com.hydro.link.server.util.ShizukuManager
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
                port = { activePort },
                dataManagerProvider = { dataManager },
                commandRunner = { cmd -> runCommandForPlugins(cmd) },
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
            portProvider = { activePort },
            log = { level, message -> addLog(message, level) },
        )
    }

    /** 实际监听端口（start 之后确定）；插件宿主与 ABI 上下文用它拼 URL。 */
    @Volatile
    private var activePort: Int = port

    /** 供 kit 能力端点执行命令：与 /api/commands/{id}/run 同一套逻辑（Shizuku / Shell）。 */
    private fun runCommandForPlugins(command: String): Triple<Boolean, String, Int> {
        val config = dataManager.loadConfig()
        if (config.useShizuku) {
            if (!ShizukuManager.isReady()) return Triple(false, "Shizuku 未就绪", -1)
            val (ok, out, code) = ShizukuManager.executeCommandSync(command)
            return Triple(ok, out, code)
        }
        return try {
            val process = java.lang.ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            Triple(code == 0, out, code)
        } catch (e: Exception) {
            Triple(false, "命令执行失败: ${e.message}", -1)
        }
    }
    private val musicController = MusicController(context)
    private val logBuffer = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val maxLogs = 100

    companion object {
        private const val API_LEVEL = 6
        private const val VERSION = "1.0.0"
    }

    fun addLog(message: String, level: String = "info") {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry("[$timestamp] $message", level)
        logBuffer.add(entry)
        if (logBuffer.size > maxLogs) logBuffer.removeAt(0)
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
                        "api_level" to API_LEVEL,
                        "version" to VERSION
                    ))

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
            return jsonResp(mapOf("status" to "error", "message" to "Missing device ID"), Response.Status.UNAUTHORIZED)
        }
        if (!dataManager.isTrusted(deviceId)) {
            return jsonResp(mapOf("status" to "error", "message" to "Device not trusted"), Response.Status.FORBIDDEN)
        }
        return block()
    }

    // ---- Internal request handler (for interconnect transport) ----

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
                // Health check
                cleanPath == "/api/ping" || cleanPath == "/ping" ->
                    gson.toJsonTree(mapOf("status" to "ok", "api_level" to API_LEVEL, "version" to VERSION))

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
                    gson.toJsonTree(mapOf("status" to "ok", "commands" to dataManager.loadCommands()))

                cleanPath.matches(Regex("/api/commands/\\d+/run")) || cleanPath.matches(Regex("/commands/\\d+/run")) -> {
                    val parts = cleanPath.split("/")
                    val cmdId = parts[parts.indexOfLast { it.matches(Regex("\\d+")) }].toInt()
                    val commands = dataManager.loadCommands()
                    if (cmdId >= 0 && cmdId < commands.size) {
                        val cmd = commands[cmdId]
                        val (success, output, _) = ShizukuManager.executeCommandSync(cmd.command)
                        gson.toJsonTree(mapOf("status" to if (success) "ok" else "error", "output" to output))
                    } else {
                        gson.toJsonTree(mapOf("status" to "error", "message" to "Invalid command ID"))
                    }
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

        // Check if rejected
        if (dataManager.isRejected(deviceId)) {
            return jsonResp(mapOf("status" to "rejected", "message" to "Device was rejected"))
        }

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
    private fun handleGetCommands(): Response {
        val commands = dataManager.loadCommands()
        val result = commands.mapIndexed { i, cmd ->
            mapOf(
                "id" to i,
                "name" to cmd.name.take(50),
                "command" to cmd.command.take(200)
            )
        }
        return jsonResp(mapOf("status" to "ok", "commands" to result))
    }

    private fun handleRunCommand(cmdId: Int): Response {
        val commands = dataManager.loadCommands()
        if (cmdId < 0 || cmdId >= commands.size) {
            return jsonResp(mapOf("status" to "error", "message" to "Invalid command ID"), Response.Status.BAD_REQUEST)
        }

        val cmd = commands[cmdId]
        val cmdStr = cmd.command.trim()
        if (cmdStr.isEmpty()) {
            return jsonResp(mapOf("status" to "error", "message" to "Empty command"), Response.Status.BAD_REQUEST)
        }

        val config = dataManager.loadConfig()

        // Use Shizuku if enabled and ready
        if (config.useShizuku) {
            if (!ShizukuManager.isReady()) {
                addLog("Shizuku 未就绪，命令执行失败", "error")
                return jsonResp(mapOf("status" to "error", "message" to "Shizuku not ready"), Response.Status.INTERNAL_ERROR)
            }

            addLog("执行命令(Shizuku): ${cmd.name}", "warning")
            val (success, output, returnCode) = ShizukuManager.executeCommandSync(cmdStr)
            if (success) {
                addLog("命令完成: 返回码 $returnCode", "success")
                return jsonResp(mapOf(
                    "status" to "ok",
                    "returncode" to returnCode,
                    "output" to output.take(1000)
                ))
            } else {
                addLog("命令失败: $output", "error")
                return jsonResp(mapOf("status" to "error", "message" to "Command failed: $output"), Response.Status.INTERNAL_ERROR)
            }
        }

        // Fallback: direct execution (app user privileges)
        try {
            addLog("执行命令: ${cmd.name}", "warning")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmdStr))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText() +
                    BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            val returnCode = process.exitValue()
            addLog("命令完成: 返回码 $returnCode", "success")
            return jsonResp(mapOf(
                "status" to "ok",
                "returncode" to returnCode,
                "output" to output.take(1000)
            ))
        } catch (e: Exception) {
            addLog("命令失败", "error")
            return jsonResp(mapOf("status" to "error", "message" to "Command failed"), Response.Status.INTERNAL_ERROR)
        }
    }

    // ---- System info helpers ----
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
