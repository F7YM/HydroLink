package com.hydro.link.shared.ui.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import com.hydro.link.shared.ui.RemoteImage
import com.hydro.link.shared.ui.icons.IcAdd
import com.hydro.link.shared.ui.marketIconUrl
import com.hydro.link.shared.ui.icons.IcCheck
import com.hydro.link.shared.ui.icons.IcCheckCircleOutline
import com.hydro.link.shared.ui.icons.IcClose
import com.hydro.link.shared.ui.icons.IcDelete
import com.hydro.link.shared.ui.icons.IcEdit
import com.hydro.link.shared.ui.icons.IcErrorOutline
import com.hydro.link.shared.ui.icons.IcPluginRefresh
import com.hydro.link.shared.ui.icons.IcPluginUi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.AppActions
import com.hydro.link.shared.AppModel
import com.hydro.link.shared.model.Tone
import com.hydro.link.shared.ui.HomeTab
import com.hydro.link.shared.market.MarketCategory
import com.hydro.link.shared.market.MarketSources
import com.hydro.link.shared.market.marketFirstBase
import com.hydro.link.shared.ui.CmdEditDialog
import com.hydro.link.shared.ui.icons.IcPluginRefresh
import com.hydro.link.shared.ui.PageEdge
import com.hydro.link.shared.ui.PageMaxWidth
import com.hydro.link.shared.ui.SideBarSpace
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 引擎的外壳：五个 Tab + 悬浮底栏。
 *
 * 文案与版式照安卓端（MiuixApp）抄：状态页是「运行状态 + 互联」两张方卡 + 「设备/插件」
 * 计数卡 + 服务器信息；设备页先配对请求再已配对设备；命令页快捷命令；插件页顶部是插件市场
 * 入口；设置页按 关于 / 服务 / 主题风格 分组。
 */
@Composable
fun MiuixShell(
    model: AppModel,
    actions: AppActions,
    tab: HomeTab,
    modifier: Modifier = Modifier,
    /** 内容区左侧为侧栏预留的宽度（宿主按侧栏实际宽度算好传进来）。 */
    contentInset: Dp = SideBarSpace,
) {
    // 新建/编辑命令弹窗（WindowDialog 是窗口级弹窗，放在 Scaffold 外面）
    CmdEditDialog(
        miuix = true,
        current = model.cmdEdit,
        onDismiss = { model.cmdEdit = null },
        onSave = actions.onSaveCommand,
    )
    // 页面的「大标题」自己画，不用 Scaffold 的 topBar 槽。
    // 那个槽在桌面端不渲染内容（往里塞红色大字也看不见，整栏空白），大标题折叠那套
    // scrollBehavior 一起撤掉——不跟组件较劲，标题能看见才是第一位的。
    Scaffold(containerColor = MiuixTheme.colorScheme.surface, modifier = modifier) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = contentInset)
        ) {
            val content = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            )
            Column(Modifier.fillMaxSize()) {
                // 标题居中：外层 Box 铺满整宽并居中内容。不用 Column 的 align /
                // horizontalAlignment——miuix 的 Text 会把 modifier 包一层，父数据修饰符
                // 在它上面不生效（试过，居中没反应）。
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = tab.title,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .widthIn(max = PageMaxWidth)
                            .fillMaxWidth()
                            .padding(start = PageEdge, end = PageEdge, top = PageEdge)
                            .padding(bottom = 12.dp),
                    )
                }
                // 这一层铺满整宽并把内容居中：列表本身有 widthIn(max) 上限，
                // 不居中的话窗口一拉宽内容就贴左边（Column 的 horizontalAlignment 管不到它）
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when (tab) {
                        HomeTab.Status -> StatusTab(content, model, actions)
                        HomeTab.Devices -> DevicesTab(content, model, actions)
                        HomeTab.Commands -> CommandsTab(content, model, actions)
                        HomeTab.Plugins -> PluginsTab(content, model, actions)
                        HomeTab.Settings -> SettingsTab(content, model, actions)
                        HomeTab.Market -> MarketTab(content, model, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun page(content: PaddingValues, block: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = PageMaxWidth)
            // 滚动质感 + 把滚动量交给大标题折叠（照安卓端 pageScroll()）
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = content,
    ) { block() }
}

