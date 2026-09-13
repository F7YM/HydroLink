package com.hydro.link.server.hydrokit

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 平台无关的 HTTP 请求描述（由 HttpServer 从 NanoHTTPD session 转换而来）。 */
data class HydroHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** 平台无关的 HTTP 响应。 */
data class HydroHttpResponse(
    val status: Int,
    val contentType: String = "application/json",
    val body: String = "",
    val headers: Map<String, String> = mapOf("Cache-Control" to "no-store"),
) {
    companion object {
        fun json(body: String, status: Int = 200) = HydroHttpResponse(status, "application/json", body)
        fun text(body: String, status: Int) = HydroHttpResponse(status, "text/plain; charset=utf-8", body)

        /** 204：插件表示"无需更新"（对应桌面端 view == nil）。 */
        fun noContent() = HydroHttpResponse(204, "application/json", "")
    }
}

/**
 * HydroKit 的 HTTP 路由（对应 desktop/internal/hydrokit/handler.go 与
 * api/worker.go 的公开部分）。
 *
 *	GET  /api/hydro/list                       → 启动器用的插件清单
 *	POST /api/hydro/{pkg}/{surface}/{item}/view     → 打开/首次渲染
 *	POST /api/hydro/{pkg}/{surface}/{item}/refresh  → 手动/回前台刷新
 *	POST /api/hydro/{pkg}/{surface}/{item}/event    → 用户交互后的可选新树
 *	GET  /api/hydro/webui/{pkg}/{path...}      → 插件包内 web/ 静态资源
 *
 * 请求上下文：
 *   · 头部 X-Hydro-Store 携带设备侧存储快照（手表上传）
 *   · 查询参数 shape/width/height/dpr/dt 描述请求方屏幕
 *   · event 请求体：{"event":{"handler":…},"params":{…}}
 *
 * 本类不依赖 NanoHTTPD / Android API，纯逻辑，便于单测。
 */
