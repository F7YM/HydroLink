package com.hydro.link.server.ui.material

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hydro.link.server.R

// InstallerX 的分段列表规格：外圆角 16dp、接缝圆角 5dp、容器色 surfaceBright
private val CornerRadius = 16.dp
private val ConnectionRadius = 5.dp

/**
 * 分组列表容器（InstallerX SegmentedColumn 同款）：标题 titleSmall + primary，
 * 条目各自 surfaceBright + 16dp/5dp 圆角，条目间留 1dp 接缝。
 */
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
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceBright,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item()
            }
            if (index != count - 1) {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

class MdSegmentedScope {
    internal val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items.add(content)
    }
}

/** 设置/信息行（InstallerX BaseWidget：Material3 ListItem，titleMedium + bodyMedium）。 */
@Composable
fun MdBaseWidget(
    title: String,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    descriptionColor: Color? = null,
) {
    ListItem(
        modifier = Modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = icon?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 尾部开关的设置行。 */
@Composable
fun MdSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    MdBaseWidget(
        title = title,
        description = description,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/** 尾部单选圆钮的设置行（UI 引擎）。 */
@Composable
fun MdRadioWidget(
    title: String,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MdBaseWidget(
        title = title,
        description = description,
        onClick = onClick,
        trailing = {
            RadioButton(selected = selected, onClick = null)
        },
    )
}

/** 统计卡（InstallerX StatCard：surfaceBright + 数值 headlineMedium）。 */
@Composable
fun MdStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 状态卡（InstallerX StatusCard：彩色容器 + 居中图标/标题/说明）。 */
@Composable
fun MdStatusCard(
    running: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val container = if (running) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val content = if (running) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (running) R.drawable.ic_check_circle_outline else R.drawable.ic_error_outline
                ),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = if (running) "正在运行" else "已停止",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 行尾图标钮（Transparent 底，24dp 图标）。 */
@Composable
fun MdRowIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空态行（放进分段列表里）。 */
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
