package com.hydro.link.server.hydrokit

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hydro.link.server.hydrokit.net.SshManager
import com.hydro.link.server.hydrokit.net.TcpManager

/**
 * 宿主提供给插件的「kit 能力」。桌面端直接调用 Go 里的 system/music/lock 等实现；
 * 安卓端由 App 层实现（通知用 NotificationManager、音乐用 MusicController、
 * 锁屏用无障碍服务、命令用 DataManager + CommandService）。
 *
 * 抽成接口是为了让路由层可单测（用假实现），也避免 hydrokit 包依赖 Android API。
 */
interface KitCapabilities {
    /** 性能信息（system 权限）：cpu / memPercent / memUsedMB / memTotalMB。 */
    fun perf(): Map<String, Any?>

    /** 当前媒体信息（music 权限）：hasMedia/title/artist/playing/artUrl。 */
    fun music(): Map<String, Any?>

    /** 控制媒体（music 权限），返回是否成功。 */
    fun musicAction(action: String): Boolean

    /** 发送系统通知（notify 权限）。 */
    fun notify(title: String, text: String): Boolean

    /** 锁屏（lock 权限）。 */
    fun lock(): Boolean

    /** 命令列表（commands 权限）。 */
    fun commandList(): List<Map<String, Any?>>

    /** 执行命令（commands 权限），返回 {success, exitCode, output}。 */
    fun runCommand(index: Int): Map<String, Any?>

    /** 局域网唤醒（wol 权限）：发送 Magic Packet。 */
    fun wolWake(mac: String, host: String?, port: Int?): Map<String, Any?>

    /** 探测设备在线状态（wol 权限）：TCP 或 ICMP 探测。 */
    fun wolProbe(host: String, port: Int?, timeout: Int?): Map<String, Any?>

    /** 发送 UDP 数据报文（udp 权限）。 */
    fun udpSend(host: String, port: Int, data: ByteArray, broadcast: Boolean): Map<String, Any?>

    /** 获取屏幕尺寸与分辨率信息（input 权限）。 */
    fun inputScreen(): Map<String, Any?>

    /** 获取当前光标/虚拟指针坐标（input 权限）。 */
    fun inputCursor(): Map<String, Any?>

    /** 绝对移动光标/指针到 (x, y)（input 权限）。 */
    fun inputMove(x: Float, y: Float): Map<String, Any?>

    /** 相对位移光标/指针 (dx, dy)（input 权限）。 */
    fun inputMoveBy(dx: Float, dy: Float): Map<String, Any?>

    /** 在指定位置或当前位置点击（input 权限）。 */
    fun inputClick(x: Float?, y: Float?, button: String, count: Int): Map<String, Any?>

    /** 拖拽/手势滑动（input 权限）。 */
    fun inputDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Map<String, Any?>

    /** 滚轮滚动（input 权限）。 */
    fun inputScroll(dx: Float, dy: Float): Map<String, Any?>

    /** 单键触发（input 权限）。 */
    fun inputKey(key: String): Map<String, Any?>

    /** 组合快捷键（input 权限）。 */
    fun inputShortcut(keys: List<String>): Map<String, Any?>

    /** 文本键入（input 权限）。 */
    fun inputText(text: String): Map<String, Any?>

    /** 服务器基地址（形如 http://192.168.1.5:9000），供 ABI 上下文使用。 */
    fun serverBase(): String

