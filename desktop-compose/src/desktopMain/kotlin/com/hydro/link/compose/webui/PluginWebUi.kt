package com.hydro.link.compose.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.weblite.webview.swing.WebViewComponent
import com.hydro.link.shared.ui.icons.IcArrowBack
import com.hydro.link.shared.ui.icons.IcPluginRefresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 插件 WebUI 窗口的内容。
 *
 * 页面地址与安卓端完全一致：`http://127.0.0.1:<port>/api/hydro/webui/<pkg>/index.html`
 * ——同源，插件页面可以直接调 kit 接口，插件作者不用为桌面写第二套页面。
 *
 * 引擎是**系统 WebView**（见 [SystemWebEngine]），不是自带 CEF。
 *
 * 顶栏跟随全局主题适配深色（miuix / MD3）：返回 / 标题 / 刷新。
 */
@Composable
fun PluginWebUi(
    pluginId: String,
    pluginName: String,
    baseUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    miuix: Boolean = false,
) {
    val url = "$baseUrl/api/hydro/webui/$pluginId/index.html"
    // 刷新要拿到组件本体（重新导航）；拿不到就按地址重来一次
    var web by remember { mutableStateOf<WebViewComponent?>(null) }

    val topBarBg: Color = if (miuix) {
        MiuixTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor: Color = if (miuix) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(modifier.fillMaxSize().background(topBarBg)) {
        Surface(color = topBarBg, contentColor = contentColor) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
                ) {
                    Icon(IcArrowBack, contentDescription = "返回")
                }
                Text(
                    text = pluginName,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // 重新导航到同一个地址 = 刷新（页面自己不管缓存）
                IconButton(
                    onClick = { web?.setUrl(url) },
                    enabled = web != null,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = contentColor,
                        disabledContentColor = contentColor.copy(alpha = 0.38f),
                    ),
                ) {
                    Icon(IcPluginRefresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            SystemWebView(url = url, modifier = Modifier.fillMaxSize()) { web = it }
        }
    }
}