/**
 * 内容区最大宽度。
 *
 * 手机上整屏就是内容宽度，桌面上不限制的话一行会被拉到上千 dp：方卡变成巨型色块、
 * 列表行长到横穿屏幕，都没法看。限宽居中是桌面版的常规做法。
 */

/** 小标题到下方卡片的间距（覆盖 miuix 自带留白用，标题不再贴卡片）。 */
private val TitleGap = 10.dp

/** 状态方卡的高度（桌面版不用 1:1 —— 宽屏下正方形会变成巨大的色块）。 */
private val StatCardHeight = 232.dp

/**
 * 同一行并列卡片的分组容器：照安卓端 `sectionCard()` —— **只有 12dp 边距和 12dp 间距，
 * 外面没有卡**。之前我在外面又套了一层 Card，方卡就变成「卡中卡」，两块并列的卡之间
 * 露出一条连接的背景色，看起来像被框在一起。
 */
private fun LazyListScope.sectionRow(block: @Composable RowScope.() -> Unit) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { block() }
    }
}

/** 普通卡片的分组容器：左右边距与分组小标题一致（见 [SmallTitleBox]）。 */
private fun LazyListScope.card(modifier: Modifier = Modifier, block: @Composable () -> Unit) {
    item {
        Box(modifier = modifier.padding(horizontal = PageEdge)) {
            Card(modifier = Modifier.fillMaxWidth()) { block() }
        }
    }
}

// ---------------------------------------------------------------- 状态 Tab

