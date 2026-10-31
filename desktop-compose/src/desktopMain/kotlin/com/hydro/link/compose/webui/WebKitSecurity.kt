package com.hydro.link.compose.webui

import ca.weblite.webview.OffscreenWebView
import ca.weblite.webview.swing.WebViewComponent
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities

/**
 * WebKitGTK 原生配置与安全策略调优。
 *
 * ## 为什么需要调整 WebKit 安全策略？
 * 桌面端插件 WebUI（如 `com.hydro.chat` 扫码登录 B 站）需要在页面内直接 `fetch()` 第三方 Web API
 * （如 B 站二维码 `passport.bilibili.com/x/passport-login/web/qrcode/generate`）。
 * WebKitGTK 默认开启了严格的浏览器同源策略（Same-Origin Policy / CORS），从本地环回
 * （`http://127.0.0.1:9000` 或 `http://127.0.1.x:9000`）发起的外部跨域请求会被拦截，
 * 导致页面抛出 `TypeError: Load failed`。
 *
 * 通过在底层原生 `WebKitSettings` 上启用：
 * - `disable-web-security = true`
 * - `allow-universal-access-from-file-urls = true`
 * - `allow-file-access-from-file-urls = true`
 * 彻底解除跨域限制，使插件 WebUI 拥有与桌面混合宿主（如 Electron / Qt WebEngine）一致的网络能力。
 */
object WebKitSecurity {

    private interface WebKitGtkLib : Library {
        fun webkit_web_view_get_settings(view: Pointer): Pointer?
        fun webkit_settings_set_disable_web_security(settings: Pointer, disable: Boolean)
        fun webkit_settings_get_disable_web_security(settings: Pointer): Boolean
        fun webkit_settings_set_allow_universal_access_from_file_urls(settings: Pointer, allow: Boolean)
        fun webkit_settings_set_allow_file_access_from_file_urls(settings: Pointer, allow: Boolean)
    }

    private val lib: WebKitGtkLib? by lazy {
        val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
        if (!isLinux) return@lazy null
        try {
            Native.load("webkit2gtk-4.1", WebKitGtkLib::class.java)
        } catch (_: Throwable) {
            try {
                Native.load("webkit2gtk-4.0", WebKitGtkLib::class.java)
            } catch (t: Throwable) {
                println("[WebKitSecurity] 未能加载 libwebkit2gtk 原生库: ${t.message}")
                null
            }
        }
    }

    private val offscreenPeerField by lazy {
        runCatching {
            OffscreenWebView::class.java.getDeclaredField("peer").apply { isAccessible = true }
        }.getOrNull()
    }

    private val lightweightEngineField by lazy {
        runCatching {
            Class.forName("ca.weblite.webview.swing.WebViewLightweightComponent")
                .getDeclaredField("engine").apply { isAccessible = true }
        }.getOrNull()
    }

    private val heavyweightEmbeddedField by lazy {
        runCatching {
            Class.forName("ca.weblite.webview.swing.WebViewHeavyweightComponent")
                .getDeclaredField("embedded").apply { isAccessible = true }
        }.getOrNull()
    }

    private val embeddedPeerField by lazy {
        runCatching {
            Class.forName("ca.weblite.webview.EmbeddedWebView")
                .getDeclaredField("peer").apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * 为给定的 [OffscreenWebView] 应用安全策略配置（关闭 CORS 限制）。
     */
    fun apply(offscreen: OffscreenWebView): Boolean {
        val nativeLib = lib ?: return false
        val peerField = offscreenPeerField ?: return false
        return runCatching {
            val peer = peerField.getLong(offscreen)
            if (peer == 0L) return false
            // OffEngine 结构体偏移 8 (0x8) 为 WebKitWebView*
            val webViewPtr = Pointer(peer).getPointer(8) ?: return false
            applyToWebView(nativeLib, webViewPtr)
        }.getOrDefault(false)
    }

    /**
     * 为给定的 [WebViewComponent] 应用安全策略配置。
     * 支持 [WebViewLightweightComponent] 与 [WebViewHeavyweightComponent]。
     */
    fun apply(component: WebViewComponent) {
        if (lib == null) return

        // 尝试立即配置已有引擎
        val applied = applyIfReady(component)
        if (applied) return

        // 若尚未就绪（如 addNotify 尚未触发），监听层次变化，在就绪时立即注入
        component.addHierarchyListener { e ->
            if ((e.changeFlags and (HierarchyEvent.SHOWING_CHANGED.toLong() or HierarchyEvent.DISPLAYABILITY_CHANGED.toLong())) != 0L) {
                applyIfReady(component)
            }
        }
    }

    private fun applyIfReady(component: WebViewComponent): Boolean {
        val nativeLib = lib ?: return false

        // 1. Lightweight 模式 (OffscreenWebView engine)
        lightweightEngineField?.let { field ->
            val engine = runCatching { field.get(component) as? OffscreenWebView }.getOrNull()
            if (engine != null) {
                return apply(engine)
            }
        }

        // 2. Heavyweight 模式 (EmbeddedWebView embedded)
        heavyweightEmbeddedField?.let { hField ->
            val embedded = runCatching { hField.get(component) }.getOrNull()
            if (embedded != null) {
                embeddedPeerField?.let { pField ->
                    val peer = runCatching { pField.getLong(embedded) }.getOrDefault(0L)
                    if (peer != 0L) {
                        // Engine 结构体偏移 24 (0x18) 为 WebKitWebView*
                        val webViewPtr = Pointer(peer).getPointer(24) ?: return false
                        return applyToWebView(nativeLib, webViewPtr)
                    }
                }
            }
        }

        return false
    }

    private fun applyToWebView(nativeLib: WebKitGtkLib, webViewPtr: Pointer): Boolean {
        val settings = nativeLib.webkit_web_view_get_settings(webViewPtr) ?: return false
        return runCatching {
            nativeLib.webkit_settings_set_disable_web_security(settings, true)
            nativeLib.webkit_settings_set_allow_universal_access_from_file_urls(settings, true)
            nativeLib.webkit_settings_set_allow_file_access_from_file_urls(settings, true)
            val disabled = nativeLib.webkit_settings_get_disable_web_security(settings)
            if (disabled) {
                println("[WebKitSecurity] ✅ 已成功为 WebKitWebView 配置 disable-web-security = true (CORS 限制已解除)")
            }
            disabled
        }.getOrDefault(false)
    }
}
