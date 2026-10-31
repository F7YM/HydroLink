package com.hydro.link.compose.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import ca.weblite.webview.swing.WebViewComponent

/**
 * 在 Compose 布局里嵌一块系统 WebView（ca.weblite），并把它导航到 [url]。
 *
 * 用的是 Compose Desktop 自带的 `SwingPanel`（`androidx.compose.ui.awt`，Experimental）：
 * 它把一个 AWT 组件挂进 Compose 的 Swing 互操作层，所以网页区域是真的系统 webview，
 * 而窗口其余部分仍然是 Compose 画的。
 *
 * 创建失败（Linux 缺 `libwebkit2gtk` 之类）不抛给组合，而是落到一块占位面板 +
 * [SystemWebEngine.reportFailure] —— 页面上给一句话，应用继续活着。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SystemWebView(
    url: String,
    modifier: Modifier = Modifier,
    onComponent: (WebViewComponent?) -> Unit = {},
) {
    // 组件只建一次：url 变化靠下面的 update 重导航，而不是重建整个 webview
    // （重建会丢页面状态，worker 宿主还会断掉长轮询）。
    val holder = remember { arrayOfNulls<WebViewComponent>(1) }

    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.let { comp ->
                runCatching { comp.dispose() }
                    .onFailure { println("[SystemWebView] comp.dispose() 异常: ${it.message}") }
            }
            holder[0] = null
            onComponent(null)
        }
    }

    SwingPanel(
        factory = {
            val created = runCatching { SystemWebEngine.create(quiet = false) }
            created.exceptionOrNull()?.let {
                println("[SystemWebView] 创建 WebKit 组件失败: ${it.message}")
                SystemWebEngine.reportFailure(it)
            }
            created.getOrNull()?.also {
                holder[0] = it
                it.setUrl(url)
            } ?: FailedWebPanel(created.exceptionOrNull())
        },
        modifier = modifier,
        update = { component ->
            if (component is WebViewComponent && component.url != url) {
                component.setUrl(url)
            }
            onComponent(holder[0])
        },
    )
}

/** 引擎起不来时的占位面板（灰底一行字，避免白屏让人以为卡住了）。 */
private class FailedWebPanel(cause: Throwable?) : javax.swing.JPanel(
    java.awt.BorderLayout(),
) {
    init {
        background = java.awt.Color(0xF2, 0xF2, 0xF7)
        add(
            javax.swing.JLabel(
                "系统 WebView 不可用：${cause?.message ?: cause}",
                javax.swing.SwingConstants.CENTER,
            ),
            java.awt.BorderLayout.CENTER,
        )
    }
}
