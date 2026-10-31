package com.hydro.link.compose.webui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.weblite.webview.PopupDisposition
import ca.weblite.webview.WebViewDownloadCompleteEvent
import ca.weblite.webview.WebViewDownloadEvent
import ca.weblite.webview.WebViewDownloadHandler
import ca.weblite.webview.WebViewPopupEvent
import ca.weblite.webview.WebViewPopupHandler
import ca.weblite.webview.swing.WebViewComponent
import com.hydro.link.compose.platform.PlatformFileChooser
import java.io.File

/**
 * 桌面端的内嵌浏览器：**系统 WebView**（ca.weblite:webview，MIT）。
 *
 * 这是和 Go 端对齐的关键——Wails 应用本身就是一个 webview（Windows=WebView2/Edge、
 * macOS=WKWebView、Linux=WebKitGTK），插件 WebUI 是同一 webview 里的 iframe。
 * 所以 Go 端不需要"额外浏览器"，插件 JS/WASM 直接跑在那块系统 webview 里。
 * 我们的 UI 是 Compose/Skia 画的，窗口里没有 webview，但插件页和胶水仍然需要一块，
 * 于是就用**同一个系统 webview**：Linux/macOS/Windows 各自的引擎，不是自带一份 CEF。
 *
 * 换掉之前的 KCEF/内嵌 CEF 有三个实打实的好处：
 * · 包体积：CEF bundle 539MB + 首启下载（还要解压）→ jar 1.4MB（各平台 ~1MB 原生壳）；
 * · 崩溃：CEF 在本机（Fedora + mesa 26 + XWayland）初始化 GLX 时，JOGL 的
 *   `glXChooseFBConfig` 会在 `jni_IsInstanceOf` 里把 JVM 打成 SIGABRT，整个进程 core dump；
 * · 依赖：jcef/kcef/jogl/gluegen-rt 连带 jogamp 那个 Maven 仓库一起不用了。
 *
 * 前置条件只有系统本身有引擎：Linux 需 `libwebkit2gtk-4.1`（或 4.0），macOS/Windows 自带。
 * 缺引擎时 [create] 会抛，调用方（插件 WebUI / worker 宿主）负责兜底。
 *
 * ## 为什么是 Swing
 *
 * [WebViewComponent] 是个 `JComponent`，而 Compose Desktop 自带
 * `androidx.compose.ui.awt.SwingPanel`（`ui-desktop` artifact，`ExperimentalComposeUiApi`），
 * 可以把任意 AWT 组件嵌进 Compose 布局——所以窗口外壳仍是 Compose，只有网页区域是系统 webview。
 *
 * ## 渲染模式
 *
 * macOS/Windows 只能 HEAVYWEIGHT（原生 view 直接贴到窗口上）；Linux 默认 LIGHTWEIGHT
 * （WebKit 画到 offscreen surface，再由 Swing 贴进组件）。Linux 上 heavyweight 需要把 GTK 窗口
 * `XReparentWindow` 到 AWT 窗口下面，跟 Compose 的绘制顺序容易打架，而且**文字输入反馈不可靠**
 * （GTK frame-clock 在外来父窗口下不 pacing）；lightweight 那边则明确没有 IME 组字。
 * 插件 WebUI 是设置页、worker 胶水根本不收输入，两边都不致命，所以默认跟官方一致，
 * 需要换时用 `HYDROLINK_WEBVIEW_MODE=heavyweight|lightweight`。
 */
object SystemWebEngine {

    /** 引擎不可用时的原因（`null` = 可用）。UI 读它来决定要不要退回系统浏览器。 */
    var error: String? by mutableStateOf(null)
        private set

    /** 生效的渲染模式（见文件头「渲染模式」）。 */
    val mode: WebViewComponent.Mode = resolveMode()

