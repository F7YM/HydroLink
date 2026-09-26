package com.hydro.link.server.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.hydro.link.server.R
import com.hydro.link.server.model.LogEntry
import com.hydro.link.server.service.ServerService
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 运行日志（独立 Activity，系统返回栈）。每秒从服务器日志缓冲拉一次，
 * 按级别着色：error 红 / warning 黄 / success 绿 / info 灰。
 */
class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        setContent {
            MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) {
                LogsContent(onBack = { finish() })
            }
        }
    }

    private fun applySystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = if (night) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}

@Composable
private fun LogsContent(onBack: () -> Unit) {
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            logs = ServerService.instance?.getServer()?.getLogs()?.takeLast(400) ?: emptyList()
            delay(1000)
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = "运行日志",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) {
        if (logs.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp),
            ) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = "暂无日志",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                items(logs.size) { i ->
                    val entry = logs[i]
                    Text(
                        text = entry.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when (entry.level) {
                            "error" -> androidx.compose.ui.graphics.Color(0xFFE5484D)
                            "warning" -> androidx.compose.ui.graphics.Color(0xFFE5A50A)
                            "success" -> androidx.compose.ui.graphics.Color(0xFF36D167)
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}
