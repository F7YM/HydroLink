package com.hydro.link.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydro.link.shared.model.PluginRow
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 点插件卡片弹出的**详情弹窗**（文案与安卓端 `PluginUi.showDetails` 逐行一致）。
 *
 * 之前我做成了单独一页，是我对「不要单独弹出」理解错了——安卓端这里就是弹窗：
 * 列表里放不下的信息（包名/版本/运行时/包含/权限/worker/清单异常）都在这一屏里，
 * 一个「知道了」收尾。行尾的「界面 / 卸载」按钮仍是各自独立的动作。
 */
@Composable
fun PluginDetailDialog(
    miuix: Boolean,
    current: PluginRow?,
    onDismiss: () -> Unit,
) {
    if (!miuix && current == null) return

    // 退出动画：置空 state 不拆组合，让弹窗按 show=false 播完退场（同安卓端）
    var last by remember { mutableStateOf<PluginRow?>(null) }
    if (current != null) last = current
    val plugin = current ?: last ?: return
    val message = pluginDetailMessage(plugin)

    if (miuix) {
        WindowDialog(
            show = current != null,
            onDismissRequest = onDismiss,
            title = plugin.name,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MiuixText(
                    text = message,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "知道了",
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(plugin.name) },
            text = {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { M3TextButton(onClick = onDismiss) { Text("知道了") } },
        )
    }
}

/** 详情正文（与安卓端同一份字段与顺序，不自造内容）。 */
fun pluginDetailMessage(row: PluginRow): String = buildString {
    append("作者：").append(row.author.ifBlank { "—" }).append('\n')
    append("包名：").append(row.id).append('\n')
    append("版本：").append(if (row.version.isBlank()) "—" else "v${row.version}").append('\n')
    append("运行时：").append(row.runtime.ifBlank { "—" }).append('\n')
    if (row.items.isNotBlank()) append("包含：").append(row.items).append('\n')
    append("权限：").append(if (row.permissions.isBlank()) "未申请权限" else row.permissions).append('\n')
    append("worker：").append(if (row.workerOnline) "在线" else "离线（首次请求时自动拉起）")
    if (row.error != null) append('\n').append("清单异常：").append(row.error)
}