    companion object {
        /** 测试/无能力环境下的空实现。 */
        val NONE = object : KitCapabilities {
            override fun perf() = mapOf<String, Any?>("cpu" to 0.0, "memPercent" to 0.0, "memUsedMB" to 0, "memTotalMB" to 0)
            override fun music() = mapOf<String, Any?>("hasMedia" to false, "title" to "", "artist" to "", "playing" to false, "artUrl" to "")
            override fun musicAction(action: String) = false
            override fun notify(title: String, text: String) = false
            override fun lock() = false
            override fun commandList(): List<Map<String, Any?>> = emptyList()
            override fun runCommand(index: Int) = mapOf<String, Any?>("success" to false, "exitCode" to -1, "output" to "无可执行能力")
            override fun wolWake(mac: String, host: String?, port: Int?) = mapOf<String, Any?>("ok" to true)
            override fun wolProbe(host: String, port: Int?, timeout: Int?) = mapOf<String, Any?>("online" to false, "rttMs" to -1)
            override fun udpSend(host: String, port: Int, data: ByteArray, broadcast: Boolean) = mapOf<String, Any?>("ok" to true, "bytesSent" to data.size)
            override fun inputScreen() = mapOf<String, Any?>("width" to 1920, "height" to 1080, "dpr" to 1.0)
            override fun inputCursor() = mapOf<String, Any?>("x" to 0.0, "y" to 0.0)
            override fun inputMove(x: Float, y: Float) = mapOf<String, Any?>("ok" to true, "x" to x, "y" to y)
            override fun inputMoveBy(dx: Float, dy: Float) = mapOf<String, Any?>("ok" to true, "dx" to dx, "dy" to dy)
            override fun inputClick(x: Float?, y: Float?, button: String, count: Int) = mapOf<String, Any?>("ok" to true)
            override fun inputDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = mapOf<String, Any?>("ok" to true)
            override fun inputScroll(dx: Float, dy: Float) = mapOf<String, Any?>("ok" to true)
            override fun inputKey(key: String) = mapOf<String, Any?>("ok" to true)
            override fun inputShortcut(keys: List<String>) = mapOf<String, Any?>("ok" to true)
            override fun inputText(text: String) = mapOf<String, Any?>("ok" to true)
            override fun serverBase() = ""
        }
    }
}

/**
 * kit 能力端点（对应桌面端实现的
 * store / context / system / notify / music / lock / commands / asset）。
 *
 * 权限门控与桌面一致：能力端点先查 manifest.permissions，缺权限返回 403
 * `{"error":"权限不足: xxx"}`；插件不存在返回 403。
 */
