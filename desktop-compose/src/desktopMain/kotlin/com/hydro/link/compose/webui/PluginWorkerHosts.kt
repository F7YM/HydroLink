package com.hydro.link.compose.webui

import androidx.compose.runtime.mutableStateListOf
import ca.weblite.webview.OffscreenWebView
import java.awt.EventQueue

/**
 * 每个已注册插件一个**轻量离屏 WebKit 宿主**，里面跑 worker 宿主胶水（`worker.html` + `worker.js`）。
 *
 * ## 为什么采用纯 [OffscreenWebView] 而不是 AWT Frame
 *
 * · 插件的 JS/WASM 纯靠长轮询运行，不向屏幕绘制任何 UI，也不接受交互；
 * · AWT 隐藏 Frame (`isVisible = false`) 在 Linux GTK 环境下未 mapped，会导致 WebKitGTK
 *   在 offscreen 绘制时因为没有 allocate 尺寸触发 GTK 断言（`assertion '!widget->priv->alloc_needed' failed`）；
 * · 直接使用 [OffscreenWebView.create] 是纯 C 原生 WebKit 实例，没有外层 AWT 容器打扰，
 *   内存消耗和初始化时间大幅降低，同时原生支持 [OffscreenWebView.addJavascriptCallback] 转发日志。
 */
class PluginWorkerHosts {

    /** 一个宿主窗口的描述。 [key] 含 baseUrl，端口一变 key 就变 → 旧宿主被换掉。 */
    data class Host(val key: String, val pluginId: String, val url: String)

    /**
     * 插件 `console.*`（经胶水 `__hkLog`）的日志出口。桌面端排障主要看这条线：
     * 手表侧没有日志出口，插件打印的入参/回包/回退原因都在这里。
     * 由宿主接成 `HydroServer.addWorkerLog`，进应用日志面板。
     */
    var logSink: ((String) -> Unit)? = null

    private val state = mutableStateListOf<Host>()

    val hosts: List<Host> get() = state

    private val instances = HashMap<String, OffscreenWebView>()

    /**
     * 让宿主列表与已注册插件一致。
     */
    fun sync(pluginIds: List<String>, baseUrl: String) {
        if (baseUrl.isBlank()) return
        val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
        val wanted = pluginIds.distinct().map { id ->
            val effectiveBase = if (isLinux) workerBaseUrl(baseUrl, id) else baseUrl
            Host(
                key = "$effectiveBase|$id",
                pluginId = id,
                url = "$effectiveBase/api/hydro/worker/host?file=worker.html" +
                    "&package=" + enc(id) + "&base=" + enc(effectiveBase),
            )
        }
        val current = state.toList()
        if (current == wanted) return
        state.clear()
        state.addAll(wanted)

        onEdt {
            val keep = wanted.map { it.key }.toSet()
            instances.keys.filterNot { it in keep }.toList().forEach { key ->
                disposeInstance(key)
            }
            wanted.forEach { host ->
                if (host.key !in instances) openInstance(host)
            }
        }
    }

    /**
     * 确保某插件的 worker 宿主已启动（在被唤醒或按需调用时）。
     */
    fun ensure(packageId: String, baseUrl: String) {
        if (baseUrl.isBlank() || packageId.isBlank()) return
        val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
        val effectiveBase = if (isLinux) workerBaseUrl(baseUrl, packageId) else baseUrl
        val host = Host(
            key = "$effectiveBase|$packageId",
            pluginId = packageId,
            url = "$effectiveBase/api/hydro/worker/host?file=worker.html" +
                "&package=" + enc(packageId) + "&base=" + enc(effectiveBase),
        )
        onEdt {
            if (host.key !in instances) {
                openInstance(host)
            }
        }
    }

    /**
     * 为插件 worker 宿主分配专属的本地环回基地址。
     *
     * ## 为什么不能所有 worker 宿主和 WebUI 都挤在同一个 host（如 `127.0.0.1`）？
     * Linux 下 WebKitGTK（ca.weblite 底层）的 `SoupSession` 硬编码了 HTTP/1.1 每主机最大连接数
     * `max-conns-per-host = 6`（`NetworkSessionSoup.cpp`）。
     * 每个插件 worker 运行 `worker.js`，通过长轮询 `GET /api/hydro/worker/poll` 保持一条长达 25 秒的连接。
     * 一旦安装了 6 个插件，这 6 条持久长轮询连接就会把 `127.0.0.1:9000` 的连接池**完全占满（6/6）**！
     * 此时任何新的 WebUI 窗口去请求 `http://127.0.0.1:9000/api/hydro/webui/...` 都会被 WebKit 底层网络层
     * 排队挂起（排队等待可用连接），服务端甚至根本收不到 HTTP 请求，直接导致 WebUI 永久白屏/加载失败！
     *
     * 服务端 [HydroServer] 监听在 `0.0.0.0` 上，可接受任何 `127.x.x.x` 环回连接。
     * 通过为每个 worker 赋予独立的环回地址（`127.0.1.<id_hash>`），
     * 每个 worker 拥有独立的连接池，彻底解除并发连接数限制，同时让主界面的 WebUI 独享 `127.0.0.1` 的完整连接池。
     */
    private fun workerBaseUrl(baseUrl: String, packageId: String): String {
        val uri = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return baseUrl
        val host = uri.host ?: return baseUrl
        if (host == "127.0.0.1" || host == "localhost") {
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val scheme = uri.scheme ?: "http"
            val ipIndex = ((packageId.hashCode() and 0x7fffffff) % 250) + 1
            val loopbackIp = "127.0.1.$ipIndex"
            return "$scheme://$loopbackIp$port"
        }
        return baseUrl
    }

    fun stopAll() {
        state.clear()
        onEdt {
            instances.keys.toList().forEach { disposeInstance(it) }
        }
    }

    private fun openInstance(host: Host) {
        runCatching {
            val web = OffscreenWebView.create(800, 600, false)
            // 调整 WebKit 安全策略：禁用 CORS 跨域拦截
            WebKitSecurity.apply(web)
            // 增强透明 Fetch 跨域代理（消除第三方 API 诸如 Bilibili 识别 Origin 后 403 拦截）
            web.addOnBeforeLoad(WebUiFetchPolyfill.SCRIPT)
            // 日志回调：worker.js 内置了对 __hkLog 的支持（插件 console.* 也汇到这条）
            web.addJavascriptCallback("__hkLog") { msg ->
                println("[hydrokit] $msg")
                runCatching { logSink?.invoke("[${host.pluginId}] $msg") }
            }
            web.navigate(host.url)
            instances[host.key] = web
            println("[WebView] worker 宿主已加载 · ${host.pluginId}")
        }.onFailure {
            SystemWebEngine.reportFailure(it)
        }
    }

    private fun disposeInstance(key: String) {
        val web = instances.remove(key) ?: return
        runCatching {
            web.dispose()
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            block()
        } else {
            EventQueue.invokeLater(block)
        }
    }

    private fun enc(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
