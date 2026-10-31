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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hydro.link.shared.market.MarketEntry
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 插件市场安装确认弹窗（双引擎适配：Miuix WindowDialog 与 MD3 AlertDialog）。
 *
 * 与安卓端 `MarketInstaller` + `EngineDialog` 规范完全对齐：
 * 1. 展示插件来源、包路径、版本、运行时、SHA256 指纹与权限清单；
 * 2. 高危权限门控：若声明「屏幕触控与指针模拟 (input)」权限，突出显示红字高危警示，并启动 3 秒强制倒计时；
 * 3. 倒计时期间肯定按钮禁用并显示剩余秒数，倒计时结束后允许安装。
 */
@Composable
fun InstallConfirmDialog(
    miuix: Boolean,
    target: MarketEntry?,
    marketBaseUrl: String,
    onCancel: () -> Unit,
    onConfirm: (MarketEntry) -> Unit,
) {
    if (!miuix && target == null) return

    // 退场动画：取消或确认时保留最后一份 target，让 WindowDialog 播完退场动画
    var last by remember { mutableStateOf<MarketEntry?>(null) }
    if (target != null) last = target
    val entry = target ?: last ?: return

    val hasInput = entry.permissions.any { it.equals("input", ignoreCase = true) }
    val dangerWarning = if (hasInput) {
        "高危权限警示：该插件申请了「屏幕触控与指针模拟 (input)」高危权限，可在后台模拟点击、滑动及按键操作。请确保你信任此插件且此插件来源安全可靠。"
    } else null
    val countdownSeconds = if (hasInput) 3 else 0

    var remaining by remember(entry) { mutableIntStateOf(countdownSeconds) }

    LaunchedEffect(entry) {
        if (countdownSeconds > 0) {
            remaining = countdownSeconds
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
        } else {
            remaining = 0
        }
    }

    val perms = if (entry.permissions.isEmpty()) "未申请任何权限" else entry.permissions.joinToString(", ")
    val message = buildString {
        append("来源：").append(marketBaseUrl).append('\n')
        append("包：").append(entry.url).append('\n')
        append("版本：v").append(entry.version).append("（").append(entry.runtime).append("）\n")
        append("指纹：").append(entry.sha256.take(12)).append('\n')
        append("权限：").append(perms).append('\n')
        append('\n')
        append("插件代码以本应用同等权限运行，可访问本机服务与数据目录，请只安装可信来源的插件。")
    }

    val title = "安装 ${entry.name}"

    if (miuix) {
        WindowDialog(
            show = target != null,
            onDismissRequest = onCancel,
            title = title,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (dangerWarning != null) 160.dp else 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (dangerWarning != null) {
                        MiuixText(
                            text = dangerWarning,
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    MiuixText(
                        text = message,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiuixTextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    val posText = if (remaining > 0) "安装 (${remaining}s)" else "安装"
                    MiuixTextButton(
                        text = posText,
                        enabled = remaining <= 0,
                        onClick = { onConfirm(entry) },
                        colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { M3Text(title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (dangerWarning != null) 180.dp else 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (dangerWarning != null) {
                        M3Text(
                            text = dangerWarning,
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    M3Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                val posText = if (remaining > 0) "安装 (${remaining}s)" else "安装"
                M3Button(
                    onClick = { onConfirm(entry) },
                    enabled = remaining <= 0,
                ) {
                    M3Text(posText)
                }
            },
            dismissButton = {
                M3TextButton(onClick = onCancel) {
                    M3Text("取消")
                }
            },
        )
    }
}
