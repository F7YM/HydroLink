package com.hydro.link.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.model.PluginRow
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 卸载插件的**二次确认**（与安卓端 `EngineDialog` 同一套流程）。
 *
 * 之前直接点「卸载」就删了插件——删掉的还有用户的插件设置，手滑一下就没了。
 * 确认框里说清三件事：删的是哪个包、插件存储保留（重装后设置还在）、不可撤销。
 */
@Composable
fun UninstallConfirmDialog(
    miuix: Boolean,
    target: PluginRow?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!miuix && target == null) return

    // 退场动画：取消时不能立刻拆组合（那样没有退场动画），保留最后一份内容
    var last by remember { mutableStateOf<PluginRow?>(null) }
    if (target != null) last = target
    val plugin = target ?: last ?: return

    if (miuix) {
        WindowDialog(
            show = target != null,
            onDismissRequest = onCancel,
            title = "卸载插件",
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "确定要卸载「${plugin.name}」吗？",
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "插件包会被删除；插件设置会保留，重新安装后自动恢复。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "卸载",
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { M3Text("卸载插件") },
            text = {
                Column {
                    M3Text(
                        text = "确定要卸载「${plugin.name}」吗？",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    M3Text(
                        text = "插件包会被删除；插件设置会保留，重新安装后自动恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { M3TextButton(onClick = onConfirm) { M3Text("卸载") } },
            dismissButton = { M3TextButton(onClick = onCancel) { M3Text("取消") } },
        )
    }
}