@Composable
private fun StatusTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    page(padding) {
        // 运行状态在左边，设备数量 / 插件数量在右边上下并列（点数量卡跳对应页）。
        // 桌面没有 XMS 互联（那是安卓端 XMS Wearable SDK 的事），所以右边是两张计数卡。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StatCardHeight)
                    .padding(horizontal = PageEdge)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RunningCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    running = model.serverRunning,
                    title = if (model.serverRunning) "正在运行" else "服务未运行",
                    summary = model.address(),
                    onClick = actions.onOpenLogs,
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        title = "设备数量",
                        value = model.devices.size.toString(),
                        onClick = { actions.onOpenTab(HomeTab.Devices) },
                    )
                    StatCard(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        title = "插件数量",
                        value = model.plugins.size.toString(),
                        onClick = { actions.onOpenTab(HomeTab.Plugins) },
                    )
                }
            }
        }
        card {
            Column {
                BasicComponent(title = "IP 地址", summary = model.ipText.ifBlank { "—" })
                BasicComponent(title = "服务器版本", summary = model.serverVersion)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
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
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Outline -> MiuixTheme.colorScheme.onSurface
    Tone.Warning -> Color(0xFFE5A50A)
    Tone.Success -> Color(0xFF36D167)
    Tone.Error -> Color(0xFFE5484D)
}

/** InstallerX 同款彩色状态卡：正方形、左上标题、右下大图标。 */
@Composable
private fun RunningCard(
    modifier: Modifier,
    running: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(
            color = if (running) Color(0xFF1A3825) else Color(0xFF381A1A),
        ),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick,
    ) {
        // 角标对号：按安卓端的**比例**做，尺寸跟着卡高走（不是写死 dp）——
        // 安卓那张方卡约 180dp 高，图标 160dp（0.89×）、右下偏移 (38, 48)（0.21/0.27×）。
        // 这样圆圈永远被卡片右下角裁掉一块，但对号主体（圆心那部分）留在卡内不被裁。
        var cardHeight by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { cardHeight = it.height },
        ) {
            if (cardHeight > 0) {
                val markSize = (cardHeight * 0.89f).dp
                Icon(
                    imageVector = if (running) IcCheckCircleOutline else IcErrorOutline,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (cardHeight * 0.21f).dp, y = (cardHeight * 0.27f).dp)
                        .size(markSize),
                    tint = if (running) Color(0xFF36D167) else Color(0xFFD13636),
                )
            }
            Column(Modifier.padding(16.dp)) {
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

/** 计数/状态方卡（标题在上、大字在下，整卡可点）。 */
@Composable
private fun StatCard(
    modifier: Modifier,
    title: String,
    value: String,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- 设备 Tab

@Composable
private fun DevicesTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    page(padding) {
        if (model.pairs.isNotEmpty()) {
            item { SmallTitleBox("配对请求") }
            card {
                Column {
                    model.pairs.forEach { pair ->
                        ListRow(
                            title = pair.deviceName.ifEmpty { "未知设备" },
                            summary = pair.deviceId,
                            onClick = { actions.onPromptPair(pair) },
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RowAction(IcCheck, "允许") { actions.onAcceptPair(pair) }
                                    RowAction(IcClose, "拒绝") { actions.onRejectPair(pair) }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { SmallTitleBox("已配对设备", top = 8.dp) }
        if (model.devices.isEmpty()) {
            item {
                Text(
                    text = "暂无配对设备",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            card {
                Column {
                    model.devices.forEach { device ->
                        ListRow(
                            title = device.deviceName.ifEmpty { "未知设备" },
                            summary = device.deviceId,
                            actions = {
                                RowAction(IcDelete, "删除") {
                                    actions.onDeleteDevice(device.deviceId)
                                }
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------- 命令 Tab

@Composable
private fun CommandsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    page(padding) {
        item {
            Row(
                // 只留右边距：小标题自己已经带 PageEdge 的左缩进，这里再叠 20dp 会和
                // 其它页面的标题/卡片左缩进对不上
                modifier = Modifier.fillMaxWidth().padding(end = PageEdge).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { SmallTitleAligned("快捷命令") }
                RowAction(IcAdd, "新建命令") { actions.onAddCommand() }
            }
        }
        if (model.commands.isEmpty()) {
            item {
                Text(
                    text = "暂无快捷命令",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            card {
                Column {
                    model.commands.forEachIndexed { index, command ->
                        ListRow(
                            title = command.name.ifEmpty { "命令 ${index + 1}" },
                            summary = command.command,
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RowAction(IcEdit, "编辑") { actions.onEditCommand(index) }
                                    RowAction(IcDelete, "删除") { actions.onDeleteCommand(index) }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------- 插件 Tab

@Composable
private fun PluginsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    page(padding) {
        item { SmallTitleBox("插件") }
        // 插件市场入口（与安卓端一致：最上面一张卡）
        card {
            BasicComponent(
                title = "插件市场",
                summary = "从 GitHub 索引仓浏览并安装插件",
                onClick = actions.onOpenMarket,
            )
        }
        card(Modifier.padding(top = 8.dp)) {
            Column {
                BasicComponent(
                    title = model.pluginSummary.ifBlank { "已安装 0 个插件" },
                    summary = model.pluginMessage,
                    endActions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RowAction(IcPluginRefresh, "刷新") { actions.onRefreshPlugins() }
                            RowAction(IcAdd, "安装") { actions.onInstallPlugin() }
                        }
                    },
                )
                model.plugins.forEach { row ->
                    BasicComponent(
                        title = row.name,
                        summary = if (row.version.isBlank()) null else "v${row.version}",
                        onClick = { actions.onPluginDetails(row) },
                        endActions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 只有带 WebUI（web/index.html）的插件才显示「界面」按钮
                                if (row.hasWeb) {
                                    RowAction(IcPluginUi, "界面") {
                                        actions.onPluginOpenWebUi(row)
                                    }
                                }
                                RowAction(IcDelete, "卸载") {
                                    actions.onRequestUninstall(row)
                                }
                            }
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------- 市场 Tab

@Composable
private fun MarketTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    val entries = model.marketVisible
    page(padding) {
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = PageEdge).padding(bottom = 10.dp)) {
                TextField(
                    value = TextFieldValue(model.marketQuery),
                    onValueChange = { actions.onMarketSearch(it.text) },
                    label = "搜索插件",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            // 分类 chip 在左、刷新钮在最右；刷新钮比 chip 大一圈（chip 高 ~32dp，按钮 40dp），
            // 不然两个尺寸并排看着不匹配
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageEdge).padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MarketCategory.entries.forEach { category ->
                    TextButton(
                        text = category.label,
                        onClick = { actions.onMarketCategory(category) },
                        colors = if (model.marketCategory == category) {
                            MiuixButtonDefaults.textButtonColorsPrimary()
                        } else {
                            MiuixButtonDefaults.textButtonColors()
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = actions.onMarketRefresh,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                ) {
                    Icon(
                        imageVector = IcPluginRefresh,
                        contentDescription = "刷新市场",
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
            }
        }
        item {
            // 镜像源：WindowSpinnerPreference 是**给放在 Card 里**的组件（设置页的 UI 引擎
            // 就是这么用的）。裸放在 Box 里会没背景、悬停高亮还是方角。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageEdge)
                    .padding(bottom = 10.dp)
                    .clipToBounds(),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    WindowSpinnerPreference(
                        title = "镜像源",
                        items = MarketSources.entries.map { source ->
                            DropdownItem(text = source.label)
                        },
                        selectedIndex = MarketSources.entries
                            .indexOfFirst { it.key == model.marketSource }
                            .coerceAtLeast(0),
                        onSelectedIndexChange = {
                            actions.onMarketSourceChange(MarketSources.entries[it].key)
                        },
                    )
                }
            }
        }
        if (model.market.loading) {
            item {
                Text(
                    text = "正在加载插件市场…",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else if (entries.isEmpty()) {
            item {
                Text(
                    // 有失败原因就显示原因（下方不再重复一遍）
                    text = model.market.message ?: "插件市场里还没有插件",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            // 列表行照安卓端 MarketActivity：图标 + 名称/版本作者 + 安装钮，下面接简介与标签。
            // 这里没用 BasicComponent：它没有"标题槽塞图标"的位置，自绘一行反而与安卓端一致。
            val base = model.market.baseHint ?: marketFirstBase(model.marketSource)
            entries.forEach { entry ->
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 4.dp)) {
                        MiuixMarketRow(
                            entry = entry,
                            base = base,
                            busy = model.market.installing == entry.id,
                            installed = entry.id in model.installedPluginIds,
                            onOpen = { actions.onMarketDetails(entry) },
                            onInstall = { actions.onMarketInstall(entry) },
                        )
                    }
                }
            }
        }
        model.market.message?.takeIf { entries.isNotEmpty() }?.let { message ->
            item {
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 市场列表的一行（miuix）：图标 + 名称/版本·作者 + 安装钮 + 简介 + 分类/权限标签。 */
@Composable
private fun MiuixMarketRow(
    entry: com.hydro.link.shared.market.MarketEntry,
    base: String,
    busy: Boolean,
    installed: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp),
        onClick = onOpen,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = marketIconUrl(base, entry.icon),
                    size = 40.dp,
                    placeholderColor = MiuixTheme.colorScheme.secondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "v${entry.version} · ${entry.author.ifBlank { "—" }}",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = when {
                        busy -> "安装中"
                        installed -> "已安装"
                        else -> "安装"
                    },
                    enabled = !installed && !busy,
                    onClick = onInstall,
                    // 已装/安装中的按钮不再用主题色，否则看着还能点
                    colors = if (installed || busy) {
                        MiuixButtonDefaults.textButtonColors()
                    } else {
                        MiuixButtonDefaults.textButtonColorsPrimary()
                    },
                )
            }
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            if (entry.category.isNotBlank() || entry.permissions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = MarketCategory.of(entry.category).label,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    entry.permissions.forEach { permission ->
                        Text(
                            text = permission,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 设置 Tab

@Composable
private fun SettingsTab(padding: PaddingValues, model: AppModel, actions: AppActions) {
    page(padding) {
        item { SmallTitleBox("关于") }
        card {
            Column {
                BasicComponent(
                    title = model.appName,
                    summary = "版本 v${model.appVersion} · By Float11",
                )
                BasicComponent(
                    title = "检查更新",
                    summary = model.updateNotice ?: if (model.checkingAppUpdate) "正在检查更新..." else "点击检查新版本",
                    onClick = actions.onCheckAppUpdate,
                )
            }
        }

        item { SmallTitleBox("服务", top = 8.dp) }
        card {
            Column {
                // 端口：可编辑的数字输入框（BasicComponent 只能显示文本，改不了）
                BasicComponent(
                    title = "端口",
                    summary = null,
                    endActions = {
                        MiuixPortField(
                            port = model.port,
                            onPortChange = actions.onPortChange,
                        )
                    },
                )
                SwitchRow(
                    title = "接受所有设备",
                    summary = "开启后任何设备配对都会被自动信任",
                    checked = model.acceptAll,
                    onCheckedChange = actions.onAcceptAllChange,
                )
                SwitchRow(
                    title = "最小化到系统托盘",
                    summary = "关闭主窗口时常驻后台，保持手表连接与插件运行",
                    checked = model.trayEnabled,
                    onCheckedChange = actions.onToggleTray,
                )
            }
        }

        item { SmallTitleBox("UI 引擎", top = 8.dp) }
        card {
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
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * 端口输入框（miuix 侧）：同样只在**失焦**时提交，合法且与现值不同才回调，
 * 输入中间不会触发重启；提交后回显真实端口，输入中锁定背景轮询防止被覆盖。
 */
@Composable
private fun MiuixPortField(
    port: Int,
    onPortChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(TextFieldValue(port.toString())) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(port) {
        if (!isFocused) {
            value = TextFieldValue(port.toString())
        }
    }

    // 失焦时提交：合法且与现值不同才回调；随后回显真实端口——
    // 成功时 port 会变、LaunchedEffect 再刷新，失败时保持旧值。
    fun submit() {
        val parsed = value.text.toIntOrNull()
        if (parsed != null && parsed in 1..65535 && parsed != port) {
            onPortChange(parsed)
        }
        value = TextFieldValue(port.toString())
    }

    TextField(
        value = value,
        onValueChange = { next ->
            val digits = next.text.filter { it.isDigit() }.take(5)
            value = TextFieldValue(digits, next.selection)
        },
        modifier = modifier
            .width(120.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) submit()
            },
    )
}

/** 设置行（整行可点 + 尾部 Switch）：对齐安卓端 `SwitchRow` / InstallerX 的 MiuixSwitchWidget。 */
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
        endActions = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/** 列表行：照安卓端 MiuixApp 里那份私有 ListRow 抄（miuix 库本身没有这个组件）。 */
@Composable
private fun ListRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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

/**
 * 分组小标题（行内标题行的版本：右侧有操作按钮，所以不带顶部间距）。
 *
 * 竖向间距由 [TitleGap] 统一给，左右缩进与卡片同为 [PageEdge]——之前标题 20dp、卡片
 * 12dp，两边缩进不一样，一眼能看出不齐。
 */
@Composable
private fun SmallTitleAligned(text: String) {
    SmallTitle(
        text,
        Modifier.fillMaxWidth(),
        MiuixTheme.colorScheme.onBackgroundVariant,
        PaddingValues(start = PageEdge, end = PageEdge, top = 0.dp, bottom = TitleGap),
    )
}

@Composable
private fun SmallTitleBox(text: String, top: androidx.compose.ui.unit.Dp = 4.dp) {
    // padding 显式给成 PageEdge：SmallTitle 自带的缩进和卡片不是一回事，
    // 不显式指定的话小标题会比卡片多缩进一截（之前 20dp vs 12dp，肉眼能看出不齐）
    // miuix 0.9.4 的 SmallTitle 没导出参数名（kotlin.Metadata 里没有 d2 名字表），
    // 具名参数只有 text / modifier 能用，后面两个按位置传：
    // (text, modifier, color, padding)
    // 竖向要自己给：显式传 padding 会覆盖组件自带的上下留白，不给的话标题会贴着卡片。
    SmallTitle(
        text,
        Modifier.fillMaxWidth().padding(top = top),
        MiuixTheme.colorScheme.onBackgroundVariant,
        PaddingValues(
            start = PageEdge,
            end = PageEdge,
            top = 0.dp,
            bottom = TitleGap,
        ),
    )
}

/** 行尾圆底图标钮（与安卓端 `RowActionButton` 同款：secondaryContainer 圆底 + 20dp 图标）。 */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        minHeight = 36.dp,
        minWidth = 36.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}
