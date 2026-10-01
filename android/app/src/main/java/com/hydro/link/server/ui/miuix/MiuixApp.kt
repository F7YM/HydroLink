package com.hydro.link.server.ui.miuix

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hydro.link.server.R
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * miuix 模式主界面——设计语言对齐 InstallerX-Revived 的 miuix 页面：
 *
 * - 页面 = Scaffold + TopAppBar（标题随 Tab）+ LazyColumn（`scrollEndHaptic` +
 *   `overScrollVertical` 滚动质感，`contentPadding` 水平 0：SmallTitle 自带 28dp、
 *   卡片 12dp 边距 + 行 16dp 内边距，两者正好对齐到 28dp）
 * - 分组 = `SmallTitle` 小标题 + 一张聚合 `Card`（设置行用 [BasicComponent]
 *   整行可点 + 尾部 Switch，列表行用 标题 title4 + 摘要 subtitle + 圆底图标钮）
 * - 状态页 = 彩色 Hero 卡（运行 secondaryContainer / 停止 errorContainer）+
 *   双统计卡（26sp 数值）
 * - 对话框 = [WindowDialog]，提示一律走**系统 Toast**（Snackbar 会被底栏挡住）
 * - 底栏逻辑与 InstallerX 统一：玻璃胶囊（GlassTabHost 渲染、覆盖在页面上）只在
 *   Liquid Glass 开时出现；否则用 miuix 自己的 [NavigationBar] 挂在 Scaffold
 *   `bottomBar`（insets/内容让位由 Scaffold 处理）；页面底色用 `surface`
 *   ——暗色 `background == surfaceContainer == #242424`，用 background 卡片会隐形
 */
@Composable
fun MiuixApp(
    model: MiuixModel,
    actions: MiuixActions,
    selectedIndex: () -> Int,
    tabLabels: List<CharSequence>,
    capsuleOn: Boolean,
    onTabSelected: (Int) -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
) {
    // 5 个 Tab 用 NavHost 管（真实返回栈 + 转场）；权限管理 / 运行日志是独立
    // Activity，走系统返回栈（返回键直接 finish 回主页）。
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val scrollBehavior = MiuixScrollBehavior()

    // 系统返回键让 NavHost 弹栈后，把底栏选中态同步回来（否则页面回到首页、Tab 还停在原处）
    LaunchedEffect(currentRoute) {
        val idx = TAB_ROUTES.indexOf(currentRoute)
        if (idx >= 0 && idx != selectedIndex()) onTabSelected(idx)
    }

    // 外部 Tab 状态（XML 底栏 / 玻璃胶囊）变化 → 导航到对应 Tab（launchSingleTop，
    // 不会往栈里堆重复页面）
    LaunchedEffect(selectedIndex()) {
        val target = TAB_ROUTES[selectedIndex().coerceIn(0, TAB_ROUTES.lastIndex)]
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = tabLabels.getOrNull(selectedIndex())?.toString() ?: "HydroLink",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            // 悬浮胶囊由 GlassTabHost 覆盖渲染（要用整页背景层折射，不能画在采集层里）；
            // 只有不开悬浮时才用引擎自己的底栏
            if (!capsuleOn) {
                MiuixNavBar(tabLabels, selectedIndex, onTabSelected)
            }
        },
        floatingActionButton = {
            // 命令 Tab 才出「新建命令」（与 MD3 的 FAB 显隐逻辑一致）；
            // 胶囊悬浮时抬到胶囊上方，miuix 底栏由 Scaffold 自动让位
            if (selectedIndex() == 2) {
                FloatingActionButton(
                    modifier = Modifier.padding(
                        end = 16.dp,
                        bottom = if (capsuleOn) 96.dp else 0.dp,
                    ),
                    onClick = actions.onAddCommand,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.add_command),
                        modifier = Modifier.size(26.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                    )
                }
            }
        },
    ) { raw ->
        // 胶囊是悬浮的，列表内容在 Scaffold 让位之外再补一段安全区
        val padding = if (capsuleOn) {
            val ld = LocalLayoutDirection.current
            PaddingValues(
                start = raw.calculateStartPadding(ld),
                top = raw.calculateTopPadding(),
                end = raw.calculateEndPadding(ld),
                bottom = raw.calculateBottomPadding() + 96.dp,
            )
        } else {
            raw
        }
        // 滚动行为接给各 Tab 的 LazyColumn（pageScroll() 读这个 Local）：列表滚动时
        // 顶栏大标题折叠。Tab 之间用淡入淡出转场。
        CompositionLocalProvider(LocalMiuixNestedScroll provides scrollBehavior.nestedScrollConnection) {
            // 背景层只包内容（不含底栏），底栏才能拿它做模糊而不自引用
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            ) {
                NavHost(
                navController = navController,
                startDestination = ROUTE_STATUS,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(120)) },
            ) {
                composable(ROUTE_STATUS) {
                    StatusTab(
                        padding = padding,
                        model = model,
                        actions = actions,
                        onOpenPlugins = { onTabSelected(3) },
                    )
                }
                composable(ROUTE_DEVICES) { DevicesTab(padding, model, actions) }
                composable(ROUTE_COMMANDS) { CommandsTab(padding, model, actions) }
                composable(ROUTE_PLUGINS) { PluginsTab(padding, model, actions) }
                composable(ROUTE_SETTINGS) { SettingsTab(padding, model, actions) }
                }
            }
        }
    }

    // 对话框（WindowDialog 是窗口级弹窗，不需要 Scaffold 包裹）
    CmdEditDialog(model, actions)
    RootConfirmDialog(model, actions)
    PluginConfirmDialog(model)
}

