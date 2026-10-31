package com.hydro.link.shared.ui.md

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import com.hydro.link.shared.ui.icons.IcAdd
import com.hydro.link.shared.ui.icons.IcArrowDropDown
import com.hydro.link.shared.ui.icons.IcCheck
import com.hydro.link.shared.ui.icons.IcClose
import com.hydro.link.shared.ui.icons.IcDelete
import com.hydro.link.shared.ui.icons.IcEdit
import com.hydro.link.shared.ui.icons.IcPluginRefresh
import com.hydro.link.shared.ui.icons.IcPluginUi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hydro.link.shared.AppActions
import com.hydro.link.shared.AppModel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton as M3TextButton
import com.hydro.link.shared.market.MarketCategory
import com.hydro.link.shared.market.MarketSources
import com.hydro.link.shared.market.marketFirstBase
import com.hydro.link.shared.ui.RemoteImage
import com.hydro.link.shared.ui.marketIconUrl
import com.hydro.link.shared.ui.CmdEditDialog
import com.hydro.link.shared.ui.icons.IcPluginRefresh
import com.hydro.link.shared.ui.PageEdge
import com.hydro.link.shared.ui.PageMaxWidth
import com.hydro.link.shared.ui.SideBarSpace
import com.hydro.link.shared.ui.HomeTab

/**
 * MD3 引擎的外壳：五个 Tab + 悬浮底栏。
 *
 * 版式与安卓端 `MaterialApp` 一致：每页按「分组小标题 + 圆角连续列表行」排（主页
 * 「服务器信息」那组就是这套），底栏用同一个 [FloatingBottomBar]，两个引擎观感统一。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MdShell(
    model: AppModel,
    actions: AppActions,
    tab: HomeTab,
    modifier: Modifier = Modifier,
    /** 内容区左侧为侧栏预留的宽度（宿主按侧栏实际宽度算好传进来）。 */
    contentInset: Dp = SideBarSpace,
) {
    CmdEditDialog(
        miuix = false,
        current = model.cmdEdit,
        onDismiss = { model.cmdEdit = null },
        onSave = actions.onSaveCommand,
    )
    // 大标题自己画（与 miuix 侧同一套做法：不用 Scaffold 的 topBar 槽）
    Scaffold(
        // 页面底色用 surface（更深），侧栏面板才浮得出来；原来两边都用
        // surfaceContainer，深色下完全糊在一起
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = contentInset)
        ) {
            val content = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            )
            // 与 miuix 侧同一套居中规则
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // 与 miuix 侧同一套居中写法（外层 Box 居中，不依赖父数据修饰符）
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    androidx.compose.material3.Text(
                        text = tab.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .widthIn(max = PageMaxWidth)
                            .fillMaxWidth()
                            .padding(start = PageEdge, end = PageEdge, top = PageEdge)
                            .padding(bottom = 12.dp),
                    )
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when (tab) {
                    HomeTab.Status -> MdStatusTab(content, model, actions)
                    HomeTab.Devices -> MdDevicesTab(content, model, actions)
                    HomeTab.Commands -> MdCommandsTab(content, model, actions)
                    HomeTab.Plugins -> MdPluginsTab(content, model, actions)
                    HomeTab.Settings -> MdSettingsTab(content, model, actions)
                    HomeTab.Market -> MdMarketTab(content, model, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun mdPage(content: PaddingValues, block: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = PageMaxWidth),
        contentPadding = content,
    ) { block() }
}

@Composable
private fun MdStatusTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    mdPage(padding) {
        item {
            MdSegmentedColumn(title = "状态") {
                item {
                    MdBaseWidget(
                        title = if (model.serverRunning) "正在运行" else "服务未运行",
                        description = model.address(),
                        onClick = actions.onOpenLogs,
                    )
                }
                item {
                    MdBaseWidget(
                        title = "设备数量",
                        description = model.devices.size.toString(),
                        onClick = { actions.onOpenTab(HomeTab.Devices) },
                    )
                }
                item {
                    MdBaseWidget(
                        title = "插件数量",
                        description = model.plugins.size.toString(),
                        onClick = { actions.onOpenTab(HomeTab.Plugins) },
                    )
                }
            }
        }
        item {
            MdSegmentedColumn(title = "概览", contentPadding = PaddingValues(top = 16.dp)) {
                item { MdBaseWidget(title = "设备数量", description = model.devices.size.toString()) }
                item { MdBaseWidget(title = "插件数量", description = model.plugins.size.toString()) }
            }
        }
        item {
            MdSegmentedColumn(title = "服务器信息", contentPadding = PaddingValues(top = 16.dp)) {
                item { MdBaseWidget(title = "IP 地址", description = model.ipText.ifBlank { "—" }) }
                item { MdBaseWidget(title = "端口", description = model.portText.ifBlank { "—" }) }
                item { MdBaseWidget(title = "服务器版本", description = model.serverVersion) }
            }
        }
    }
}

