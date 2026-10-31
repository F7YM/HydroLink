package com.hydro.link.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hydro.link.shared.CmdEdit
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.material3.TextButton as M3TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 新建 / 编辑快捷命令的弹窗（两个引擎各一套组件，文案与安卓端一致）。
 *
 * 取消时不能立刻把编辑目标置空——那样组合被拆掉就没有退场动画了；保留最后一份内容，
 * 让弹窗按 `show=false` 播完退场（与安卓端 `CmdEditDialog` 同一套处理）。
 */
@Composable
fun CmdEditDialog(
    miuix: Boolean,
    current: CmdEdit?,
    onDismiss: () -> Unit,
    onSave: (Int?, String, String) -> Boolean,
) {
    if (!miuix && current == null) return

    var last by remember { mutableStateOf<CmdEdit?>(null) }
    if (current != null) last = current
    val dialog = current ?: last ?: return
    val editing = dialog.editIndex != null

    if (miuix) {
        WindowDialog(
            show = current != null,
            onDismissRequest = onDismiss,
            title = if (editing) "编辑快捷命令" else "新建快捷命令",
        ) {
            MiuixCmdFields(
                initialName = dialog.name,
                initialCommand = dialog.command,
                onCancel = onDismiss,
                onConfirm = { name, command ->
                    if (onSave(dialog.editIndex, name, command)) onDismiss() else Unit
                },
            )
        }
    } else {
        var name by remember(dialog) { mutableStateOf(dialog.name) }
        var command by remember(dialog) { mutableStateOf(dialog.command) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (editing) "编辑快捷命令" else "新建快捷命令") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("命令") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                M3TextButton(onClick = {
                    if (onSave(dialog.editIndex, name.trim(), command.trim())) onDismiss()
                }) { Text("确定") }
            },
            dismissButton = {
                M3TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }
}

/** miuix 侧的两个输入框 + 取消/确定（miuix 0.9.4 的 TextField 收 TextFieldValue）。 */
@Composable
private fun MiuixCmdFields(
    initialName: String,
    initialCommand: String,
    onCancel: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName)) }
    var command by remember { mutableStateOf(TextFieldValue(initialCommand)) }
    Column {
        TextField(
            value = name,
            onValueChange = { name = it },
            label = "名称",
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = command,
            onValueChange = { command = it },
            label = "命令",
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "确定",
                onClick = { onConfirm(name.text.trim(), command.text.trim()) },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
