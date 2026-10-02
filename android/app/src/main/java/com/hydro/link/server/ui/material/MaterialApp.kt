package com.hydro.link.server.ui.material

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hydro.link.server.R
import com.hydro.link.server.ui.material.md3BlurBar
import com.hydro.link.server.ui.miuix.MiuixActions
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.hydro.link.server.ui.miuix.MiuixModel
import com.hydro.link.server.ui.miuix.Tone

/** 悬浮底栏时页面尾部额外留白（内容流到胶囊下面，末端可滚上来）。 */
private val LocalMdExtraBottom = androidx.compose.runtime.compositionLocalOf { 0.dp }

private val TAB_ICONS = listOf(
    R.drawable.ic_nav_status,
    R.drawable.ic_nav_devices,
    R.drawable.ic_nav_commands,
    R.drawable.ic_nav_plugins,
    R.drawable.ic_settings,
)

/**
 * MD3 界面引擎（Compose Material3，与 InstallerX 同一套组件）：
 * Scaffold + LargeFlexibleTopAppBar + Material3 ListItem/Switch/RadioButton，
 * 分组用 [MdSegmentedColumn]（16dp/5dp 圆角 + surfaceBright）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialApp(
    model: MiuixModel,
    actions: MiuixActions,
    selectedIndex: () -> Int,
    tabLabels: List<CharSequence>,
    floatingBar: Boolean,
    onTabSelected: (Int) -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
) {
    HydroMaterialTheme {
        // 授权器二级页：系统返回先关页面（不退出设置 Tab、更不回桌面）
        androidx.activity.compose.BackHandler(enabled = model.authorizerPage) {
            model.authorizerPage = false
        }
        // 新建/编辑命令弹窗（此前 MD3 侧只设了 model.cmdDialog 却没人渲染 → FAB 点了没反应）
        MdCmdEditDialog(model, actions)
        val index = selectedIndex().coerceIn(0, 4)
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                LargeFlexibleTopAppBar(
                    modifier = Modifier.md3BlurBar(backdrop),
                    title = {
                        androidx.compose.animation.AnimatedContent(
                            targetState = model.authorizerPage,
                            transitionSpec = {
                                if (targetState) {
                                    slideInHorizontally { it / 3 } + fadeIn() togetherWith
                                        slideOutHorizontally { -it / 3 } + fadeOut()
                                } else {
                                    slideInHorizontally { -it / 3 } + fadeIn() togetherWith
                                        slideOutHorizontally { it / 3 } + fadeOut()
                                }
                            },
                            label = "topBarTitle",
                        ) { page ->
                            Text(
                                text = if (page) {
                                    "授权器"
                                } else {
                                    tabLabels.getOrNull(index)?.toString() ?: "HydroLink"
                                },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    },
                    // 授权器二级页：顶栏给返回箭头（底栏此时整页隐藏）
                    navigationIcon = {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = model.authorizerPage,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ExpressiveBackButton(onClick = { model.authorizerPage = false })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (backdrop != null) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = if (backdrop != null) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                // 悬浮胶囊由 GlassTabHost 覆盖渲染；只有不开悬浮时才用 ShortNavigationBar。
                // 授权器二级页整页覆盖，底栏一起收起
                if (!floatingBar) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !model.authorizerPage,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            tabLabels.forEachIndexed { i, label ->
                                ShortNavigationBarItem(
                                    selected = index == i,
                                    onClick = { onTabSelected(i) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(TAB_ICONS[i.coerceIn(TAB_ICONS.indices)]),
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(label.toString()) },
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (index == 2 && !model.authorizerPage) {
                    FloatingActionButton(
                        // 悬浮胶囊压在底部，FAB 要抬到胶囊上方（同 miuix 的 96dp）
                        modifier = Modifier.padding(
                            end = 16.dp,
                            bottom = if (floatingBar) 96.dp else 0.dp,
                        ),
                        onClick = actions.onAddCommand,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "新建命令")
                    }
                }
            },
        ) { padding ->
            CompositionLocalProvider(
                LocalMdExtraBottom provides
                    if (floatingBar && !model.authorizerPage) 96.dp else 0.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    // 设置列表一直保持组合（子页返回时不丢滚动位置）
                    when (index) {
                        0 -> HomeTab(padding, model, actions, onTabSelected)
                        1 -> DevicesTab(padding, model, actions)
                        2 -> CommandsTab(padding, model, actions)
                        3 -> PluginsTab(padding, model, actions)
                        else -> SettingsTab(padding, model, actions)
                    }
                    // 授权器二级页：整页覆盖、从右滑入
                    androidx.compose.animation.AnimatedVisibility(
                        visible = model.authorizerPage,
                        modifier = Modifier.fillMaxSize(),
                        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            AuthorizerPage(padding, model, actions)
                        }
                    }
                }
            }
        }
    }
}

/** 页面列表：左右 16dp、顶/底接 Scaffold 让位再补 16dp（InstallerX 同间距）。 */
@Composable
private fun MdPage(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    val extraBottom = LocalMdExtraBottom.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 16.dp + extraBottom,
        ),
        content = content,
    )
}