private fun AppModel.address(): String {
    val ip = ipText.trim()
    val port = portText.trim()
    return when {
        ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
        ip.isNotEmpty() -> ip
        else -> port
    }
}

@Composable
private fun MdDevicesTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    mdPage(padding) {
        if (model.pairs.isNotEmpty()) {
            item {
                MdSegmentedColumn(title = "配对请求") {
                    model.pairs.forEach { pair ->
                        item {
                            MdBaseWidget(
                                title = pair.deviceName.ifEmpty { "未知设备" },
                                description = pair.deviceId,
                                onClick = { actions.onPromptPair(pair) },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        MdRowIconButton(IcCheck, "允许") {
                                            actions.onAcceptPair(pair)
                                        }
                                        MdRowIconButton(IcClose, "拒绝") {
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
            MdSegmentedColumn(
                title = "已配对设备",
                contentPadding = PaddingValues(top = 16.dp),
            ) {
                if (model.devices.isEmpty()) {
                    item { MdEmptyItem("暂无配对设备") }
                } else {
                    model.devices.forEach { device ->
                        item {
                            MdBaseWidget(
                                title = device.deviceName.ifEmpty { "未知设备" },
                                description = device.deviceId,
                                trailing = {
                                    MdRowIconButton(IcDelete, "删除") {
                                        actions.onDeleteDevice(device.deviceId)
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
private fun MdCommandsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    mdPage(padding) {
        item {
            MdSegmentedColumn(title = "快捷命令") {
                if (model.commands.isEmpty()) {
                    item { MdEmptyItem("暂无快捷命令") }
                } else {
                    model.commands.forEachIndexed { index, command ->
                        item {
                            MdBaseWidget(
                                title = command.name.ifEmpty { "命令 ${index + 1}" },
                                description = command.command,
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        MdRowIconButton(IcEdit, "编辑") {
                                            actions.onEditCommand(index)
                                        }
                                        MdRowIconButton(IcDelete, "删除") {
                                            actions.onDeleteCommand(index)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MdBaseWidget(
                        title = "新建快捷命令",
                        description = "添加一条可一键执行的命令",
                        icon = { androidx.compose.material3.Icon(IcAdd, contentDescription = null) },
                        onClick = actions.onAddCommand,
                    )
                }
            }
        }
    }
}

@Composable
private fun MdPluginsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    mdPage(padding) {
        item {
            MdSegmentedColumn(title = "插件") {
                item {
                    MdBaseWidget(
                        title = "插件市场",
                        description = "从 GitHub 索引仓浏览并安装插件",
                        onClick = actions.onOpenMarket,
                    )
                }
                item {
                    MdBaseWidget(
                        title = model.pluginSummary.ifBlank { "已安装 0 个插件" },
                        description = model.pluginMessage,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MdRowIconButton(IcPluginRefresh, "刷新") { actions.onRefreshPlugins() }
                                MdRowIconButton(IcAdd, "安装") { actions.onInstallPlugin() }
                            }
                        },
                    )
                }
                model.plugins.forEach { row ->
                    item {
                        MdBaseWidget(
                            title = row.name,
                            description = if (row.version.isBlank()) null else "v${row.version}",
                            onClick = { actions.onPluginDetails(row) },
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (row.hasWeb) {
                                        MdRowIconButton(IcPluginUi, "界面") {
                                            actions.onPluginOpenWebUi(row)
                                        }
                                    }
                                    MdRowIconButton(IcDelete, "卸载") {
                                        actions.onRequestUninstall(row)
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
private fun MdMarketTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    val entries = model.marketVisible
    mdPage(padding) {
        item {
            MdSearchField(
                value = model.marketQuery,
                onValueChange = actions.onMarketSearch,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MarketCategory.entries.forEach { category ->
                    FilterChip(
                        selected = model.marketCategory == category,
                        onClick = { actions.onMarketCategory(category) },
                        label = { androidx.compose.material3.Text(category.label) },
                    )
                }
                MdRowIconButton(IcPluginRefresh, "刷新市场") { actions.onMarketRefresh() }
            }
        }
        item {
            MdSourcePicker(
                current = model.marketSource,
                onSelect = actions.onMarketSourceChange,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }
        item {
            when {
                model.market.loading -> MdEmptyItem("正在加载插件市场…")
                entries.isEmpty() -> MdEmptyItem(model.market.message ?: "插件市场里还没有插件")
                else -> MdSegmentedColumn {
                    entries.forEach { entry ->
                        item {
                            MdBaseWidget(
                                title = entry.name,
                                description = buildString {
                                    append("v").append(entry.version)
                                    append(" · ").append(entry.author.ifBlank { "—" })
                                    append(" · ").append(MarketCategory.of(entry.category).label)
                                    if (entry.permissions.isNotEmpty()) {
                                        append(" · ").append(entry.permissions.joinToString(" "))
                                    }
                                    if (entry.description.isNotBlank()) {
                                        append('\n').append(entry.description)
                                    }
                                },
                                descriptionMaxLines = 3,
                                icon = {
                                    RemoteImage(
                                        url = marketIconUrl(
                                            model.market.baseHint
                                                ?: marketFirstBase(model.marketSource),
                                            entry.icon,
                                        ),
                                        size = 40.dp,
                                    )
                                },
                                onClick = { actions.onMarketDetails(entry) },
                                trailing = {
                                    val busy = model.market.installing == entry.id
                                    val installed = entry.id in model.installedPluginIds
                                    M3TextButton(
                                        enabled = !installed && !busy,
                                        onClick = { actions.onMarketInstall(entry) },
                                    ) {
                                        androidx.compose.material3.Text(
                                            when {
                                                busy -> "安装中"
                                                installed -> "已安装"
                                                else -> "安装"
                                            }
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 镜像源选择（MD3 侧）：规范的 MD3 圆角卡片，点击弹出下拉菜单（无冗余 summary，与 Miuix 保持一致）。 */
@Composable
private fun MdSourcePicker(
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = MarketSources.entries.firstOrNull { it.key == current } ?: MarketSources.GhProxy

    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "镜像源",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(12.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = selected.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = IcArrowDropDown,
                            contentDescription = "选择镜像源",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MarketSources.entries.forEach { source ->
                val isSelected = source.key == selected.key
                androidx.compose.material3.DropdownMenuItem(
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = IcCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    text = {
                        Text(
                            text = source.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(source.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun MdSettingsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    mdPage(padding) {
        item {
            MdSegmentedColumn(title = "关于") {
                item {
                    MdBaseWidget(
                        title = model.appName,
                        description = "版本 v${model.appVersion} · By Float11",
                    )
                }
                item {
                    MdBaseWidget(
                        title = "检查更新",
                        description = model.updateNotice ?: if (model.checkingAppUpdate) "正在检查更新..." else "点击检查新版本",
                        onClick = actions.onCheckAppUpdate,
                    )
                }
            }
        }
        item {
            MdSegmentedColumn(title = "服务", contentPadding = PaddingValues(top = 16.dp)) {
                item {
                    MdBaseWidget(
                        title = "端口",
                        description = null,
                        trailing = {
                            MdPortField(
                                port = model.port,
                                onPortChange = actions.onPortChange,
                            )
                        },
                    )
                }
                item {
                    MdSwitchWidget(
                        title = "接受所有设备",
                        description = "开启后任何设备配对都会被自动信任",
                        checked = model.acceptAll,
                        onCheckedChange = actions.onAcceptAllChange,
                    )
                }
                item {
                    MdSwitchWidget(
                        title = "最小化到系统托盘",
                        description = "关闭主窗口时常驻后台，保持手表连接与插件运行",
                        checked = model.trayEnabled,
                        onCheckedChange = actions.onToggleTray,
                    )
                }
            }
        }
        // 主题风格放最下（与安卓端排序一致）
        item {
            MdSegmentedColumn(title = "主题风格", contentPadding = PaddingValues(top = 16.dp)) {
                item {
                    MdRadioWidget(
                        title = "Google Material 3",
                        description = "标准 Material Design 界面",
                        selected = !model.miuix,
                        onClick = { actions.onToggleMiuix(false) },
                    )
                }
                item {
                    MdRadioWidget(
                        title = "Miuix",
                        description = "类 HyperOS 风格界面",
                        selected = model.miuix,
                        onClick = { actions.onToggleMiuix(true) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
