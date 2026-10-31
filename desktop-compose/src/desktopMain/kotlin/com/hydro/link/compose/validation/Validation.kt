package com.hydro.link.compose.validation

import com.hydro.link.compose.PlatformExit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hydro.link.compose.webui.SystemWebEngine
import com.hydro.link.compose.webui.SystemWebView
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 桌面端重写的**验证程序**（不是产品代码）。
 *
 * 一次性回答三个问题，决定「桌面端能不能整套用安卓那套（Compose + miuix + WebUI）」：
 *  1. miuix-kmp 能不能在 desktop JVM 上加载并渲染（它是 CMP 库，理论上可以）；
 *  2. Material3 引擎同一份 artifact 能不能跑；
 *  3. 插件 WebUI 的宿主——**系统 WebView**（ca.weblite，Linux 上即 WebKitGTK，与 Go 端 Wails
 *     同一块系统 webview）能不能起，并且 **JS 引擎 + localStorage + 同源 fetch** 可用
 *     （插件页面就靠这些调 kit 端点）。
 *
 * 验证方式尽量贴近真实：起一个真实的本地 HTTP 服务，用与插件 WebUI 相同的
 * `/api/hydro/webui/<pkg>/index.html` 路径喂页面，再从原生侧执行 JS 把结果读回来。
 */
private data class Check(val name: String, val state: String, val detail: String)