@Composable
private fun HomeTab(
    padding: PaddingValues,
    model: MiuixModel,
    actions: MiuixActions,
    onTabSelected: (Int) -> Unit,
) {
    val ip = model.ipText.removePrefix("IP: ").trim()
    val port = model.portText.removePrefix("端口: ").trim()
    val ipPort = when {
        ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
        ip.isNotEmpty() -> ip
        else -> port
    }
    val permissionsPending = model.accVisible || model.notifListenerVisible || model.notifPermVisible
    MdPage(padding) {
        item {
            MdStatusCard(
                running = model.serverRunning,
                description = ipPort,
                onClick = actions.onOpenLogs,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
        // 互联（手表连接）：状态 + 设备名，点按刷新（与 miuix 状态页信息一致）
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().clickable(onClick = actions.onRefresh),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_card_link),
                        contentDescription = null,
                        tint = mdToneColor(model.interconnectTone),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = model.interconnectText,
                            style = MaterialTheme.typography.titleMedium,
                            color = mdToneColor(model.interconnectTone),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = model.interconnectDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MdStatCard(
                    title = "权限管理",
                    value = if (permissionsPending) "去授予" else "已授权",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = actions.onOpenPermissions,
                )
                MdStatCard(
                    title = "插件数量",
                    value = model.plugins.size.toString(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onTabSelected(3) },
                )
            }
        }
        item {
            MdSegmentedColumn(
                title = "服务器信息",
                contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp),
            ) {
                item { MdBaseWidget(title = "IP 地址", description = ip) }
                item { MdBaseWidget(title = "端口", description = port) }
                item { MdBaseWidget(title = "服务器版本", description = model.serverVersion) }
            }
        }
    }
}

