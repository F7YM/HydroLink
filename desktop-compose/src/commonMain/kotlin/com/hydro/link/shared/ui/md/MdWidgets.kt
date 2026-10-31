package com.hydro.link.shared.ui.md

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MD3 侧的列表组件（照安卓端 `ui/material/MaterialWidgets.kt` 抄，视觉与「服务器信息」
 * 那组一致）：分组用 [MdSegmentedColumn]，行用 [MdBaseWidget]，单选用 [MdRadioWidget]。
 *
 * 之所以搬一份而不是引用安卓端：安卓那份在 androidMain 里、还带 R 资源与 haptics；
 * 等两端真共享时再合到一处（desktop-compose 的 commonMain 已经是为此准备的）。
 */

/** 分组圆角常量（与安卓端一致）。 */
private val CornerRadius = 20.dp
private val ConnectionRadius = 4.dp

class MdSegmentedScope {
    internal val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/** 一组圆角连续的列表行（安卓端同名组件）。 */
@Composable
fun MdSegmentedColumn(
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: MdSegmentedScope.() -> Unit,
) {
    val scope = MdSegmentedScope().apply(content)
    if (scope.items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
            )
        }
        val count = scope.items.size
        scope.items.forEachIndexed { index, item ->
            val shape = when {
                count == 1 -> RoundedCornerShape(CornerRadius)
                index == 0 -> RoundedCornerShape(
                    topStart = CornerRadius, topEnd = CornerRadius,
                    bottomStart = ConnectionRadius, bottomEnd = ConnectionRadius,
                )
                index == count - 1 -> RoundedCornerShape(
                    topStart = ConnectionRadius, topEnd = ConnectionRadius,
                    bottomStart = CornerRadius, bottomEnd = CornerRadius,
                )
                else -> RoundedCornerShape(ConnectionRadius)
            }
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                item()
            }
            if (index != count - 1) {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/** 列表行（标题 + 副标题 + 可选图标/尾部）。 */
@Composable
fun MdBaseWidget(
    title: String,
    description: String? = null,
    icon: (@Composable (() -> Unit))? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    descriptionColor: Color? = null,
    descriptionMaxLines: Int = 2,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = description?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = descriptionColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = icon,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 长文本行（介绍/更新日志用）：**不截断**，与安卓端 MdNoteWidget 一致。 */
@Composable
fun MdNoteWidget(
    title: String,
    text: String,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

/** 单选行（设置里的「主题风格」「授权器」用的就是它）。 */
@Composable
fun MdRadioWidget(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MdBaseWidget(
        title = title,
        description = description,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
            }
        },
    )
}

/** 开关行。 */
@Composable
fun MdSwitchWidget(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    MdBaseWidget(
        title = title,
        description = description,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** 空态提示行。 */
@Composable
fun MdEmptyItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 行尾图标钮。 */
@Composable
fun MdRowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
        )
    }
}

/** 小号标签（权限/分类徽章）。 */
@Composable
fun MdTag(text: String, fontSize: Dp = 11.dp) {
    Text(text = text, fontSize = fontSize.value.sp, color = MaterialTheme.colorScheme.primary)
}

/**
 * 端口输入框（MD3 侧）。
 *
 * 只在**失焦或回车**时提交：合法且与现值不同才回调，输入中间不会触发重启；
 * 提交后回显真实端口，成功则由 [port] 自动刷新。
 * 输入中获得焦点时锁定背景轮询刷新，防止输入被自动覆盖。
 * 宽度固定 120dp，右侧对齐，和其它行的尾部控件对齐。
 */
@Composable
fun MdPortField(
    port: Int,
    onPortChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(port.toString()) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(port) {
        if (!isFocused) {
            text = port.toString()
        }
    }

    // 提交当前输入：合法且与现值不同才回调；随后把输入框回显成真实端口。
    // 成功时 port 会变、LaunchedEffect 再刷新；失败时保持旧值——同时天然抑制
    // 回车（onDone）与失焦（onFocusChanged）对同一值的重复提交。
    fun submit() {
        val parsed = text.toIntOrNull()
        if (parsed != null && parsed in 1..65535 && parsed != port) {
            onPortChange(parsed)
        }
        text = port.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            val digits = next.filter { it.isDigit() }.take(5)
            text = digits
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .width(120.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) submit()
            },
    )
}

/** 市场页的搜索框。 */
@Composable
fun MdSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text("搜索插件") },
        modifier = modifier,
    )
}