class HydroKitRoutes(
    private val runtime: Runtime,
    private val store: PluginStore? = null,
    private val log: (level: String, message: String) -> Unit = { _, _ -> },
) {
    private val gson = Gson()

    companion object {
        const val PREFIX = "/api/hydro"

        /** 桌面端 RoutePrefix（带尾斜杠）。 */
        const val ROUTE_PREFIX = "/api/hydro/"
    }

    /** 处理一个请求；路径不属于 HydroKit 时返回 null（交由服务器其它路由）。 */
    fun handle(req: HydroHttpRequest): HydroHttpResponse? {
        val path = req.path.split("?")[0]
        if (path != PREFIX && !path.startsWith(ROUTE_PREFIX)) return null

        if (req.method != "GET" && req.method != "POST") {
            return HydroHttpResponse.text("method not allowed", 405)
        }

        val rest = path.removePrefix(ROUTE_PREFIX).trim('/')
        if (rest == "list") {
            return HydroHttpResponse.json(gson.toJson(mapOf("plugins" to runtime.list())))
        }
        if (rest == "webui" || rest.startsWith("webui/")) {
            // 注意：rest 已经被 trim('/') 处理过，所以 ".../webui/{pkg}/" 到这里
            // 只剩 "webui/{pkg}" —— 空路径要当作默认入口 index.html
            return serveWebUi(rest.removePrefix("webui").trimStart('/'))
        }
        if (rest.startsWith("worker") || rest == "worker") {
            // worker 通道（poll/respond/…）由 WorkerRoutes 处理，这里不接
            return null
        }

        val parts = rest.split("/")
        if (parts.size != 4) {
            return HydroHttpResponse.text(
                "expected /api/hydro/{package}/{surface}/{item}/{action}", 400,
            )
        }
        val (pkg, surface, item, action) = parts

        val request = try {
            buildRequest(req, action)
        } catch (e: HydroException) {
            return HydroHttpResponse.text(e.message ?: "bad request", 400)
        }

        val view = try {
            runtime.dispatch(pkg, surface, item, request)
        } catch (e: HydroException) {
            log("info", "hydrokit: ${req.method} $path -> 404 (${e.message})")
            return HydroHttpResponse.text(e.message ?: "not found", 404)
        } ?: return HydroHttpResponse.noContent()

        log("info", "hydrokit: ${req.method} $path -> 200")
        return HydroHttpResponse.json(gson.toJson(view))
    }

    /** 由 action 得到 trigger，并填充 event/params/screen/store。 */
    @Throws(HydroException::class)
    fun buildRequest(req: HydroHttpRequest, action: String): Request {
        val trigger = when (action) {
            "refresh" -> Trigger.REFRESH
            "event" -> Trigger.EVENT
            else -> Trigger.OPEN // view 及任何其它 action 都按 open
        }
        var event: Event? = null
        val params = mutableMapOf<String, String>()
        if (action == "event") {
            val body = req.body
            if (!body.isNullOrBlank()) {
                val obj = try {
                    JsonParser.parseString(body)
                } catch (e: Exception) {
                    throw HydroException("event 请求体不是合法 JSON: ${e.message}")
                }
                if (obj.isJsonObject) {
                    val root = obj.asJsonObject
                    root.get("event")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ev ->
                        event = gson.fromJson(ev, Event::class.java)
                    }
                    root.get("params")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ps ->
                        ps.entrySet().forEach { (k, v) ->
                            params[k] = if (v.isJsonPrimitive) v.asJsonPrimitive.asString else v.toString()
                        }
                    }
                }
            }
        }

        val store = parseStoreHeader(req.headers["x-hydro-store"])

        return Request(
            trigger = trigger,
            event = event,
            params = params,
            screen = Screen(
                shape = req.query["shape"]?.takeIf { it.isNotBlank() },
                width = req.query["width"]?.toIntOrNull() ?: 0,
                height = req.query["height"]?.toIntOrNull() ?: 0,
                dpr = req.query["dpr"]?.toDoubleOrNull() ?: 0.0,
                deviceType = req.query["dt"]?.takeIf { it.isNotBlank() },
            ),
            store = store,
        )
    }

    /** X-Hydro-Store：JSON 对象；解析失败按"没有快照"处理（与桌面一致）。 */
    private fun parseStoreHeader(raw: String?): MutableMap<String, Any?>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val el = JsonParser.parseString(raw)
            if (!el.isJsonObject) return null
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(el, Map::class.java) as? MutableMap<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 插件 WebUI 静态资源：仅限包内 web/ 目录，防路径逃逸。
     * 对应桌面端 GET /api/hydro/webui/{package}/{path}。
     */
    private fun serveWebUi(rest: String): HydroHttpResponse {
        val st = store ?: return HydroHttpResponse.text("webui unavailable", 404)
        val idx = rest.indexOf('/')
        // 只给包名（路径为空）时取默认入口 index.html（与桌面端一致）
        val pkg: String
        val rel: String
        if (idx < 0) {
            pkg = rest
            rel = "index.html"
        } else {
            pkg = rest.substring(0, idx)
            rel = rest.substring(idx + 1).ifBlank { "index.html" }
        }
        if (pkg.isBlank()) return HydroHttpResponse.text("expected /webui/{package}/{path}", 400)
        val data = st.readFile(pkg, "web/$rel")
            ?: return HydroHttpResponse.text("not found", 404)
        return HydroHttpResponse(
            status = 200,
            contentType = contentTypeFor(rel),
            body = data.toString(Charsets.UTF_8),
        )
    }

    /** 极简静态类型表（插件 web/ 基本上是 html/js/css）。 */
    fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

/** 便于在测试/宿主里构造 JSON 对象。 */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val o = JsonObject()
    pairs.forEach { (k, v) ->
        when (v) {
            null -> o.add(k, com.google.gson.JsonNull.INSTANCE)
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            is String -> o.addProperty(k, v)
            is JsonObject -> o.add(k, v)
            else -> o.addProperty(k, v.toString())
        }
    }
    return o
}
