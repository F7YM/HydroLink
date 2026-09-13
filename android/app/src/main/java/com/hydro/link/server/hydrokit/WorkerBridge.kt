package com.hydro.link.server.hydrokit

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Worker 通道（对应 desktop/internal/hydrokit/bridge.go）。
 *
 * 插件 worker（安卓端是隐藏 WebView 里的 JS/WASM 宿主）不能监听端口，因此：
 *   · 它长轮询 `GET /api/hydro/worker/poll?package=<id>` 领取请求
 *   · 处理完 `POST /api/hydro/worker/respond` 回填结果
 * 请求在超时前一直等待；worker 未连接时快速失败并触发 OnNeedWake（宿主据此拉起 worker）。
 *
 * 计时参数与桌面一致：请求超时 10s，单次 poll 最多等 25s、每 800ms 复查一次。
 *
 * 本类为纯 JVM 逻辑（线程 + 队列），不依赖 Android API，可单测。
 */
class WorkerBridge(
    private val requestTimeoutMs: Long = 10_000,
    private val pollWindowMs: Long = 25_000,
    private val pollIntervalMs: Long = 800,
    private val log: (String) -> Unit = {},
) {
    private class Pending(
        val id: Long,
        val payload: String,
    ) {
        val done = LinkedBlockingDeque<Reply>(1)
    }

    private class Reply(val data: String?, val error: String?)

    private class Conn {
        val queue = LinkedBlockingDeque<Pending>()
        val pending = ConcurrentHashMap<Long, Pending>()
        val notify = LinkedBlockingDeque<Unit>(1)
        val ids = java.util.concurrent.atomic.AtomicLong(0)
    }

    private val conns = ConcurrentHashMap<String, Conn>()

    /** 请求到达但 worker 不在线时的回调（宿主用它唤醒 WebView 里的 worker）。 */
    @Volatile
    var onNeedWake: ((String) -> Unit)? = null

    /** 建立/复用某插件的连接会话（worker 首次轮询或首次投递时创建）。 */
    private fun ensureConn(packageId: String): Conn = conns.computeIfAbsent(packageId) {
        log("hydrokit: worker connected for $packageId")
        Conn()
    }

    /** worker 是否已连接（用于 UI 状态显示）。 */
    fun isConnected(packageId: String): Boolean = conns.containsKey(packageId)

    /** 断开某插件的 worker（卸载插件 / 宿主销毁时调用）。 */
    fun disconnect(packageId: String) {
        conns.remove(packageId)
    }

    fun connectedPackages(): Set<String> = conns.keys.toSet()

    /**
     * 向插件投递一次调用并等待结果（对应 bridge.Call）。
     * worker 不在线时立刻返回错误并触发唤醒回调。
     */
    @Throws(HydroException::class)
    fun call(packageId: String, action: String, payload: String): String {
        val conn = conns[packageId]
        if (conn == null) {
            log("hydrokit: plugin $packageId worker offline (request dropped, wake fired)")
            onNeedWake?.let { cb -> Thread { cb(packageId) }.start() }
            throw HydroException("hydrokit: plugin $packageId worker is offline")
        }
        val id = conn.ids.incrementAndGet()
        val pending = Pending(id, payload)
        conn.pending[id] = pending
        conn.queue.add(pending)
        conn.notify.poll() // 唤醒正在等待的 poll（有容量 1，重复唤醒无副作用）

        val reply = pending.done.poll(requestTimeoutMs, TimeUnit.MILLISECONDS)
            ?: run {
                conn.pending.remove(id)
                throw HydroException("hydrokit: plugin $packageId 响应超时（${requestTimeoutMs}ms）")
            }
        if (reply.error != null) {
            log("hydrokit: $packageId worker returned error: ${reply.error}")
            throw HydroException("plugin: ${reply.error}")
        }
        return reply.data ?: ""
    }

    /**
     * worker 领取请求：`GET /api/hydro/worker/poll?package=<id>`。
     * 有请求 → 200 `{"req":{"id":1,"surface":"app","item":"main","req":{…}}}`；
     * 等到窗口结束仍没有 → 200 `{}`（桌面端也是 `{}`，不是 204）。
     */
    fun handlePoll(req: HydroHttpRequest): HydroHttpResponse {
        val pkg = req.query["package"].orEmpty()
        if (pkg.isBlank()) return HydroHttpResponse.text("missing package", 400)
        if (!validPackageId(pkg)) return HydroHttpResponse.text("invalid package", 400)

        val conn = ensureConn(pkg)
        val deadline = System.currentTimeMillis() + pollWindowMs
        var pending: Pending? = null
        while (System.currentTimeMillis() < deadline) {
            pending = conn.queue.poll()
            if (pending != null) break
            conn.notify.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
        }
        if (pending == null) return HydroHttpResponse.json("{}")

        log("hydrokit: delivered $pkg req id=${pending.id}")
        // payload 是宿主投递时的 {surface,item,req} 三元组，原样嵌回
        val payload = try {
            JsonParser.parseString(pending.payload).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        }
        val reqMsg = com.google.gson.JsonObject()
        reqMsg.addProperty("id", pending.id)
        payload?.get("surface")?.takeIf { it.isJsonPrimitive }?.let { reqMsg.add("surface", it) }
        payload?.get("item")?.takeIf { it.isJsonPrimitive }?.let { reqMsg.add("item", it) }
        payload?.get("req")?.let { reqMsg.add("req", it) }
        val out = com.google.gson.JsonObject()
        out.add("req", reqMsg)
        return HydroHttpResponse.json(Gson().toJson(out))
    }

    /**
     * worker 回填结果：`POST /api/hydro/worker/respond`
     * 请求体 `{"package":id,"id":1,"result":{…}}` 或 `{"…","error":"…"}`。
     */
    fun handleRespond(req: HydroHttpRequest): HydroHttpResponse {
        val body = req.body
        if (body.isNullOrBlank()) return HydroHttpResponse.text("invalid body", 400)
        val obj: JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return HydroHttpResponse.text("invalid body", 400)
        }
        if (!obj.isJsonObject) return HydroHttpResponse.text("invalid body", 400)
        val root = obj.asJsonObject
        val pkg = root.get("package")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val id = root.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val conn = conns[pkg] ?: return HydroHttpResponse.json("""{"status":"unknown_package"}""")
        val pending = conn.pending.remove(id)
            ?: return HydroHttpResponse.json("""{"status":"unknown_req"}""")

        val err = root.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        if (!err.isNullOrBlank()) {
            log("hydrokit: $pkg responded req id=$id with error: $err")
            pending.done.offer(Reply(null, err))
        } else {
            val result = root.get("result")
            pending.done.offer(Reply(result?.toString() ?: "", null))
        }
        return HydroHttpResponse.json("""{"status":"ok"}""")
    }

    companion object {
        fun validPackageId(id: String): Boolean {
            if (id.isEmpty() || id.length > 128) return false
            return id.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_' }
        }
    }
}

/**
 * worker 端点路由（对应 desktop/internal/api/worker.go 的路径分发）。
 * M4 只接 poll/respond；store/context/system/notify/music/lock/commands/asset
 * 属于 kit 能力，在 M6/M7 里补。
 */
class WorkerRoutes(private val bridge: WorkerBridge) {
    fun handle(req: HydroHttpRequest): HydroHttpResponse? {
        val path = req.path.split("?")[0]
        val prefix = HydroKitRoutes.PREFIX + "/worker"
        if (path != prefix && !path.startsWith("$prefix/")) return null
        val rest = path.removePrefix(prefix).trimStart('/')

        return when {
            rest == "poll" && req.method == "GET" -> bridge.handlePoll(req)
            rest == "respond" && req.method == "POST" -> bridge.handleRespond(req)
            else -> HydroHttpResponse.text("worker endpoint not found", 404)
        }
    }
}