fun runValidation() = application {
    val checks = remember { mutableStateListOf<Check>() }
    fun pass(name: String, detail: String) {
        println("[CHECK] PASS  $name — $detail")
        checks.add(Check(name, "PASS", detail))
    }

    fun fail(name: String, detail: String) {
        println("[CHECK] FAIL  $name — $detail")
        checks.add(Check(name, "FAIL", detail))
    }

    // ---- 本地 HTTP 服务：模拟服务端 + 插件 WebUI 同源页面 ----
    // 组合前就启动：页面 URL 里要带真实端口（组合之后再启，第一次组合会拿到 port=0）
    val server = remember { WebUiTestServer().apply { start() } }
    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    // 系统 WebView 的可用性：真建一个再关掉（Linux 缺 libwebkit2gtk 就会在这里露出来）
    var webReady by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    var probeCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val ok = SystemWebEngine.available
        webReady = ok
        webError = SystemWebEngine.error
        if (ok) {
            pass(
                "系统 WebView 可用（${SystemWebEngine.mode}）",
                "Linux=WebKitGTK / macOS=WKWebView / Windows=WebView2，与 Go 端同一个引擎",
            )
        } else {
            fail("系统 WebView 可用", webError ?: "创建失败")
            probeCompleted = true
        }
    }

    // 验证跑完自动退出（无人值守，退出时彻底结束进程）
    LaunchedEffect(probeCompleted) {
        if (!probeCompleted) return@LaunchedEffect
        delay(2000)
        println("[CHECK] 验证完毕，正常退出进程")
        server.stop()
        exitApplication()
        PlatformExit.exitCleanly(0)
    }

    // 保底超时 15 秒（万一探针一直没返回）
    LaunchedEffect(Unit) {
        delay(15_000)
        println("[CHECK] 验证超时，退出进程")
        server.stop()
        exitApplication()
        PlatformExit.exitCleanly(0)
    }

    Window(
        onCloseRequest = {
            println("[CHECK] 用户关闭窗口，退出进程")
            server.stop()
            exitApplication()
            PlatformExit.exitCleanly(0)
        },
        state = rememberWindowState(width = 960.dp, height = 720.dp),
        title = "HydroLink 桌面端验证",
    ) {
        // 1) miuix 引擎
        MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) {
            Scaffold(containerColor = MiuixTheme.colorScheme.surface) { inner ->
                Column(Modifier.fillMaxSize().padding(inner)) {
                    MiuixText(
                        text = "miuix-kmp 0.9.4 · desktop JVM",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            MiuixText(
                                text = "miuix Card 渲染正常",
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            MiuixText(
                                text = "颜色 ${MiuixTheme.colorScheme.primary}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    CheckList(checks)
                }
            }
        }

        // 1.5) miuix-blur：桌面有没有 RuntimeShader/Skia 后端支持
        BlurProbe(
            onResult = { name, ok, detail ->
                if (ok) pass(name, detail) else fail(name, detail)
            },
        )

        // 2) 系统 WebView：加载同源插件页面并验证 JS
        if (webReady) {
            WebUiProbe(
                url = server.pageUrl,
                onJsResult = { result ->
                    // 期望 "JS-OK|pong"：document.title 由页面用 JS 写入，
                    // ping 是一次真实的同源 fetch 结果（存在 localStorage 里）
                    if (result.contains("JS-OK") && result.contains("pong")) {
                        pass("系统 WebView（JS + localStorage + 同源 fetch）", result)
                    } else {
                        fail("系统 WebView（JS + localStorage + 同源 fetch）", "返回 $result")
                    }
                    probeCompleted = true
                },
                onError = { msg ->
                    fail("系统 WebView（JS + localStorage + 同源 fetch）", msg)
                    probeCompleted = true
                },
            )
        } else {
            Text("系统 WebView 不可用：${webError ?: ""}", modifier = Modifier.padding(16.dp))
        }
    }

    // 启动即记录前两项（渲染成功 = 跑到这里没崩）
    pass("miuix-kmp 0.9.4 在 desktop JVM 加载并渲染", "见窗口内容")
    pass("Material3 1.12 引擎可加载", "本窗口同时用 androidx compose material3 渲染了状态文本")
    println("[CHECK] 本地 WebUI 测试服务: ${server.pageUrl}")
}

@Composable
private fun CheckList(checks: List<Check>) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                checks.forEach { c ->
                    Text(
                        text = "[${c.state}] ${c.name} — ${c.detail}",
                        color = if (c.state == "PASS") Color(0xFF36D167) else Color(0xFFE5484D),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * 加载同源页面 → 读标题/存储 → 用原生侧执行 JS 把结果取回来（证明 JS 引擎真的在跑）。
 *
 * 轮询而不是等回调：ca.weblite 的组件 API 没有"页面加载完成"事件，只有 `evalAsync` 能问，
 * 所以按固定间隔问 storage（安卓端的验证程序也是这个思路）。
 */
@Composable
private fun WebUiProbe(
    url: String,
    onJsResult: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var web by remember { mutableStateOf<Any?>(null) }
    var done by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("系统 WebView：$url", fontSize = 12.sp)
        SystemWebView(url = url, modifier = Modifier.fillMaxWidth().size(300.dp)) { web = it }

        LaunchedEffect(web) {
            val view = web as? ca.weblite.webview.swing.WebViewComponent ?: return@LaunchedEffect
            // 等页面里的 fetch 写完 localStorage
            repeat(20) {
                if (done) return@LaunchedEffect
                delay(300)
                val ping = withContext(Dispatchers.IO) {
                    runCatching {
                        view.evalAsync("return (localStorage.getItem('hydrolink.ping')||'');")
                            .get(2, TimeUnit.SECONDS)
                    }.getOrNull()
                }
                if (ping != null && ping.contains("pong")) {
                    done = true
                    val title = withContext(Dispatchers.IO) {
                        runCatching {
                            view.evalAsync("return document.title + '|' + (localStorage.getItem('hydrolink.ping')||'');")
                                .get(2, TimeUnit.SECONDS)
                        }.getOrNull()
                    }
                    if (title != null) onJsResult(title) else onError("标题读不回来")
                }
            }
            if (!done) onError("20 次轮询都没等到 localStorage 里的 ping 结果")
        }
    }
}

/**
 * 极简本地服务：只提供插件 WebUI 那一类同源资源。
 * 路径照抄真实协议（/api/hydro/webui/<pkg>/index.html + /api/ping），验证才有意义。
 */
private class WebUiTestServer {
    private var server: HttpServer? = null
    var port: Int = 0
        private set

    val pageUrl: String get() = "http://127.0.0.1:$port/api/hydro/webui/demo/index.html"

    fun start() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/hydro/webui/demo/index.html") { ex ->
            val body = """
                <!doctype html><html><head><meta charset="utf-8"><title>loading</title></head>
                <body style="font-family:sans-serif;background:#121318;color:#eee">
                  <h2>插件 WebUI 测试页</h2>
                  <p id="out">…</p>
                  <script>
                    localStorage.setItem('hydrolink.js', '1');
                    document.title = 'JS-OK';
                    fetch('/api/ping').then(r => r.text()).then(t => {
                      localStorage.setItem('hydrolink.ping', t);
                      document.getElementById('out').textContent = 'ping=' + t;
                    });
                  </script>
                </body></html>
            """.trimIndent()
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.createContext("/api/ping") { ex ->
            val bytes = "pong".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.executor = null
        s.start()
        server = s
        port = s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }
}

/**
 * 桌面 RenderEffect（高斯模糊）后端的可用性探测。
 *
 * **只查后端、不画玻璃**：真正给内容加 `layerBackdrop` 录制背景层再 `drawBackdrop` 采样，
 * 在本机（Linux/Wayland-Xwayland + Skia）会把 JVM 直接打成 **SIGSEGV（exit 139）**，
 * 窗口都起不来。单独调 `blur()` 而不录制不崩（所以第一版「假悬浮底栏」看着没出事）——
 * 崩溃的是**录制 + 采样**这条路。
 *
 * 结论：桌面侧栏/面板一律用半透明容器色，不做模糊（依赖也从 desktopMain 移除了）。
 */
@Composable
private fun BlurProbe(onResult: (String, Boolean, String) -> Unit) {
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reported) return@LaunchedEffect
        reported = true
        // RenderEffect 在桌面由 SkiaBackedRenderEffect 提供（Skia ImageFilter）
        val blurOk = runCatching { BlurEffect(8f, 8f).isSupported() }.getOrDefault(false)
        onResult(
            "RenderEffect 后端（高斯模糊可用性）",
            blurOk,
            if (blurOk) {
                "isSupported()=true；但 layerBackdrop 录制路径在本机 SIGSEGV，桌面不做模糊"
            } else {
                "桌面不支持 RenderEffect，模糊要降级"
            },
        )
    }
}