private const val ROUTE_STATUS = "home"
private const val ROUTE_DEVICES = "devices"
private const val ROUTE_COMMANDS = "commands"
private const val ROUTE_PLUGINS = "plugins"
private const val ROUTE_SETTINGS = "settings"

private val TAB_ROUTES = listOf(
    ROUTE_STATUS, ROUTE_DEVICES, ROUTE_COMMANDS, ROUTE_PLUGINS, ROUTE_SETTINGS,
)

// ---------- 公共件 ----------

/** 顶栏折叠用的滚动行为：MiuixApp 往下喂，各 Tab 的列表用 [pageScroll] 接。 */
private val LocalMiuixNestedScroll = compositionLocalOf<NestedScrollConnection?> { null }

/** 页面列表统一修饰：滚动质感 + 顶栏折叠连接。 */
@Composable
private fun Modifier.pageScroll(): Modifier {
    val connection = LocalMiuixNestedScroll.current
    return this
        .fillMaxSize()
        .scrollEndHaptic()
        .overScrollVertical()
        .then(if (connection != null) Modifier.nestedScroll(connection) else Modifier)
}

/** 列表内容边距：水平 0（分组缩进由 SmallTitle/卡片各层自带），底部为 Scaffold 让位值。 */
@Composable
private fun listPadding(padding: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(layoutDirection),
        top = padding.calculateTopPadding() + 8.dp,
        end = padding.calculateEndPadding(layoutDirection),
        bottom = padding.calculateBottomPadding(),
    )
}

/** miuix 底栏（InstallerX 同款：NavigationBar 挂 Scaffold bottomBar，insets 自带）。 */
@Composable
private fun MiuixNavBar(
    tabLabels: List<CharSequence>,
    selectedIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
) {
    NavigationBar(color = MiuixTheme.colorScheme.surface) {
        tabLabels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selectedIndex() == index,
                onClick = { onTabSelected(index) },
                // 与 res/menu/bottom_nav_menu.xml 的图标顺序一致
                icon = ImageVector.vectorResource(TAB_ICON_RES[index.coerceIn(TAB_ICON_RES.indices)]),
                label = label.toString(),
            )
        }
    }
}

private val TAB_ICON_RES = listOf(
    R.drawable.ic_nav_status,
    R.drawable.ic_nav_devices,
    R.drawable.ic_nav_commands,
    R.drawable.ic_nav_plugins,
    R.drawable.ic_settings,
)

/** 同一行聚合卡片的分组容器（InstallerX 式：12dp 页边距 + 12dp 组间距）。 */
private fun Modifier.sectionCard(): Modifier =
    this.padding(horizontal = 12.dp).padding(bottom = 12.dp)

@Composable
private fun resColor(colorRes: Int): Color =
    Color(ContextCompat.getColor(LocalContext.current, colorRes))

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Success -> resColor(R.color.md_theme_success)
    Tone.Warning -> resColor(R.color.md_theme_warning)
    Tone.Error -> resColor(R.color.md_theme_error)
    Tone.Outline -> MiuixTheme.colorScheme.onSurface
}