class KitRoutes(
    private val store: PluginStore,
    bridge: WorkerBridge,
    private val caps: KitCapabilities = KitCapabilities.NONE,
    private val log: (String) -> Unit = {},
    /** 宿主平台信息（与请求里的 req.host 同源）。 */
    private val host: () -> Map<String, String> = { emptyMap() },
) {
    private val gson = Gson()
    val workerRoutes = WorkerRoutes(bridge)

    companion object {
        const val PERM_STORAGE = "storage"
        const val PERM_NOTIFY = "notify"
        const val PERM_MUSIC = "music"
        const val PERM_LOCK = "lock"
        const val PERM_COMMANDS = "commands"
        const val PERM_SYSTEM = "system"
        const val PERM_FETCH = "fetch"
        const val PERM_WOL = "wol"
        const val PERM_UDP = "udp"
        const val PERM_INPUT = "input"
        const val PERM_TCP = "tcp"
        const val PERM_SSH = "ssh"

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
    }

    /** 处理 worker 端点（path 前缀 /api/hydro/worker）；非本前缀返回 null。 */
    fun handle(req: HydroHttpRequest): HydroHttpResponse? {
        val prefix = HydroKitRoutes.PREFIX + "/worker"
        val path = req.path.split("?")[0]
        if (path != prefix && !path.startsWith("$prefix/")) return null

        // poll / respond 交给 bridge
        val bridged = workerRoutes.handle(req)
        val rest = path.removePrefix(prefix).trimStart('/')
        if (rest == "poll" || rest == "respond") return bridged

        return when (rest) {
            "platform" -> handlePlatform()
            "asset" -> handleAsset(req)
            "store" -> handleStore(req)
            "context" -> handleContext(req)
            "system" -> handleSystem(req)
            "notify" -> handleNotify(req)
            "music" -> handleMusic(req)
            "lock" -> handleLock(req)
            "commands" -> handleCommands(req)
            "wol/wake" -> handleWolWake(req)
            "wol/probe" -> handleWolProbe(req)
            "udp/send" -> handleUdpSend(req)
            "input" -> handleInput(req)
            else -> {
                if (rest == "tcp" || rest.startsWith("tcp/")) handleTcp(req, rest)
                else if (rest == "ssh" || rest.startsWith("ssh/")) handleSsh(req, rest)
                else HydroHttpResponse.json("""{"error":"worker endpoint not found"}""", 404)
            }
        }
    }

    // ---- 权限门控 ----

    private fun permissionOf(packageId: String): List<String>? =
        runCatching { store.manifest(packageId).permissions }.getOrNull()

    private fun requirePermission(packageId: String, perm: String): HydroHttpResponse? {
        val perms = permissionOf(packageId)
            ?: return HydroHttpResponse.json("""{"error":"plugin 不存在或清单不可读"}""", 403)
        if (!perms.contains(perm)) {
            return HydroHttpResponse.json(
                """{"error":${gson.toJson("权限不足: $perm")}}""", 403,
            )
        }
        return null
    }

    // ---- 宿主平台信息 ----

    /**
     * `GET /api/hydro/worker/platform`
     *
     * 插件 WebUI 用它判断自己跑在什么系统上，从而只显示本平台有意义的能力
     * （例如 SPlayer 歌词集成只在桌面端有）。与桌面端实现
     * 的 handlePlatform 同构。
     */
    fun handlePlatform(): HydroHttpResponse {
        val info = host()
        if (info.isEmpty()) {
            return HydroHttpResponse.json("""{"error":"宿主平台信息不可用"}""", 503)
        }
        return HydroHttpResponse.json(gson.toJson(info))
    }

    // ---- 资产（worker 拉取入口代码 / wasm / 其它包内文件）----

    /**
     * `GET /api/hydro/worker/asset?package=<id>&path=<相对路径>`
     *
     * 只允许包内文件：PluginStore.readFile 内部会做 canonical 前缀校验，越界返回 null。
     */
    fun handleAsset(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "GET") return HydroHttpResponse.text("method not allowed", 405)
        val pkg = req.query["package"].orEmpty()
        val rel = req.query["path"].orEmpty()
        if (pkg.isBlank() || rel.isBlank()) {
            return HydroHttpResponse.json("""{"error":"缺少 package/path"}""", 400)
        }
        if (looksLikeEscape(rel)) {
            return HydroHttpResponse.json("""{"error":"非法路径"}""", 400)
        }
        val data = store.readFile(pkg, rel)
            ?: return HydroHttpResponse.json("""{"error":"文件不存在"}""", 404)
        val ct = contentTypeFor(rel)
        // 文本资产走字符串（日志/调试可读），二进制（wasm 等）必须按字节原样返回，
        // 否则会被 UTF-8 重新编码破坏
        return if (isTextual(ct)) {
            HydroHttpResponse(200, ct, String(data, Charsets.UTF_8), null)
        } else {
            HydroHttpResponse.binary(data, ct)
        }
    }

    private fun looksLikeEscape(rel: String): Boolean {
        val norm = rel.replace('\\', '/')
        if (norm.startsWith("/")) return true
        return norm.split('/').any { it == ".." }
    }

    private fun isTextual(contentType: String): Boolean =
        contentType.startsWith("text/") || contentType.contains("javascript") || contentType == "application/json"

    fun contentTypeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "wasm" -> "application/wasm"
            "json" -> "application/json"
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }

    // ---- kit.storage（server 作用域）----

    fun handleStore(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        val scope = body.str("scope").ifBlank { "server" }
        if (scope == "watch") {
            // 手表作用域由容器落盘（随响应 storage 回传），这里不经手
            return HydroHttpResponse.json("""{"error":"watch 作用域由容器处理"}""", 400)
        }
        requirePermission(pkg, PERM_STORAGE)?.let { return it }

        val op = body.str("op")
        val key = body.str("key")
        return when (op) {
            "get" -> {
                val (found, value) = store.storeGet(pkg, key)
                HydroHttpResponse.json(
                    if (found) gson.toJson(mapOf("found" to true, "value" to value))
                    // 与桌面一致：未命中也回 value:null
                    else """{"found":false,"value":null}"""
                )
            }
            "set" -> {
                val value: Any? = body.get("value")?.let { el ->
                    if (el.isJsonNull) null else gson.fromJson(el, Any::class.java)
                }
                store.storeSet(pkg, key, value)
                HydroHttpResponse.json("""{"ok":true}""")
            }
            "remove" -> {
                store.storeRemove(pkg, key)
                HydroHttpResponse.json("""{"ok":true}""")
            }
            "clear" -> {
                for (k in store.storeAll(pkg).keys.toList()) store.storeRemove(pkg, k)
                HydroHttpResponse.json("""{"ok":true}""")
            }
            else -> HydroHttpResponse.json("""{"error":"未知操作: $op"}""", 400)
        }
    }

    // ---- ABI 上下文（WASM 插件用；JS 插件也可读）----

    fun handleContext(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        val surface = body.str("surface")
        val item = body.str("item")
        val perms = permissionOf(pkg)
            ?: return HydroHttpResponse.json("""{"error":"plugin 不存在或清单不可读"}""", 403)

        val now = System.currentTimeMillis() / 1000
        val ctx = LinkedHashMap<String, Any?>()
        ctx["abi"] = 1
        ctx["package"] = pkg
        ctx["surface"] = surface
        ctx["item"] = item
        ctx["time"] = mapOf(
            "unix" to now,
            "hour" to java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            "minute" to java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE),
        )
        ctx["server"] = mapOf("base" to caps.serverBase())
        // 宿主平台：插件按 os 分支（linux/windows/darwin/android）
        ctx["host"] = host()
        body.get("req")?.let { ctx["req"] = gson.fromJson(it, Any::class.java) }
        if (perms.contains(PERM_SYSTEM)) ctx["system"] = caps.perf()
        if (perms.contains(PERM_MUSIC)) ctx["music"] = caps.music()
        if (perms.contains(PERM_STORAGE)) ctx["storage"] = store.storeAll(pkg)
        return HydroHttpResponse.json(gson.toJson(ctx))
    }

    // ---- 其它能力端点 ----

    fun handleSystem(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_SYSTEM)?.let { return it }
        return when (body.str("op")) {
            "perf" -> HydroHttpResponse.json(gson.toJson(caps.perf()))
            // 与桌面一致：system op=music 返回媒体信息（hasMedia/title/artist/playing/artUrl）
            "music" -> HydroHttpResponse.json(gson.toJson(caps.music()))
            else -> HydroHttpResponse.json("""{"error":"未知操作: ${body.str("op")}"}""", 400)
        }
    }

    fun handleNotify(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_NOTIFY)?.let { return it }
        // 回包形状与桌面统一：成功 200 {"ok":true}，失败 500 {"error":"通知失败"}
        if (!caps.notify(body.str("title"), body.str("text"))) {
            return HydroHttpResponse.json("""{"error":"通知失败"}""", 500)
        }
        return HydroHttpResponse.json("""{"ok":true}""")
    }

    fun handleMusic(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_MUSIC)?.let { return it }
        // kit.music.now()：读当前媒体（与桌面 ctx.music 同构）
        if (body.str("op") == "now") return HydroHttpResponse.json(gson.toJson(caps.music()))
        val action = body.str("action")
        // 动作白名单与桌面一致：play/pause/playpause/next/previous
        if (action !in setOf("play", "pause", "playpause", "next", "previous")) {
            return HydroHttpResponse.json("""{"error":"无效动作: $action"}""", 400)
        }
        if (!caps.musicAction(action)) {
            return HydroHttpResponse.json("""{"error":"没有活动的媒体会话"}""", 500)
        }
        return HydroHttpResponse.json(gson.toJson(mapOf("ok" to true, "action" to action)))
    }

    fun handleLock(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_LOCK)?.let { return it }
        // 与桌面统一：成功 200 {"ok":true}，失败 500 {"error":"锁屏失败"}
        if (!caps.lock()) {
            return HydroHttpResponse.json("""{"error":"锁屏失败"}""", 500)
        }
        return HydroHttpResponse.json("""{"ok":true}""")
    }

    fun handleCommands(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_COMMANDS)?.let { return it }
        return when (body.str("op")) {
            "list" -> HydroHttpResponse.json(gson.toJson(mapOf("commands" to caps.commandList())))
            "run" -> {
                val index = body.get("index")?.let { if (it.isJsonPrimitive) it.asInt else -1 } ?: -1
                val size = caps.commandList().size
                if (index < 0 || index >= size) {
                    return HydroHttpResponse.json("""{"error":"无效命令序号"}""", 400)
                }
                val r = caps.runCommand(index)
                val code = (r["exitCode"] as? Number)?.toInt() ?: -1
                val output = (r["output"] as? String) ?: ""
                // 语义（与桌面统一）：非 0 退出码是命令自己的执行结果，按 200 +
                // returncode 回包；ExitCode == -1 才是宿主级失败（能力不可用/读取失败）。
                if (code == -1) {
                    return HydroHttpResponse.json(
                        gson.toJson(mapOf("error" to "命令执行失败", "output" to output)), 500,
                    )
                }
                HydroHttpResponse.json(gson.toJson(mapOf("returncode" to code, "output" to output)))
            }
            else -> HydroHttpResponse.json("""{"error":"未知操作: ${body.str("op")}"}""", 400)
        }
    }

    // ---- 局域网唤醒（wol 权限）----

    fun handleWolWake(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_WOL)?.let { return it }
        val mac = body.str("mac").trim()
        if (!MAC_REGEX.matches(mac)) {
            return HydroHttpResponse.json("""{"error":"无效的 MAC 地址"}""", 400)
        }
        val host = body.str("host").takeIf { it.isNotBlank() } ?: "255.255.255.255"
        val port = body.get("port")?.let { if (it.isJsonPrimitive) it.asInt else 9 } ?: 9
        if (port !in 1..65535) {
            return HydroHttpResponse.json("""{"error":"无效端口"}""", 400)
        }
        val res = caps.wolWake(mac, host, port)
        if (res.containsKey("error")) {
            return HydroHttpResponse.json(gson.toJson(res), 500)
        }
        return HydroHttpResponse.json(gson.toJson(res))
    }

    fun handleWolProbe(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_WOL)?.let { return it }
        val host = body.str("host").trim()
        if (host.isBlank()) {
            return HydroHttpResponse.json("""{"error":"缺少 host"}""", 400)
        }
        val port = body.get("port")?.let { if (it.isJsonPrimitive) it.asInt else null }
        if (port != null && port !in 1..65535) {
            return HydroHttpResponse.json("""{"error":"无效端口"}""", 400)
        }
        val timeout = body.get("timeout")?.let { if (it.isJsonPrimitive) it.asInt else 1000 }?.coerceIn(100, 10000) ?: 1000
        val res = caps.wolProbe(host, port, timeout)
        return HydroHttpResponse.json(gson.toJson(res))
    }

    fun handleUdpSend(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_UDP)?.let { return it }
        val host = body.str("host").trim()
        if (host.isBlank()) {
            return HydroHttpResponse.json("""{"error":"缺少 host"}""", 400)
        }
        val port = body.get("port")?.let { if (it.isJsonPrimitive) it.asInt else -1 } ?: -1
        if (port !in 1..65535) {
            return HydroHttpResponse.json("""{"error":"无效端口"}""", 400)
        }
        val rawData = body.str("data")
        val encoding = body.str("encoding").lowercase().ifBlank { "utf-8" }
        val bytes = try {
            when (encoding) {
                "utf-8", "utf8" -> rawData.toByteArray(Charsets.UTF_8)
                "base64" -> java.util.Base64.getDecoder().decode(rawData)
                "hex" -> hexToBytes(rawData)
                else -> return HydroHttpResponse.json("""{"error":"不支持的编码: $encoding"}""", 400)
            }
        } catch (e: Exception) {
            return HydroHttpResponse.json("""{"error":"数据解码失败"}""", 400)
        }
        val broadcast = body.get("broadcast")?.let { if (it.isJsonPrimitive) it.asBoolean else false }
            ?: (host == "255.255.255.255" || host.endsWith(".255"))
        val res = caps.udpSend(host, port, bytes, broadcast)
        if (res.containsKey("error")) {
            return HydroHttpResponse.json(gson.toJson(res), 500)
        }
        return HydroHttpResponse.json(gson.toJson(res))
    }

    fun handleInput(req: HydroHttpRequest): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_INPUT)?.let { return it }

        val op = body.str("op")
        val res = when (op) {
            "screen" -> caps.inputScreen()
            "cursor" -> caps.inputCursor()
            "move" -> {
                val x = body.get("x")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 x 坐标"}""", 400)
                val y = body.get("y")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 y 坐标"}""", 400)
                caps.inputMove(x, y)
            }
            "move_by" -> {
                val dx = body.get("dx")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                val dy = body.get("dy")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                caps.inputMoveBy(dx, dy)
            }
            "click", "tap" -> {
                val x = body.get("x")?.takeIf { it.isJsonPrimitive }?.asFloat
                val y = body.get("y")?.takeIf { it.isJsonPrimitive }?.asFloat
                val button = body.str("button").ifBlank { "left" }
                val count = body.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
                caps.inputClick(x, y, button, count)
            }
            "drag", "swipe" -> {
                val x1 = body.get("x1")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 x1 坐标"}""", 400)
                val y1 = body.get("y1")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 y1 坐标"}""", 400)
                val x2 = body.get("x2")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 x2 坐标"}""", 400)
                val y2 = body.get("y2")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: return HydroHttpResponse.json("""{"error":"缺少 y2 坐标"}""", 400)
                val duration = body.get("duration")?.takeIf { it.isJsonPrimitive }?.asLong ?: 300L
                caps.inputDrag(x1, y1, x2, y2, duration.coerceIn(10L, 10000L))
            }
            "scroll" -> {
                val dx = body.get("dx")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                val dy = body.get("dy")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                caps.inputScroll(dx, dy)
            }
            "key" -> {
                val key = body.str("key")
                if (key.isBlank()) return HydroHttpResponse.json("""{"error":"缺少 key"}""", 400)
                caps.inputKey(key)
            }
            "shortcut" -> {
                val arr = body.getAsJsonArray("keys")
                if (arr == null || arr.isEmpty) return HydroHttpResponse.json("""{"error":"缺少 keys"}""", 400)
                val keys = arr.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                caps.inputShortcut(keys)
            }
            "text" -> {
                val text = body.str("text")
                caps.inputText(text)
            }
            else -> return HydroHttpResponse.json("""{"error":"未知操作: $op"}""", 400)
        }

        if (res.containsKey("error")) {
            return HydroHttpResponse.json(gson.toJson(res), 500)
        }
        return HydroHttpResponse.json(gson.toJson(res))
    }

    fun handleTcp(req: HydroHttpRequest, rest: String): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_TCP)?.let { return it }

        val op = if (rest.startsWith("tcp/")) {
            rest.removePrefix("tcp/").trim()
        } else {
            body.str("op").ifBlank { "write" }
        }

        return try {
            when (op) {
                "connect", "open" -> {
                    val host = body.str("host").trim()
                    val port = body.get("port")?.let { if (it.isJsonPrimitive) it.asInt else -1 } ?: -1
                    val timeout = body.get("timeout")?.let { if (it.isJsonPrimitive) it.asInt else 10_000 } ?: 10_000
                    if (host.isBlank()) return HydroHttpResponse.json("""{"error":"缺少 host"}""", 400)
                    if (port !in 1..65535) return HydroHttpResponse.json("""{"error":"无效端口"}""", 400)
                    val res = TcpManager.connect(pkg, host, port, timeout)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "id" to res.id,
                        "remote" to res.remote,
                    )))
                }
                "read" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    val timeout = body.get("timeout")?.let { if (it.isJsonPrimitive) it.asInt else 1000 } ?: 1000
                    val encoding = body.str("encoding").ifBlank { "utf-8" }
                    val res = TcpManager.read(pkg, id, timeout, encoding)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "data" to res.data,
                        "eof" to res.eof,
                        "bytesRead" to res.bytesRead,
                    )))
                }
                "write" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    val data = if (body.has("data")) body.str("data") else body.str("text")
                    val encoding = body.str("encoding").ifBlank { "utf-8" }
                    val written = TcpManager.write(pkg, id, data, encoding)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "bytesWritten" to written,
                    )))
                }
                "close" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    TcpManager.close(pkg, id)
                    HydroHttpResponse.json("""{"ok":true}""")
                }
                "list" -> {
                    val list = TcpManager.list(pkg)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "connections" to list,
                    )))
                }
                else -> HydroHttpResponse.json("""{"error":"未知 TCP 操作: $op"}""", 400)
            }
        } catch (e: Exception) {
            HydroHttpResponse.json(gson.toJson(mapOf("error" to (e.message ?: "TCP 操作失败"))), 500)
        }
    }

    fun handleSsh(req: HydroHttpRequest, rest: String): HydroHttpResponse {
        if (req.method != "POST") return HydroHttpResponse.text("method not allowed", 405)
        val body = parseJsonBody(req) ?: return HydroHttpResponse.json("""{"error":"invalid body"}""", 400)
        val pkg = body.str("package")
        requirePermission(pkg, PERM_SSH)?.let { return it }

        val op = if (rest.startsWith("ssh/")) {
            rest.removePrefix("ssh/").trim()
        } else {
            body.str("op").ifBlank { "read" }
        }

        return try {
            when (op) {
                "open", "connect" -> {
                    val host = body.str("host").trim()
                    val port = body.get("port")?.let { if (it.isJsonPrimitive) it.asInt else 22 } ?: 22
                    val user = body.str("user").ifBlank { body.str("username") }.trim()
                    val password = body.str("password").takeIf { it.isNotBlank() }
                    val agent = body.get("agent")?.let { if (it.isJsonPrimitive) it.asBoolean else false } ?: false
                    val keyFile = body.str("keyFile").takeIf { it.isNotBlank() }
                    val privateKey = body.str("privateKey").takeIf { it.isNotBlank() }
                    val passphrase = body.str("passphrase").takeIf { it.isNotBlank() }
                    val fingerprint = body.str("fingerprint").takeIf { it.isNotBlank() }
                    val term = body.str("term").ifBlank { "xterm-256color" }
                    val cols = body.get("cols")?.let { if (it.isJsonPrimitive) it.asInt else 80 } ?: 80
                    val rows = body.get("rows")?.let { if (it.isJsonPrimitive) it.asInt else 24 } ?: 24
                    val timeout = body.get("timeout")?.let { if (it.isJsonPrimitive) it.asInt else 10_000 } ?: 10_000

                    if (host.isBlank()) return HydroHttpResponse.json("""{"error":"缺少 host"}""", 400)
                    if (user.isBlank()) return HydroHttpResponse.json("""{"error":"缺少 user"}""", 400)
                    if (port !in 1..65535) return HydroHttpResponse.json("""{"error":"无效端口"}""", 400)

                    val params = SshManager.OpenParams(
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
                    val res = SshManager.open(pkg, params)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "id" to res.id,
                        "remote" to res.remote,
                        "fingerprint" to res.fingerprint,
                        "banner" to res.banner,
                    )))
                }
                "read" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    val timeout = body.get("timeout")?.let { if (it.isJsonPrimitive) it.asInt else 1000 } ?: 1000
                    val encoding = body.str("encoding").ifBlank { "utf-8" }
                    val res = SshManager.read(pkg, id, timeout, encoding)
                    val map = mutableMapOf<String, Any?>(
                        "ok" to true,
                        "data" to res.data,
                        "closed" to res.closed,
                        "bytesRead" to res.bytesRead,
                    )
                    if (res.exitCode != null) map["exitCode"] = res.exitCode
                    HydroHttpResponse.json(gson.toJson(map))
                }
                "write" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    val data = if (body.has("data")) body.str("data") else body.str("text")
                    val encoding = body.str("encoding").ifBlank { "utf-8" }
                    val written = SshManager.write(pkg, id, data, encoding)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "bytesWritten" to written,
                    )))
                }
                "resize" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    val cols = body.get("cols")?.let { if (it.isJsonPrimitive) it.asInt else 80 } ?: 80
                    val rows = body.get("rows")?.let { if (it.isJsonPrimitive) it.asInt else 24 } ?: 24
                    SshManager.resize(pkg, id, cols, rows)
                    HydroHttpResponse.json("""{"ok":true}""")
                }
                "close" -> {
                    val id = body.str("id").takeIf { it.isNotBlank() }
                    SshManager.close(pkg, id)
                    HydroHttpResponse.json("""{"ok":true}""")
                }
                "list" -> {
                    val list = SshManager.list(pkg)
                    HydroHttpResponse.json(gson.toJson(mapOf(
                        "ok" to true,
                        "sessions" to list,
                    )))
                }
                else -> HydroHttpResponse.json("""{"error":"未知 SSH 操作: $op"}""", 400)
            }
        } catch (e: Exception) {
            HydroHttpResponse.json(gson.toJson(mapOf("error" to (e.message ?: "SSH 操作失败"))), 500)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(" ", "").replace(":", "")
        require(s.length % 2 == 0) { "Hex length must be even" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseJsonBody(req: HydroHttpRequest): JsonObject? {
        val raw = req.body ?: return null
        if (raw.isBlank()) return null
        return try {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
