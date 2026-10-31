package com.hydro.link.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.update.AppUpdateHelper
import com.hydro.link.shared.update.AppUpdateInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 应用版本更新弹窗（双引擎适配：Miuix WindowDialog 与 MD3 AlertDialog）。
 *
 * 展示新版本信息、发布日期、网盘提取码提示与格式化更新日志。
 * 肯定按钮使用强调色突出「前往网盘下载」，非强更时否定按钮为「稍后提醒」。
 * 强制更新时拦截外部空白处点击与取消关闭，强制单按钮前往下载。
 */
@Composable
fun AppUpdateDialog(
    miuix: Boolean,
    target: AppUpdateInfo?,
    currentVersion: String,
    onCancel: () -> Unit,
    onConfirm: (AppUpdateInfo) -> Unit,
) {
    if (!miuix && target == null) return

    var last by remember { mutableStateOf<AppUpdateInfo?>(null) }
    if (target != null) last = target
    val update = target ?: last ?: return

    val isForce = AppUpdateHelper.isForceUpdate(update, currentVersion)
    val title = "发现新版本 v${update.version}"
    val message = AppUpdateHelper.dialogText(update, currentVersion)

    if (miuix) {
        val scrollState = rememberScrollState()
        WindowDialog(
            show = target != null,
            onDismissRequest = {
                if (!isForce) onCancel()
            },
            title = title,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .drawVerticalScrollbar(
                            state = scrollState,
                            thumbColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isForce) {
                        TextButton(
                            text = "稍后提醒",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        text = "前往网盘下载",
                        onClick = { onConfirm(update) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    } else {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = {
                if (!isForce) onCancel()
            },
            title = {
                M3Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .drawVerticalScrollbar(
                            state = scrollState,
                            thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                ) {
                    M3Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = if (!isForce) {
                {
                    M3TextButton(onClick = onCancel) {
                        M3Text("稍后提醒")
                    }
                }
            } else null,
            confirmButton = {
                M3Button(onClick = { onConfirm(update) }) {
                    M3Text("前往网盘下载")
                }
            },
        )
    }
}