/** 圆底行动图标钮（InstallerX 列表行样式：secondaryContainer 圆底 + 20dp 图标）。 */
@Composable
private fun RowActionButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    /** false = 灰显不可点（如 HydroLink 版本太低、装不了更新）。 */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        minHeight = 36.dp,
        minWidth = 36.dp,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurface.copy(
                alpha = when {
                    !enabled -> 0.3f
                    isSystemInDarkTheme() -> 0.7f
                    else -> 0.9f
                },
            ),
        )
    }
}

/** 行内文字链钮：透明底 + 文字色（MIUI 行内动作样式，不用实心大药丸）。 */
@Composable
private fun LinkTextButton(
    text: String,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        text = text,
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = if (primary) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
            disabledTextColor = MiuixTheme.colorScheme.disabledOnSurface,
        ),
    )
}

/** 设置行（整行可点 + 尾部 Switch）：对齐 InstallerX 的 MiuixSwitchWidget。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/** 统计卡（InstallerX 首页样式：15sp 灰标题 + 26sp 半粗数值；可点 + Tilt 按压畸变）。 */
@Composable
private fun StatCard(
    title: String,
    value: String,
    valueColor: Color,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onRefresh,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                // 手表名可能很长：自动换行（多行完整显示，不截断）
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 服务状态卡（InstallerX 首页「正在作为默认安装器工作」同款）：
 * 启用亮色绿 0xFFDFFAE4 / 暗色绿 0xFF1A3825，停用亮 0xFFFAEEEE / 暗 0xFF381A1A；
 * 右下角装饰大图标（启用 CheckCircleOutline / 停用 ErrorOutline，同款 0xFF36D167 /
 * 0xFFD13636 着色，170dp 在半宽卡里等比缩到 132dp）；整卡可点 + Tilt 按压畸变。
 */
@Composable
private fun RunningCard(
    running: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val container = if (running) {
        if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (dark) Color(0xFF381A1A) else Color(0xFFFAEEEE)
    }
    val iconTint = if (running) Color(0xFF36D167) else Color(0xFFD13636)
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = container),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        ) {
            Icon(
                painter = painterResource(
                    if (running) R.drawable.ic_check_circle_outline
                    else R.drawable.ic_error_outline
                ),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 38.dp, y = 48.dp)
                    .size(160.dp),
                tint = iconTint,
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- 状态 Tab ----------

@Composable
private fun StatusTab(
    padding: PaddingValues,
    model: MiuixModel,
    actions: MiuixActions,
    onOpenPlugins: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .pageScroll(),
        contentPadding = listPadding(padding),
        overscrollEffect = null,
    ) {
        // 状态行：正在运行（InstallerX 同款彩色状态卡 + 大图标，正方形）与已连接并排
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sectionCard(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RunningCard(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    running = model.serverRunning,
                    title = stringResource(
                        if (model.serverRunning) R.string.server_running
                        else R.string.server_stopped
                    ),
                    summary = run {
                        val ip = model.ipText.removePrefix("IP: ").trim()
                        val port = model.portText.removePrefix("端口: ").trim()
                        when {
                            ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
                            ip.isNotEmpty() -> ip
                            else -> port
                        }
                    },
                    onClick = actions.onOpenLogs,
                )
                StatCard(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    title = model.interconnectDetail,
                    value = model.interconnectText,
                    valueColor = toneColor(model.interconnectTone),
                    onRefresh = actions.onRefresh,
                )
            }
        }

        // 权限管理 + 插件数量：并排两张卡（权限卡右侧带箭头，插件数量可点切到插件 Tab）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .sectionCard(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    title = "权限管理",
                    value = if (model.accVisible || model.notifListenerVisible || model.notifPermVisible) {
                        "请授予权限"
                    } else {
                        "已授权"
                    },
                    valueColor = MiuixTheme.colorScheme.onSurface,
                    onRefresh = actions.onOpenPermissions,
                )
                StatCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    title = "插件数量",
                    value = model.plugins.size.toString(),
                    valueColor = MiuixTheme.colorScheme.onSurface,
                    onRefresh = onOpenPlugins,
                )
            }
        }

        // 服务器信息（InstallerX「机型 / 系统」式：一张卡里多行 label + value）
        item {
            Card(modifier = Modifier.sectionCard()) {
                BasicComponent(title = "IP 地址", summary = model.ipText.removePrefix("IP: ").trim())
                BasicComponent(title = "端口", summary = model.portText.removePrefix("端口: ").trim())
                BasicComponent(title = "服务器版本", summary = model.serverVersion)
            }
        }
    }
}