@Composable
private fun DevicesTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    MdPage(padding) {
        // 配对请求（弹窗被划掉时的兜底入口：允许 / 拒绝都在这）
        if (model.pairs.isNotEmpty()) {
            item {
                MdSegmentedColumn(title = "配对请求") {
                    model.pairs.forEach { pair ->
                        item {
                            MdBaseWidget(
                                title = pair.deviceName.ifBlank { pair.deviceId.take(16) },
                                description = "请求配对 · ${pair.deviceId.take(16)}...",
                                trailing = {
                                    Row {
                                        MdRowIconButton(R.drawable.ic_check, "允许") {
                                            actions.onAcceptPair(pair)
                                        }
                                        MdRowIconButton(R.drawable.ic_close, "拒绝") {
                                            actions.onRejectPair(pair)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            MdSegmentedColumn(title = "已配对设备") {
                if (model.devices.isEmpty()) {
                    item { MdEmptyItem("暂无配对设备") }
                } else {
                    model.devices.forEach { device ->
                        item {
                            MdBaseWidget(
                                title = device.deviceName.ifBlank { device.deviceId.take(12) },
                                description = device.deviceId,
                                trailing = {
                                    MdRowIconButton(R.drawable.ic_delete, "删除") {
                                        actions.onDeleteDevice(device)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    MdPage(padding) {
        item {
            MdSegmentedColumn(title = "快捷命令") {
                if (model.commands.isEmpty()) {
                    item { MdEmptyItem("暂无快捷命令，点右下角 + 新建") }
                } else {
                    model.commands.forEachIndexed { i, command ->
                        item {
                            MdBaseWidget(
                                title = command.name.ifBlank { "命令 ${i + 1}" },
                                description = command.command,
                                trailing = {
                                    Row {
                                        MdRowIconButton(R.drawable.ic_edit, "编辑") {
                                            actions.onEditCommand(i)
                                        }
                                        MdRowIconButton(R.drawable.ic_delete, "删除") {
                                            actions.onDeleteCommand(i)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    MdPage(padding) {
        item {
            MdSegmentedColumn(title = "插件") {
                // 插件市场：独立页（搜索/分类/一键装），放在最上面当入口
                item {
                    MdBaseWidget(
                        title = "插件市场",
                        description = "从 GitHub 索引仓浏览并安装插件",
                        onClick = { actions.onOpenMarket() },
                    )
                }
                item {
                    MdBaseWidget(
                        title = model.pluginSummary,
                        description = model.pluginMessage,
                        trailing = {
                            Row {
                                MdRowIconButton(R.drawable.ic_plugin_refresh, "刷新") {
                                    actions.onRefreshPlugins()
                                }
                                MdRowIconButton(R.drawable.ic_add, "安装") {
                                    actions.onInstallPlugin()
                                }
                            }
                        },
                    )
                }
                model.plugins.forEach { plugin ->
                    item {
                        MdBaseWidget(
                            title = plugin.name,
                            // 与 miuix 同一格式：副标题只留版本号，其余进详情
                            description = if (plugin.version.isBlank()) null
                            else "v${plugin.version}",
                            // 点卡片看详情（与 miuix 一致）；原先是个复用了刷新图标的
                            // 「详情」按钮，图标与上面的刷新撞脸、看不出是干嘛的
                            onClick = { actions.onPluginDetails(plugin) },
                            trailing = {
                                Row {
                                    // 有新版时，升级钮排在「打开界面」左边；版本不够时灰显
                                    val update = model.pluginUpdates.firstOrNull { it.entry.id == plugin.id }
                                    if (update != null) {
                                        MdRowIconButton(
                                            R.drawable.ic_upgrade,
                                            "更新到 v${update.entry.version}",
                                            enabled = update.compatible,
                                        ) { actions.onOpenUpdate(update) }
                                    }
                                    if (plugin.hasWeb) {
                                        MdRowIconButton(R.drawable.ic_plugin_ui, "打开界面") {
                                            actions.onPluginOpenWebUi(plugin)
                                        }
                                    }
                                    MdRowIconButton(R.drawable.ic_plugin_delete, "卸载") {
                                        actions.onPluginUninstall(plugin)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    MdPage(padding) {
        item {
            MdSegmentedColumn(title = "关于", contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    MdBaseWidget(
                        title = model.appName,
                        description = "版本 v${model.appVersion} · By Float11",
                    )
                }
                item {
                    MdBaseWidget(
                        title = "检查更新",
                        description = if (model.checkingAppUpdate) "正在检查更新..." else "点击检查新版本",
                        onClick = actions.onCheckAppUpdate,
                    )
                }
            }
        }
        item {
            MdSegmentedColumn(
                title = "服务",
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    MdBaseWidget(
                        title = "授权器",
                        description = model.authorizerStatus,
                        onClick = { model.authorizerPage = true },
                        trailing = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                item {
                    MdSwitchWidget(
                        title = "开机后自动启动服务",
                        description = "重启手机后自动启动服务",
                        checked = model.bootStart,
                        onCheckedChange = actions.onToggleBoot,
                    )
                }
            }
        }
        // 与 miuix 设置排序统一：主题风格放最下
        item {
            MdSegmentedColumn(
                title = "主题风格",
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    MdRadioWidget(
                        title = "Google Material 3",
                        description = "标准 Material Design 界面",
                        selected = !model.miuix,
                    ) { actions.onToggleMiuix(false) }
                }
                item {
                    MdRadioWidget(
                        title = "Miuix",
                        description = "类 HyperOS 风格界面",
                        selected = model.miuix,
                    ) { actions.onToggleMiuix(true) }
                }
                item {
                    MdSwitchWidget(
                        title = "启用悬浮底栏",
                        description = "在屏幕底部使用悬浮样式的导航栏",
                        checked = model.liquidGlass,
                        onCheckedChange = actions.onToggleGlass,
                    )
                }
            }
        }
    }
}

/**
 * 授权器二级页（从设置「授权器」行进入，整页覆盖、底栏收起）：
 * 单选用同「主题风格」的 [MdRadioWidget]，下面是状态行（未授权 Shizuku 才出「去授权」）。
 */
@Composable
private fun AuthorizerPage(padding: PaddingValues, model: MiuixModel, actions: MiuixActions) {
    MdPage(padding) {
        item {
            MdSegmentedColumn(title = "授权器", contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    MdRadioWidget(
                        title = "无",
                        description = "以应用自身权限执行",
                        selected = model.authorizer == "none",
                    ) { actions.onSetAuthorizer("none") }
                }
                item {
                    MdRadioWidget(
                        title = "Root",
                        description = "su 执行",
                        selected = model.authorizer == "root",
                    ) { actions.onSetAuthorizer("root") }
                }
                item {
                    MdRadioWidget(
                        title = "Shizuku",
                        description = "Shizuku 用户服务执行",
                        selected = model.authorizer == "shizuku",
                    ) { actions.onSetAuthorizer("shizuku") }
                }
            }
        }
        item {
            MdSegmentedColumn(title = "状态", contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    val grantButton: (@Composable () -> Unit)? =
                        if (model.authorizer == "shizuku" && model.authorizerNeedsGrant) {
                            {
                                Button(onClick = actions.onAuthorizeShizuku) { Text("去授权") }
                            }
                        } else {
                            null
                        }
                    MdBaseWidget(
                        title = "授权状态",
                        description = model.authorizerStatus,
                        onClick = actions.onRefreshAuthorizerStatus,
                        trailing = grantButton,
                    )
                }
            }
        }
    }
}

/** MD3 用的语义色（与 miuix 侧 toneColor 同一套资源色，保证两引擎观感一致）。 */
@Composable
private fun mdToneColor(tone: Tone): Color = when (tone) {
    Tone.Success -> Color(ContextCompat.getColor(LocalContext.current, R.color.md_theme_success))
    Tone.Warning -> Color(ContextCompat.getColor(LocalContext.current, R.color.md_theme_warning))
    Tone.Error -> Color(ContextCompat.getColor(LocalContext.current, R.color.md_theme_error))
    Tone.Outline -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 新建 / 编辑命令（M3 AlertDialog，对应 miuix 的 WindowDialog 版）。 */
@Composable
private fun MdCmdEditDialog(model: MiuixModel, actions: MiuixActions) {
    val dialog = model.cmdDialog ?: return
    var name by remember(dialog) { mutableStateOf(dialog.name) }
    var command by remember(dialog) { mutableStateOf(dialog.command) }
    AlertDialog(
        onDismissRequest = { model.cmdDialog = null },
        title = { Text(if (dialog.editIndex == null) "新建快捷命令" else "编辑快捷命令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = actions.onSaveCommand(dialog.editIndex, name.trim(), command.trim())
                if (ok) model.cmdDialog = null
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { model.cmdDialog = null }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
