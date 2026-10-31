package com.hydro.link.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.model.LogEntry
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行日志窗口的内容。
 *
 * 安卓端是独立 Activity + 系统返回栈（`LogsActivity`），桌面没有返回栈，所以按同样的
 * 「独立页」观感开一个独立窗口（见 `Main.kt`：点运行状态卡 → 开新窗口）。
 * 版式照安卓端：顶栏「运行日志」+ 逐行日志，error/warning/success 三种语气色。
 */
@Composable
fun LogsBody(
    logs: List<LogEntry>,
    miuix: Boolean,
    modifier: Modifier = Modifier,
) {
    if (miuix) {
        MiuixLogsContent(logs, modifier)
    } else {
        MdLogsContent(logs, modifier)
    }
}

/** 日志语气色（与安卓端 LogsActivity 一致）。 */
private fun logColor(level: String): Color = when (level) {
    "error" -> Color(0xFFE5484D)
    "warning" -> Color(0xFFE5A50A)
    "success" -> Color(0xFF36D167)
    else -> Color.Unspecified
}

@Composable
private fun MiuixLogsContent(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        modifier = modifier,
        topBar = { SmallTitle("运行日志") },
    ) { inner ->
        val padding = PaddingValues(
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + 8.dp,
        )
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                EmptyLogHint("暂无日志", MiuixTheme.colorScheme.onSurfaceVariantSummary, padding)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(logs) { entry ->
                    Text(
                        text = entry.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                                        color = logColor(entry.level).let { if (it == Color.Unspecified) MiuixTheme.colorScheme.onSurface else it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MdLogsContent(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) { inner ->
        val padding = PaddingValues(
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + 8.dp,
        )
        Column(Modifier.fillMaxSize()) {
            SmallTitleCompat("运行日志")
            if (logs.isEmpty()) {
                EmptyLogHint("暂无日志", MaterialTheme.colorScheme.onSurfaceVariant, padding)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 880.dp),
                    contentPadding = padding,
                ) {
                    items(logs) { entry ->
                        Text(
                            text = entry.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = logColor(entry.level).let { if (it == Color.Unspecified) MaterialTheme.colorScheme.onSurface else it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** MD3 分组小标题（与首页 MdSegmentedColumn 的标题同一套样式）。 */
@Composable
private fun SmallTitleCompat(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun EmptyLogHint(text: String, color: Color, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        Text(
            text = text,
            color = color,
            fontSize = 14.sp,
            modifier = Modifier.padding(24.dp),
        )
    }
}
