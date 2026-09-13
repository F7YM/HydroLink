package com.hydro.link.server.hydrokit

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件 worker 的安卓宿主：每个插件一个**隐藏 WebView**，加载同源的宿主页
 * （worker.html），由页内胶水完成 长轮询 → 调用 server.js / wasm → 回填。
 *
 * 生命周期：
 *   · 服务器启动 / 插件安装后 → [ensure]（或等 onNeedWake 触发）
 *   · 插件卸载 / 更新（版本变化）→ [destroy]
 *   · 服务重启换了端口 → [ensure] 检测到 base 变化后重建宿主
 *   · 渲染进程被杀（内存压力/崩溃）→ [WebViewClient.onRenderProcessGone] 里重启宿主
 *   · 宿主页崩溃或插件代码抛错 → 由页内胶水自身重试（2s 退避），宿主只记日志
 *
 * 注意：所有 WebView 操作都在主线程（Android 强制要求）。[hosts] 用并发容器，
 * 因为 `ensure` 可能来自 HTTP 工作线程（worker 离线唤醒），`destroy` 来自主线程。
 */
class PluginHostManager(
    private val context: Context,
    private val store: PluginStore,
    private val bridge: WorkerBridge,
    private val portProvider: () -> Int,
    private val log: (level: String, message: String) -> Unit = { _, _ -> },
    /** 主线程调度器：WebView 只能在主线程操作（默认用主 Looper）。 */
    private val mainThread: (Runnable) -> Unit = { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    },
    /** 主线程延迟调度（渲染进程被杀后延迟重启宿主，避免立刻又被杀）。 */
    private val mainThreadDelayed: (Long, Runnable) -> Unit = { delayMs, r ->
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delayMs)
    },
) {
    private val hosts = ConcurrentHashMap<String, Host>()

    private class Host(val webView: WebView, val version: String, val baseUrl: String)

    init {
        // worker 不在线时 bridge 会回调这里唤醒宿主。必须在管理器一建立就挂上：
        // 否则"第一个宿主还没起来时来的请求"唤醒不了任何东西（宿主在
        // startHost 里挂回调就晚了一步）。
        bridge.onNeedWake = { pkg ->
            log("info", "hydrokit: 请求唤醒插件 $pkg")
            ensure(pkg)
        }
        if (com.hydro.link.server.BuildConfig.DEBUG) {
            // 真机上排障用：chrome://inspect 可以打开隐藏的插件宿主页看 console
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun baseUrl(): String = "http://127.0.0.1:${portProvider()}"

    /** 确保某插件的宿主已启动（已启动且版本未变则直接返回）。任意线程可调。 */
    fun ensure(packageId: String) {
        val info = store.info(packageId) ?: run {
            log("warning", "hydrokit: 插件 $packageId 不存在，无法启动宿主")
            return
        }
        if (info.error != null) {
            log("warning", "hydrokit: 插件 $packageId 清单异常，跳过宿主: ${info.error}")
            return
        }
        val existing = hosts[packageId]
        // 宿主页里的胶水拿的是**绝对地址**（长轮询打的是宿主页所在的 base）。服务重启
        // 后端口可能变（9000 被占用时由系统分配），旧宿主会一直打已经没人听的端口，
        // 表现为"插件列表显示在线但界面永远不刷新"。端口变了必须重建宿主。
        if (existing != null && existing.version == info.version && existing.baseUrl == baseUrl()) return
        if (existing != null) destroy(packageId)
        mainThread(Runnable { startHost(packageId, info) })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startHost(packageId: String, info: PluginStore.Installed) {
        val url = buildString {
            append(baseUrl())
            append("/api/hydro/worker/host?file=worker.html")
            append("&package=").append(android.net.Uri.encode(packageId))
            append("&base=").append(android.net.Uri.encode(baseUrl()))
            append("&entry=").append(android.net.Uri.encode(info.entry))
            append("&runtime=").append(android.net.Uri.encode(info.runtime))
        }
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.allowFileAccess = false // 宿主页与资源都走同源 HTTP，无需文件访问
        web.settings.allowContentAccess = false
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                log("info", "hydrokit: worker 宿主已加载 ${info.id} v${info.version}")
            }

            /**
             * 渲染进程被杀（内存压力 / 崩溃）。**必须返回 true**：返回 false 时
             * Android 会把整个 App 进程一起干掉——插件宿主把宿主 App 拖崩是最糟的
             * 结果。这里销毁死掉的 WebView、断开 worker，延迟重建。
             */
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val crashed = detail?.didCrash() ?: false
                log("warning", "hydrokit: 插件 ${info.id} 的渲染进程终止（crash=$crashed），3 秒后重启宿主")
                hosts.remove(packageId)
                bridge.disconnect(packageId)
                try {
                    view?.destroy()
                } catch (e: Exception) {
                    Log.w("hydrokit", "销毁崩溃的宿主失败: ${e.message}")
                }
                mainThreadDelayed(3000L, Runnable {
                    if (store.info(packageId) != null) ensure(packageId)
                })
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val text = msg.message()
                // 插件自己的 console.log 也进应用日志，便于排障（宿主页会加 [worker] 前缀）
                log(
                    if (text.contains("出错") || text.contains("失败")) "warning" else "info",
                    "hydrokit(worker ${info.id}): $text",
                )
                return true
            }
        }
        web.loadUrl(url)
        hosts[packageId] = Host(web, info.version, baseUrl())
    }

    /** 停止并销毁某插件的宿主（卸载/更新时调用）。任意线程可调。 */
    fun destroy(packageId: String) {
        val host = hosts.remove(packageId) ?: return
        mainThread(Runnable {
            try {
                host.webView.evaluateJavascript("window.__hkStop && window.__hkStop();", null)
                host.webView.stopLoading()
                host.webView.loadUrl("about:blank")
                host.webView.destroy()
            } catch (e: Exception) {
                Log.w("hydrokit", "销毁插件宿主失败: ${e.message}")
            }
        })
        bridge.disconnect(packageId)
        log("info", "hydrokit: 已停止插件宿主 $packageId")
    }

    /** 销毁所有宿主（服务停止时调用）。 */
    fun destroyAll() {
        hosts.keys.toList().forEach { destroy(it) }
    }

    fun isRunning(packageId: String): Boolean = hosts.containsKey(packageId)

    fun runningPackages(): Set<String> = hosts.keys.toSet()
}
