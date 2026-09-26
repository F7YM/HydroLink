package com.hydro.link.server.ui.material

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.server.R
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

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

/** 尾部开关的设置行（InstallerX SwitchWidget：M3 Expressive 开关，滑块里带对号/叉）。 */
@Composable
fun MdSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val handle: (Boolean) -> Unit = { value ->
        haptic.performHapticFeedback(
            if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
        )
        onCheckedChange(value)
    }
    MdBaseWidget(
        title = title,
        description = description,
        onClick = { handle(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = handle,
                colors = SwitchDefaults.colors(
                    checkedIconColor = MaterialTheme.colorScheme.primary,
                    uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                thumbContent = {
                    Icon(
                        painter = painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
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

/**
 * 顶栏模糊（与悬浮底栏同一块背景层）：surfaceContainer 80% 混合 + 25dp 高斯模糊，
 * 与 InstallerX 的 installerMaterial3BlurEffect 一致。backdrop 为 null（低版本/未开）
 * 时不加任何修饰，顶栏用不透明 surfaceContainer。
 */
@Composable
fun Modifier.md3BlurBar(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    val blend = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)
    return this.textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors = BlurColors(blendColors = listOf(BlendColorEntry(blend))),
    )
}

/** MD3 模糊背景层（surfaceContainer 打底，避免 alpha 混合artifacts）；不支持时返回 null。 */
@Composable
fun rememberMd3BlurBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surface = MaterialTheme.colorScheme.surfaceContainer
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/** 顶栏返回按钮（InstallerX ExpressiveBackButton：圆形底 + onSurfaceVariant 图标）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = "返回",
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

/**
 * MD3 引擎的悬浮底栏（InstallerX FloatingBottomBar 的 Material 形态，**不是**
 * Liquid Glass）：底部居中胶囊（surfaceContainer + 轻微阴影，高 64dp、项最小宽
 * 76dp），选中项是主色胶囊底 + onPrimary 图标，标签 11sp。
 */
@Composable
fun MdFloatingNavBar(
    labels: List<CharSequence>,
    @DrawableRes icons: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(icons[index.coerceIn(icons.indices)]),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = label.toString(),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