// ---------- 设备 Tab ----------

@Composable
private fun DevicesTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    LazyColumn(
        modifier = Modifier
            .pageScroll(),
        contentPadding = listPadding(padding),
        overscrollEffect = null,
    ) {
        // 配对请求（弹窗被划掉时的兜底入口：允许 / 拒绝都在这）
        if (model.pairs.isNotEmpty()) {
            item { SmallTitle("配对请求") }
            item {
                Card(modifier = Modifier.sectionCard()) {
                    model.pairs.forEach { pair ->
                        ListRow(
                            title = pair.deviceName.ifBlank { pair.deviceId.take(16) },
                            summary = "请求配对 · ${pair.deviceId.take(16)}...",
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RowActionButton(
                                        icon = R.drawable.ic_check,
                                        contentDescription = "允许",
                                    ) { actions.onAcceptPair(pair) }
                                    RowActionButton(
                                        icon = R.drawable.ic_close,
                                        contentDescription = "拒绝",
                                    ) { actions.onRejectPair(pair) }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { SmallTitle("已配对设备") }
        if (model.devices.isEmpty()) {
            item { EmptyHint(stringResource(R.string.no_devices)) }
        } else {
            item {
                Card(modifier = Modifier.sectionCard()) {
                    model.devices.forEach { device ->
                        ListRow(
                            title = device.deviceName.ifEmpty { "Unknown" },
                            summary = device.deviceId.take(16) + "...",
                            actions = {
                                RowActionButton(
                                    icon = R.drawable.ic_delete,
                                    contentDescription = stringResource(R.string.delete),
                                ) { actions.onDeleteDevice(device) }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- 命令 Tab ----------

@Composable
private fun CommandsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    LazyColumn(
        modifier = Modifier
            .pageScroll(),
        contentPadding = listPadding(padding),
        overscrollEffect = null,
    ) {
        item { SmallTitle("快捷命令") }
        if (model.commands.isEmpty()) {
            item { EmptyHint(stringResource(R.string.no_commands)) }
        } else {
            item {
                Card(modifier = Modifier.sectionCard()) {
                    model.commands.forEachIndexed { index, cmd ->
                        ListRow(
                            title = cmd.name.ifEmpty { "命令 ${index + 1}" },
                            summary = cmd.command,
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RowActionButton(
                                        icon = R.drawable.ic_edit,
                                        contentDescription = "编辑",
                                    ) { actions.onEditCommand(index) }
                                    RowActionButton(
                                        icon = R.drawable.ic_delete,
                                        contentDescription = stringResource(R.string.delete),
                                    ) { actions.onDeleteCommand(index) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- 插件 Tab ----------

@Composable
private fun PluginsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    LazyColumn(
        modifier = Modifier
            .pageScroll(),
        contentPadding = listPadding(padding),
        overscrollEffect = null,
    ) {
        item { SmallTitle("插件") }
        // 插件市场：独立页（搜索/分类/一键装），放在最上面当入口
        item {
            Card(modifier = Modifier.sectionCard()) {
                BasicComponent(
                    title = "插件市场",
                    summary = "从 GitHub 索引仓浏览并安装插件",
                    onClick = { actions.onOpenMarket() },
                )
            }
        }
        item {
            Card(modifier = Modifier.sectionCard()) {
                // 摘要行 + 刷新/安装
                BasicComponent(
                    title = model.pluginSummary,
                    endActions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowActionButton(
                                icon = R.drawable.ic_plugin_refresh,
                                contentDescription = "刷新",
                            ) { actions.onRefreshPlugins() }
                            RowActionButton(
                                icon = R.drawable.ic_add,
                                contentDescription = "安装",
                            ) { actions.onInstallPlugin() }
                        }
                    },
                )
                val message = model.pluginMessage
                if (message != null) {
                    BasicComponent(
                        title = message,
                        summaryColor = BasicComponentColors(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            disabledColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                        ),
                    )
                } else {
                    model.plugins.forEach { row ->
                        BasicComponent(
                            title = row.name,
                            // 副标题只留版本号：作者/包名/运行时等在详情弹窗里看
                            summary = if (row.version.isBlank()) null else "v${row.version}",
                            onClick = { actions.onPluginDetails(row) },
                            endActions = {
                                // 两个动作钮之间要留 8dp 间距（命令行是这么排的，这里漏了）
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // 有新版时，升级钮排在「界面」左边；版本不够时灰显
                                    val update = model.pluginUpdates.firstOrNull { it.entry.id == row.id }
                                    if (update != null) {
                                        RowActionButton(
                                            icon = R.drawable.ic_upgrade,
                                            contentDescription = "更新到 v${update.entry.version}",
                                            enabled = update.compatible,
                                        ) { actions.onOpenUpdate(update) }
                                    }
                                    // 只有装了 WebUI（web/index.html）的插件才显示「界面」按钮
                                    if (row.hasWeb) {
                                        RowActionButton(
                                            icon = R.drawable.ic_plugin_ui,
                                            contentDescription = "界面",
                                        ) { actions.onPluginOpenWebUi(row) }
                                    }
                                    RowActionButton(
                                        icon = R.drawable.ic_plugin_delete,
                                        contentDescription = "卸载",
                                    ) { actions.onPluginUninstall(row) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- 设置 Tab ----------

@Composable
private fun SettingsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    LazyColumn(
        modifier = Modifier
            .pageScroll(),
        contentPadding = listPadding(padding),
        overscrollEffect = null,
    ) {
        item { SmallTitle("关于") }
        item {
            Card(modifier = Modifier.sectionCard()) {
                BasicComponent(title = model.appName, summary = "By Float11")
            }
        }

        item { SmallTitle("服务") }
        item {
            Card(modifier = Modifier.sectionCard()) {
                // 授权器（无 / Root / Shizuku，默认无）——InstallerX 同款下拉
                WindowSpinnerPreference(
                    title = "授权器",
                    items = listOf(
                        DropdownItem(text = "无", summary = "以应用自身权限执行"),
                        DropdownItem(text = "Root", summary = "su 执行"),
                        DropdownItem(text = "Shizuku", summary = "Shizuku 用户服务执行"),
                    ),
                    selectedIndex = when (model.authorizer) {
                        "root" -> 1
                        "shizuku" -> 2
                        else -> 0
                    },
                    onSelectedIndexChange = { index ->
                        actions.onSetAuthorizer(
                            when (index) {
                                1 -> "root"
                                2 -> "shizuku"
                                else -> "none"
                            }
                        )
                    },
                )
                BasicComponent(
                    title = "授权状态",
                    summary = model.authorizerStatus,
                    onClick = actions.onRefreshAuthorizerStatus,
                    endActions = {
                        if (model.authorizer == "shizuku" && model.authorizerNeedsGrant) {
                            LinkTextButton(text = "去授权", onClick = actions.onAuthorizeShizuku)
                        }
                    },
                )
                SwitchRow(
                    title = "开机后自动启动服务",
                    summary = "重启手机后自动启动服务",
                    checked = model.bootStart,
                    onCheckedChange = actions.onToggleBoot,
                )
            }
        }

        item { SmallTitle("主题风格") }
        item {
            Card(modifier = Modifier.sectionCard()) {
                // UI 引擎：InstallerX 同款 WindowSpinnerPreference（下拉选择，不弹自造对话框）
                WindowSpinnerPreference(
                    title = "UI 引擎",
                    items = listOf(
                        DropdownItem(text = "Miuix", summary = "类 HyperOS 风格界面"),
                        DropdownItem(text = "Google Material 3", summary = "标准 Material Design 界面"),
                    ),
                    selectedIndex = if (model.miuix) 0 else 1,
                    onSelectedIndexChange = { actions.onToggleMiuix(it == 0) },
                )
                SwitchRow(
                    title = "启用悬浮底栏",
                    summary = "在屏幕底部使用悬浮样式的导航栏",
                    checked = model.liquidGlass,
                    onCheckedChange = actions.onToggleGlass,
                )
            }
        }
    }
}

// ---------- 列表行 / 空态 ----------

/** 列表行：title4 粗标题 + subtitle 灰摘要 + 尾部圆底图标钮（InstallerX 列表卡行式）。 */
@Composable
private fun ListRow(title: String, summary: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        actions()
    }
}

/** 空态也装进卡片（保持整页的卡片质感，不出现裸文字）。 */
@Composable
private fun EmptyHint(text: String) {
    Card(modifier = Modifier.sectionCard()) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------- 对话框 ----------

/** 新建 / 编辑命令（WindowDialog + miuix TextField，替代 MD3 的 XML 弹窗）。 */
@Composable
private fun CmdEditDialog(model: MiuixModel, actions: MiuixActions) {
    // 退出动画：取消时不能立刻把 state 置空拆掉组合（那样没有退场动画）——
    // 保留最后一份内容，让 WindowDialog 按 show=false 播完退场（同 RootConfirmDialog）
    val current = model.cmdDialog
    var last by remember { mutableStateOf<CmdEdit?>(null) }
    if (current != null) last = current
    val dialog = current ?: last ?: return
    var name by remember(dialog) { mutableStateOf(dialog.name) }
    var command by remember(dialog) { mutableStateOf(dialog.command) }

    WindowDialog(
        show = current != null,
        onDismissRequest = { model.cmdDialog = null },
        title = if (dialog.editIndex == null) "新建快捷命令" else "编辑快捷命令",
        content = {
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
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.cancel),
                        onClick = { model.cmdDialog = null },
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.confirm),
                        onClick = {
                            val ok = actions.onSaveCommand(dialog.editIndex, name.trim(), command.trim())
                            if (ok) model.cmdDialog = null
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/** root 开启确认（WindowDialog，文案与 MD3 弹窗一致）。 */
@Composable
private fun RootConfirmDialog(model: MiuixModel, actions: MiuixActions) {
    WindowDialog(
        show = model.rootConfirm,
        onDismissRequest = actions.onRootConfirmDismiss,
        title = "使用 root 执行快速命令？",
        content = {
            Column {
                Text(
                    text = "使用 root 权限执行快速命令有安全风险，请自行检查命令。\n\n" +
                        "开启后 quick command 与插件请求的命令都会以 root 身份运行，请只安装可信插件。",
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "取消",
                        onClick = actions.onRootConfirmDismiss,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "仍然开启",
                        onClick = actions.onRootConfirmAccept,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/**
 * 插件的安装确认 / 卸载确认 / 卡片详情（WindowDialog）。
 *
 * 同一份文案由 [com.hydro.link.server.ui.PluginUi] 维护，MD3 引擎走
 * MaterialAlertDialogBuilder、miuix 引擎经 PluginUi 的钩子投到这里——两个引擎
 * 都不能出现对方风格的弹窗。消息可能很长（安装确认要列权限），给它限高可滚动。
 */
@Composable
private fun PluginConfirmDialog(model: MiuixModel) {
    // 退出动画同 CmdEditDialog：置空 state 不拆组合，让 WindowDialog 按 show=false 退场
    val current = model.pluginDialog
    var last by remember { mutableStateOf<ConfirmDialog?>(null) }
    if (current != null) last = current
    val dialog = current ?: last ?: return
    var remaining by remember(dialog) { mutableStateOf(dialog.countdownSeconds) }
    LaunchedEffect(dialog) {
        if (dialog.countdownSeconds > 0) {
            remaining = dialog.countdownSeconds
            while (remaining > 0) {
                kotlinx.coroutines.delay(1000)
                remaining--
            }
        }
    }
    WindowDialog(
        show = current != null,
        onDismissRequest = { model.pluginDialog = null },
        title = dialog.title,
        content = {
            Column {
                if (!dialog.dangerWarning.isNullOrBlank()) {
                    Text(
                        text = dialog.dangerWarning,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Text(
                    text = dialog.message,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val negative = dialog.negative
                    if (negative != null) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = negative,
                            onClick = {
                                val reject = dialog.onNegative
                                model.pluginDialog = null
                                reject?.invoke()
                            },
                        )
                    }
                    val posText = if (remaining > 0) "${dialog.positive} (${remaining}s)" else dialog.positive
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = posText,
                        // 灰显场景（如宿主版本太低、更新装不了，或正在倒计时）：enabled=false 走 miuix 自带的禁用色
                        enabled = dialog.positiveEnabled && remaining <= 0,
                        onClick = {
                            val confirm = dialog.onConfirm
                            model.pluginDialog = null
                            confirm?.invoke()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
