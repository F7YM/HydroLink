package com.hydro.link.server.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hydro.link.server.R
import com.hydro.link.server.ui.material.md3BlurBar
import com.hydro.link.server.ui.miuix.MiuixActions
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.hydro.link.server.ui.miuix.MiuixModel

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
        val index = selectedIndex().coerceIn(0, 4)
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                LargeFlexibleTopAppBar(
                    modifier = Modifier.md3BlurBar(backdrop),
                    title = {
                        Text(
                            text = tabLabels.getOrNull(index)?.toString() ?: "HydroLink",
                            modifier = Modifier.padding(start = 12.dp),
                        )
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
                // 悬浮胶囊由 GlassTabHost 覆盖渲染；只有不开悬浮时才用 ShortNavigationBar
                if (!floatingBar) {
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
            },
            floatingActionButton = {
                if (index == 2) {
                    FloatingActionButton(onClick = actions.onAddCommand) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "新建命令")
                    }
                }
            },
        ) { padding ->
            CompositionLocalProvider(LocalMdExtraBottom provides if (floatingBar) 96.dp else 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    when (index) {
                        0 -> HomeTab(padding, model, actions, onTabSelected)
                        1 -> DevicesTab(padding, model, actions)
                        2 -> CommandsTab(padding, model, actions)
                        3 -> PluginsTab(padding, model, actions)
                        else -> SettingsTab(padding, model, actions)
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
                            description = buildString {
                                append(plugin.author)
                                append(" · ")
                                append(plugin.version)
                                append(" · ")
                                append(plugin.runtime)
                                if (plugin.workerOnline) append(" · 在线")
                            },
                            trailing = {
                                Row {
                                    if (plugin.hasWeb) {
                                        MdRowIconButton(R.drawable.ic_plugin_ui, "打开界面") {
                                            actions.onPluginOpenWebUi(plugin)
                                        }
                                    }
                                    MdRowIconButton(R.drawable.ic_plugin_refresh, "详情") {
                                        actions.onPluginDetails(plugin)
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
                item { MdBaseWidget(title = model.appName, description = "By Float11") }
            }
        }
        item {
            MdSegmentedColumn(
                title = "服务",
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    MdSwitchWidget(
                        title = "使用 root 执行",
                        description = model.rootStatus,
                        checked = model.useRoot,
                        onCheckedChange = actions.onToggleRoot,
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
