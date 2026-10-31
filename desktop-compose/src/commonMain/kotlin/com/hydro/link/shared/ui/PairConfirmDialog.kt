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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.model.PairRequest
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设备配对授权确认弹窗（双引擎适配：Miuix WindowDialog 与 MD3 AlertDialog）。
 *
 * 当手表或外部设备发起配对请求时，弹出该弹窗供用户确认是否允许连接并授权访问。
 */
@Composable
fun PairConfirmDialog(
    miuix: Boolean,
    target: PairRequest?,
    onCancel: () -> Unit,
    onAccept: (PairRequest) -> Unit,
    onReject: (PairRequest) -> Unit,
) {
    if (!miuix && target == null) return

    // 退场动画：取消或确认时保留最后一份 target，让 WindowDialog 播完退场动画
    var last by remember { mutableStateOf<PairRequest?>(null) }
    if (target != null) last = target
    val pair = target ?: last ?: return

    val deviceName = pair.deviceName.ifBlank { "未知设备" }
    val deviceId = pair.deviceId

    if (miuix) {
        WindowDialog(
            show = target != null,
            onDismissRequest = onCancel,
            title = "设备配对请求",
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "「$deviceName」请求与本机配对连接",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "设备标识：$deviceId\n允许配对后，该设备将获得访问本机受信任接口及数据的权限。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = "拒绝",
                        onClick = { onReject(pair) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "允许",
                        onClick = { onAccept(pair) },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { M3Text("设备配对请求") },
            text = {
                Column {
                    M3Text(
                        text = "「$deviceName」请求与本机配对连接",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    M3Text(
                        text = "设备标识：$deviceId\n允许配对后，该设备将获得访问本机受信任接口及数据的权限。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                M3TextButton(onClick = { onAccept(pair) }) {
                    M3Text("允许")
                }
            },
            dismissButton = {
                M3TextButton(onClick = { onReject(pair) }) {
                    M3Text("拒绝")
                }
            },
        )
    }
}
