package com.hydro.link.compose.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * 桌面端 WebUI 自动化渲染与白屏回归诊断工具。
 */
fun main() {
    println("[WebUiDiag] 启动 Store 与内置 HydroServer...")
    val store = Store.open()
    val server = HydroServer(store).apply { start(9000) }
    val port = server.port
    val baseUrl = "http://127.0.0.1:$port"
    println("[WebUiDiag] HydroServer 已启动 · $baseUrl")

    val workerHosts = PluginWorkerHosts()
    val pluginIds = server.runtime.list().map { it.id }
    println("[WebUiDiag] 找到已安装插件: $pluginIds")
    workerHosts.sync(pluginIds, baseUrl)

    val targetPlugins = listOf(
        "com.hydro.chat" to "HydroChat",
        "com.hydro.deskbuddy" to "桌搭",
        "com.hydro.musicfox" to "MusicFox",
        "com.sunpm.wakeol" to "WakeOnLan",
        "com.sunpm.remote.terminal" to "远程终端",
    ).filter { (pkg, _) -> pkg in pluginIds }

    var activePkg by mutableStateOf<String?>("com.hydro.chat")
    var activeName by mutableStateOf<String?>("HydroChat")
    var testWindow: java.awt.Window? = null

    Thread {
        try {
            Thread.sleep(2500)

            for ((pkg, name) in targetPlugins) {
                println("\n==================================================")
                println("[WebUiDiag] 开始测试插件 WebUI: $name ($pkg)")

                // 打开窗口并设置当前插件
                SwingUtilities.invokeAndWait {
                    activePkg = pkg
                    activeName = name
                }

                // 轮询等待 WebKitGTK 完成页面加载与光栅化（最多 8 秒）
                var success = false
                var whitePct = 100.0
                var topColors = ""
                val start = System.currentTimeMillis()

                while (System.currentTimeMillis() - start < 8000) {
                    Thread.sleep(800)
                    SwingUtilities.invokeAndWait {
                        val win = testWindow ?: return@invokeAndWait
                        val w = win.width.coerceAtLeast(1)
                        val h = win.height.coerceAtLeast(1)
                        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                        val g = img.createGraphics()
                        win.paint(g)
                        g.dispose()

                        var total = 0
                        var white = 0
                        val colors = mutableMapOf<Int, Int>()

                        for (x in 0 until w step 4) {
                            for (y in 55 until h step 4) {
                                val rgb = img.getRGB(x, y)
                                val alpha = (rgb ushr 24) and 0xff
                                if (alpha == 0) continue
                                colors[rgb] = (colors[rgb] ?: 0) + 1
                                total++

                                val r = (rgb shr 16) and 0xff
                                val gVal = (rgb shr 8) and 0xff
                                val b = rgb and 0xff
                                if (r > 240 && gVal > 240 && b > 240) {
                                    white++
                                }
                            }
                        }

                        if (total > 1000) {
                            whitePct = white.toDouble() / total * 100
                            topColors = colors.entries.sortedByDescending { it.value }.take(3)
                                .joinToString(", ") { "0x${Integer.toHexString(it.key).padStart(8, '0')}" }

                            val outFile = File("/tmp/webui_verify_$pkg.png")
                            ImageIO.write(img, "PNG", outFile)

                            if (whitePct < 70.0) {
                                success = true
                                println("[WebUiDiag] $name ($pkg) 截图已保存: ${outFile.absolutePath}")
                            }
                        }
                    }
                    if (success) break
                }

                println("[WebUiDiag] $name ($pkg) 最终结果: 亮白像素占比=${String.format("%.1f%%", whitePct)}, 主要色彩=[$topColors]")
                if (!success) {
                    System.err.println("[WebUiDiag] ❌ 失败: $name ($pkg) 亮白像素仍为 ${String.format("%.1f%%", whitePct)}，疑似白屏！")
                    exitProcess(1)
                } else {
                    println("[WebUiDiag] ✅ 成功: $name ($pkg) 渲染正常！")
                }

                // 验证 WebKit 视口高度与 Swing 组件真实高度一致（防止卡在 700px 导致滚不到底）
                var compRef: ca.weblite.webview.swing.WebViewComponent? = null
                var compW = 0
                var compH = 0
                SwingUtilities.invokeAndWait {
                    compRef = findWebViewComponent(testWindow)
                    compW = compRef?.width ?: 0
                    compH = compRef?.height ?: 0
                }
                val webComp = compRef
                if (webComp != null && compH > 0) {
                    val metricsJson = webComp.evalAsync(
                        """
                        return (function() {
                            window.scrollTo(0, 999999);
                            var de = document.documentElement;
                            var body = document.body;
                            var sh = Math.max(de ? de.scrollHeight : 0, body ? body.scrollHeight : 0);
                            var st = window.scrollY || (de ? de.scrollTop : 0) || (body ? body.scrollTop : 0);
                            return window.innerWidth + "," + window.innerHeight + "," + sh + "," + Math.round(st) + "," + (window.devicePixelRatio || 1);
                        })();
                        """.trimIndent()
                    ).get(3, java.util.concurrent.TimeUnit.SECONDS).trim().trim('"')
                    val parts = metricsJson.split(",")
                    val innerW = parts.getOrNull(0)?.toIntOrNull() ?: -1
                    val innerH = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    val scrollH = parts.getOrNull(2)?.toIntOrNull() ?: -1
                    val scrollTop = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    val dpr = parts.getOrNull(4)?.toDoubleOrNull() ?: 1.0
                    val scaledH = kotlin.math.round(innerH * dpr).toInt()
                    println("[WebUiDiag] $name ($pkg) 视口与滚动度量: Swing=${compW}x${compH}, WebKit=${innerW}x${innerH} (dpr=$dpr, physicalH=$scaledH), scrollHeight=$scrollH, maxScrollTop=$scrollTop")
                    if (kotlin.math.abs(scaledH - compH) > 2) {
                        System.err.println("[WebUiDiag] ❌ 失败: $name ($pkg) WebKit 物理视口高度($scaledH, innerHeight=$innerH*dpr=$dpr) != Swing height($compH)，底部会被截断 ${scaledH - compH}px！")
                        exitProcess(1)
                    }
                    if (scrollH > innerH && scrollTop + innerH < scrollH - 2) {
                        System.err.println("[WebUiDiag] ❌ 失败: $name ($pkg) 无法滚到底部: scrollTop($scrollTop) + innerHeight($innerH) < scrollHeight($scrollH)")
                        exitProcess(1)
                    }
                    println("[WebUiDiag] ✅ 视口对齐与滚到底部验证通过: physicalH=$scaledH == SwingHeight=$compH")
                }

                // 切换前清空，重置组件
                SwingUtilities.invokeAndWait {
                    activePkg = null
                    activeName = null
                }
                Thread.sleep(800)
            }

            println("\n🎉 所有插件 WebUI 渲染测试全部通过！")
            workerHosts.stopAll()
            server.stop()
            exitProcess(0)
        } catch (e: Throwable) {
            System.err.println("[WebUiDiag] 异常退出: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }.start()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
            title = "${activeName ?: "待机"} · 界面验证",
        ) {
            testWindow = window
            MaterialTheme(colorScheme = darkColorScheme()) {
                val pkg = activePkg
                val name = activeName
                if (pkg != null && name != null) {
                    PluginWebUi(
                        pluginId = pkg,
                        pluginName = name,
                        baseUrl = baseUrl,
                        onBack = { activePkg = null },
                        miuix = false,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                        Text("正在切换插件...", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun findWebViewComponent(root: java.awt.Component?): ca.weblite.webview.swing.WebViewComponent? {
    if (root == null) return null
    if (root is ca.weblite.webview.swing.WebViewComponent) return root
    if (root is java.awt.Container) {
        for (child in root.components) {
            val found = findWebViewComponent(child)
            if (found != null) return found
        }
    }
    return null
}
