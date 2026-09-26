package com.hydro.link.server.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.hydro.link.server.R
import com.hydro.link.server.model.LogEntry
import com.hydro.link.server.service.ServerService
import com.hydro.link.server.ui.material.ExpressiveBackButton
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.ui.material.MdEmptyItem
import com.hydro.link.server.ui.material.MdSegmentedColumn
import com.hydro.link.server.ui.material.md3BlurBar
import com.hydro.link.server.ui.material.rememberMd3BlurBackdrop
import com.hydro.link.server.util.DataManager
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 运行日志（独立 Activity，系统返回栈）。界面跟随当前 UI 引擎：
 * miuix → miuix 组件；Google Material 3 → Material3 组件。每秒刷新一次。
 */
class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val miuix = DataManager.get(this).loadConfig().miuix
        SystemBars.apply(this, miuix)
        setContent {
            val content: @Composable () -> Unit = {
                LogsBody(miuix = miuix, onBack = { finish() })
            }
            if (miuix) {
                MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
            } else {
                HydroMaterialTheme { content() }
            }
        }
    }
}

@Composable
private fun LogsBody(miuix: Boolean, onBack: () -> Unit) {
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            logs = ServerService.instance?.getServer()?.getLogs()?.takeLast(400) ?: emptyList()
            delay(1000)
        }
    }
    if (miuix) {
        MiuixLogsContent(logs, onBack)
    } else {
        MdLogsContent(logs, onBack)
    }
}

private fun logColor(level: String): Color = when (level) {
    "error" -> Color(0xFFE5484D)
    "warning" -> Color(0xFFE5A50A)
    "success" -> Color(0xFF36D167)
    else -> Color.Unspecified
}

@Composable
private fun MiuixLogsContent(logs: List<LogEntry>, onBack: () -> Unit) {
    MiuixScaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            MiuixTopAppBar(
                title = "运行日志",
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        if (logs.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = inner.calculateTopPadding() + 8.dp),
            ) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        MiuixText(
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
                contentPadding = PaddingValues(
                    top = inner.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
            ) {
                items(logs.size) { i ->
                    val entry = logs[i]
                    val tint = logColor(entry.level)
                    MiuixText(
                        text = entry.text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (tint == Color.Unspecified) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            tint
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdLogsContent(logs: List<LogEntry>, onBack: () -> Unit) {
    val backdrop = rememberMd3BlurBackdrop(true)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                modifier = Modifier.md3BlurBar(backdrop),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (backdrop != null) Color.Transparent
                    else MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding() + 16.dp,
                    bottom = inner.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item {
                    MdSegmentedColumn {
                        if (logs.isEmpty()) {
                            item { MdEmptyItem("暂无日志") }
                        } else {
                            logs.forEach { entry ->
                                item {
                                    Text(
                                        text = entry.text,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = logColor(entry.level).takeIf { it != Color.Unspecified }
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