    /**
     * 建一个系统 WebView 组件。
     *
     * [quiet] = 给**不给人看**的宿主用（worker 胶水）：关掉一切会弹窗/打扰用户的东西——
     * 对话框（alert/confirm/prompt/文件选择）全部丢弃、下载一律拒绝、右键菜单关掉，
     * 但把 `console.*` 转到 stdout，这样插件日志和 `worker ready …` 能进应用日志
     * （安卓端这条走 logcat，桌面端对应就是 stdout/run 日志）。
     *
     * 必须在 AWT 事件线程上创建（底层要拿 native peer）；这里主动切过去，
     * 免得谁从后台线程调进来就踩到 GTK 的线程检查。
     */
    fun create(quiet: Boolean): WebViewComponent {
        val component = onEdt { WebViewComponent.create(mode) }
        onEdt {
            // 给定初始窗口尺寸，避免 addNotify 时因 getWidth()/getHeight()=0 导致 1x1 离屏创建并触发 GTK alloc_needed 断言
            component.setSize(900, 700)
            component.setBounds(0, 0, 900, 700)

            // 密码管理器会把插件页里的密码写进系统钥匙串，还要弹确认框 —— 一律不要。
            component.setPasswordManagerEnabled(false)
            if (quiet) {
                // setDialogHandler(null) = 内置的"丢弃"实现：alert 忽略、confirm 当取消、
                // prompt 当 null、文件选择空列表。胶水页要是弹个框，1×1 的隐藏窗口里没人看得见，
                // 弹到桌面上就是骚扰。
                component.setDialogHandler(null)
                component.setDownloadHandler(null)
                component.setPopupHandler(null)
                component.setDefaultContextMenuEnabled(false)
                // 插件的 console 输出要看得见：这是桌面端唯一的 worker 日志出口。
                component.setConsoleOutput(System.out)
            } else {
                // 用户可见的 WebUI 窗口：
                // 0. 将 WebUI 窗口内部的 console.* 与未捕获异常输出到终端，便于实时排查排障
                component.setConsoleOutput(System.out)

                // 1. 彻底解决“白屏窗口且关不掉”：接管所有 Popup 请求，BLOCK 裸 GTK 顶层窗口弹窗
                component.setPopupHandler(object : WebViewPopupHandler {
                    override fun popupDisposition(event: WebViewPopupEvent): PopupDisposition {
                        val targetUrl = event.targetUrl() ?: ""
                        println("[WebUI] 拦截弹窗请求: targetUrl=$targetUrl, pageUrl=${event.pageUrl()}")
                        // 如果是外部链接（非本地 loopback），调用系统默认浏览器打开
                        if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
                            val isLocal = targetUrl.contains("127.0.0.1") || targetUrl.contains("localhost")
                            if (!isLocal) {
                                runCatching {
                                    if (java.awt.Desktop.isDesktopSupported() &&
                                        java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
                                    ) {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI.create(targetUrl))
                                    }
                                }
                            }
                        }
                        // 绝不返回 NATIVE_WINDOW，杜绝底层 C++ 弹出一个未受控的裸 GTK 白屏窗口！
                        return PopupDisposition.BLOCK
                    }
                })

                // 2. 接管下载请求：调用系统原生文件保存选择器（支持 KDE kdialog / GNOME zenity / Win32 / Cocoa）
                component.setDownloadHandler(object : WebViewDownloadHandler {
                    override fun downloadRequested(event: WebViewDownloadEvent): File? {
                        val suggested = event.suggestedFileName()
                        val target = PlatformFileChooser.chooseSaveFile(
                            title = "保存下载文件",
                            suggestedName = suggested,
                        )
                        if (target != null) {
                            println("[WebUI] 开始下载文件: $suggested -> ${target.absolutePath}")
                        } else {
                            println("[WebUI] 用户取消了文件下载: $suggested")
                        }
                        return target
                    }

                    override fun downloadCompleted(event: WebViewDownloadCompleteEvent) {
                        if (event.success()) {
                            println("[WebUI] 文件下载完成: ${event.destination()?.absolutePath} (${event.receivedBytes()} 字节)")
                        } else {
                            println("[WebUI] 文件下载失败: ${event.failureReason()}")
                        }
                    }
                })

                // 3. 增强 HTML <select> 交互：在 Linux/Wayland 等离屏（LIGHTWEIGHT）渲染环境下，
                //    WebKitGTK 没有原生顶层 X11/Wayland 窗口句柄，点击标准 <select> 无法呼出底层 GTK 菜单（静默失败）。
                //    通过注入现代化的 Select 增强交互层，优雅渲染选项菜单并双向绑定 input/change 原生事件。
                component.addOnBeforeLoad(WebUiSelectPolyfill.SCRIPT)
            }

            // 4. 增强透明 Fetch 跨域代理（消除第三方 API 诸如 Bilibili 识别 Origin 后 403 拦截）
            component.addOnBeforeLoad(WebUiFetchPolyfill.SCRIPT)

            // 5. 调整 WebKit 安全策略：禁用 CORS 跨域拦截，允许插件 WebUI 发起外部 API 请求（如扫码登录、第三方数据交互等）
            WebKitSecurity.apply(component)
        }
        return component
    }

    /** 已经出过一次错就别反复弹同一个失败——记下来，只更新文案。 */
    fun reportFailure(cause: Throwable) {
        val message = cause.message ?: cause.toString()
        if (error != message) {
            println("[WebView] 系统 WebView 不可用：$message")
            error = message
        }
    }

    fun clearFailure() {
        error = null
    }

    /**
     * 引擎能不能用：真建一个再关掉。
     *
     * 懒一次并缓存——建 WebKit 视图不便宜（Linux 上要起 GTK 上下文），而调用方都是
     * 「用户点了打开界面」这种一次性判断。
     */
    val available: Boolean by lazy {
        val ok = runCatching { create(quiet = true).dispose() }
            .onFailure { reportFailure(it) }
            .isSuccess
        ok
    }

    private fun resolveMode(): WebViewComponent.Mode {
        val want = System.getenv("HYDROLINK_WEBVIEW_MODE")?.trim()?.lowercase()
        return when (want) {
            "heavyweight", "heavy" -> WebViewComponent.Mode.HEAVYWEIGHT
            "lightweight", "light" -> WebViewComponent.Mode.LIGHTWEIGHT
            else -> WebViewComponent.resolveDefaultMode()
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (java.awt.EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        java.awt.EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
