package com.hydro.link.compose.webui

import ca.weblite.webview.OffscreenWebView
import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main() {
    println("[WebKitSecurityTest] 正在启动测试服务端与 WebKit 环境...")
    val store = Store.open()
    val server = HydroServer(store).apply { start(0) }
    val port = server.port
    val baseUrl = "http://127.0.0.1:$port"
    println("[WebKitSecurityTest] 服务端已启动 · $baseUrl")

    val latch = CountDownLatch(1)
    var fetchResult: String? = null
    var fetchError: Throwable? = null

    SwingUtilities.invokeLater {
        try {
            val offscreen = OffscreenWebView.create(900, 700, false)
            val secApplied = WebKitSecurity.apply(offscreen)
            println("[WebKitSecurityTest] WebKitSecurity.apply(offscreen) 结果: $secApplied")

            // 注入 Fetch Polyfill 确保在任何环境均透明代理外部跨域请求
            offscreen.addOnBeforeLoad(WebUiFetchPolyfill.SCRIPT)

            // 监听 JavaScript 回调以获取 fetch 结果
            offscreen.addJavascriptCallback("onFetchResult") { msg ->
                println("[WebKitSecurityTest] 收到页面 JavaScript 回报: $msg")
                fetchResult = msg
                latch.countDown()
            }

            // 注入脚本：在页面加载后执行跨域 fetch B 站二维码接口
            val testScript = """
                (function() {
                    window.addEventListener('load', async function() {
                        try {
                            const res = await fetch('https://passport.bilibili.com/x/passport-login/web/qrcode/generate');
                            const text = await res.text();
                            if (window.onFetchResult) {
                                window.onFetchResult('STATUS=' + res.status + ' BODY=' + text.substring(0, 300));
                            }
                        } catch (e) {
                            if (window.onFetchResult) {
                                window.onFetchResult('ERR: ' + e.name + ': ' + e.message + '\nstack: ' + e.stack);
                            }
                        }
                    });
                })();
            """.trimIndent()
            offscreen.addOnBeforeLoad(testScript)

            val chatWebUrl = "$baseUrl/api/hydro/webui/com.hydro.chat/index.html"
            println("[WebKitSecurityTest] 导航到插件 WebUI: $chatWebUrl")
            offscreen.navigate(chatWebUrl)
        } catch (e: Throwable) {
            fetchError = e
            latch.countDown()
        }
    }

    val completed = latch.await(15, TimeUnit.SECONDS)
    server.stop()

    if (!completed) {
        System.err.println("[WebKitSecurityTest] ❌ 测试超时（15 秒内未收到 fetch 结果）")
        exitProcess(1)
    }

    if (fetchError != null) {
        System.err.println("[WebKitSecurityTest] ❌ 测试异常: ${fetchError?.message}")
        fetchError?.printStackTrace()
        exitProcess(1)
    }

    val result = fetchResult.orEmpty()
    println("\n==================================================")
    println("[WebKitSecurityTest] 最终跨域 fetch 响应: $result")
    println("==================================================")

    if (result.startsWith("ERR:")) {
        System.err.println("[WebKitSecurityTest] ❌ 跨域请求仍然被拦截或报错: $result")
        exitProcess(1)
    }

    if (result.contains("STATUS=200") && result.contains("qrcode_key")) {
        println("[WebKitSecurityTest] 🎉 验证通过！B 站二维码接口在 WebUI 环境中跨域获取成功，CORS 问题已彻底解决！")
        exitProcess(0)
    } else {
        System.err.println("[WebKitSecurityTest] ❌ 响应内容未达预期: $result")
        exitProcess(1)
    }
}
