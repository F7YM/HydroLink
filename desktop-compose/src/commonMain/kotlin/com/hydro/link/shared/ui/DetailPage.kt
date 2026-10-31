package com.hydro.link.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydro.link.shared.DetailTarget
import com.hydro.link.shared.market.MarketCategory
import com.hydro.link.shared.market.MarketEntry
import com.hydro.link.shared.market.marketFirstBase
import com.hydro.link.shared.model.PluginRow
import com.hydro.link.shared.ui.icons.IcArrowBack
import com.hydro.link.shared.ui.md.MdBaseWidget
import com.hydro.link.shared.ui.md.MdNoteWidget
import com.hydro.link.shared.ui.md.MdSegmentedColumn
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 插件/市场条目详情页（**页内换页**，不是新窗口）。
 *
 * 版式照安卓端 `MarketDetailActivity`：
 * `返回行 → 头部（图标 + 名称/v·作者 + 右侧主按钮）→ 截图 → 介绍 → 更新日志 → 信息`。
 *
 * 三条硬约束：
 * · 返回行排在**内容区**里（内容区已给侧栏留了 [contentInset]），所以和悬浮侧栏不打架；
 * · 返回键底色取侧栏面板色（[panelColor]），两个悬浮物颜色必须一致；
 * · 换页要有进入动画，不然点一下像闪了一帧。
 */
@Composable
fun DetailPage(
    target: DetailTarget,
    miuix: Boolean,
    onBack: () -> Unit,
    onInstall: ((MarketEntry) -> Unit)?,
    onUninstall: (() -> Unit)?,
    contentInset: Dp,
    modifier: Modifier = Modifier,
    /** 侧栏面板色：返回键底色跟它统一。 */
    panelColor: Color,
    /** 没有自己的图标时用它兜底。 */
    fallbackIcon: ImageVector,
    /** 市场基址（截图/图标都是仓库相对路径，要拼上它）。 */
    marketBaseUrl: String,
) {
    val title = when (target) {
        is DetailTarget.Installed -> target.plugin.name
        is DetailTarget.MarketEntryItem -> target.entry.name
    }
    val base = marketBaseUrl.ifBlank { marketFirstBase("auto") }
    val ctx = DetailContext(target, title, base, fallbackIcon)

    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.slideInHorizontally(
            animationSpec = androidx.compose.animation.core.tween(220),
            // 只滑一点点（1/8 宽）：太长的位移在桌面大窗口上很晃
            initialOffsetX = { it / 8 },
        ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
        modifier = modifier,
    ) {
        if (miuix) {
            MiuixDetailBody(ctx, onBack, onInstall, onUninstall, contentInset, panelColor)
        } else {
            MdDetailBody(ctx, onBack, onInstall, onUninstall, contentInset, panelColor)
        }
    }
}

/** 详情页要用的全部输入（两个引擎共用一份，别各算一遍算歪）。 */
private data class DetailContext(
    val target: DetailTarget,
    val title: String,
    val base: String,
    val fallbackIcon: ImageVector,
) {
    /** 头部副标题：v版本 · 作者。 */
    val subtitle: String
        get() = when (target) {
            is DetailTarget.Installed -> "v" + target.plugin.version.ifBlank { "?" } +
                " · " + target.plugin.author.ifBlank { "—" }
            is DetailTarget.MarketEntryItem -> "v" + target.entry.version +
                " · " + target.entry.author.ifBlank { "—" }
        }

    /** 头部第三行的状态说明（没有就不显示）。 */
    val statusLine: String
        get() = when (target) {
            is DetailTarget.Installed -> ""
            is DetailTarget.MarketEntryItem -> if (target.installed) "已安装" else "未安装"
        }

    /** 图标 URL：已装用协议给的 asset 地址，市场条目用索引里的相对路径拼基址。 */
    val iconUrl: String
        get() = when (target) {
            is DetailTarget.Installed -> target.plugin.iconUrl
            is DetailTarget.MarketEntryItem -> marketIconUrl(base, target.entry.icon)
        }

    /** 截图（只有市场条目有）。 */
    val screenshots: List<String>
        get() = (target as? DetailTarget.MarketEntryItem)
            ?.entry?.screenshots?.map { marketIconUrl(base, it) }.orEmpty()

    /** 介绍正文（描述 + 关键词，安卓端也是拼成一段）。 */
    val intro: String
        get() {
            val entry = (target as? DetailTarget.MarketEntryItem)?.entry ?: return ""
            return buildString {
                append(entry.description)
                if (entry.keywords.isNotEmpty()) {
                    append("\n\n关键词：").append(entry.keywords.joinToString("、"))
                }
            }
        }

    val notes: String get() = (target as? DetailTarget.MarketEntryItem)?.entry?.notes.orEmpty()

    /** 信息区的键值对（两个引擎都只读这一份）。 */
    val fields: List<Pair<String, String>> get() = detailFields(target)

    /** 主操作按钮：市场条目才有（已装插件那边给的是「卸载」）。 */
    val actionLabel: String
        get() = (target as? DetailTarget.MarketEntryItem)
            ?.let { if (it.installed) "已安装" else "安装" } ?: "安装"

    val actionEnabled: Boolean
        get() = (target as? DetailTarget.MarketEntryItem)?.installed != true

    val marketEntry: MarketEntry?
        get() = (target as? DetailTarget.MarketEntryItem)?.entry
}

// ---------------------------------------------------------------- miuix

@Composable
private fun MiuixDetailBody(
    ctx: DetailContext,
    onBack: () -> Unit,
    onInstall: ((MarketEntry) -> Unit)?,
    onUninstall: (() -> Unit)?,
    contentInset: Dp,
    panelColor: Color,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        modifier = Modifier,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(start = contentInset)) {
            // 返回行：在内容区里（contentInset 已避开侧栏），顶距与侧栏第一行齐平
            Row(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = PageMaxWidth)
                    .padding(start = PageEdge, end = PageEdge, top = PageEdge, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    // 底色跟侧栏面板统一：两个悬浮物颜色一致才不像拼的
                    backgroundColor = panelColor,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = IcArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
                MiuixText(
                    text = ctx.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = PageMaxWidth),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item { MiuixDetailHeader(ctx, onInstall, onUninstall) }
                if (ctx.screenshots.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            ctx.screenshots.forEach { RemoteImage(url = it, size = 120.dp) }
                        }
                    }
                }
                if (ctx.intro.isNotBlank()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 6.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                MiuixNoteCard("介绍", ctx.intro)
                            }
                        }
                    }
                }
                if (ctx.notes.isNotBlank()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 6.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                MiuixNoteCard("更新日志", ctx.notes)
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 6.dp)) {
                        Card(Modifier.fillMaxWidth()) {
                            Column {
                                ctx.fields.forEach { (key, value) ->
                                    BasicComponent(title = key, summary = value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 图标 + 名称/副标题/状态 + 右侧主按钮（安卓端 HeaderRow 的同一行布局）。 */
@Composable
private fun MiuixDetailHeader(
    ctx: DetailContext,
    onInstall: ((MarketEntry) -> Unit)?,
    onUninstall: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageEdge, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailIcon(ctx)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            MiuixText(text = ctx.title, color = MiuixTheme.colorScheme.onSurface)
            MiuixText(
                text = ctx.subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (ctx.statusLine.isNotEmpty()) {
                MiuixText(
                    text = ctx.statusLine,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (ctx.target is DetailTarget.MarketEntryItem) {
            val entry = ctx.marketEntry
            if (entry != null && onInstall != null) {
                MiuixButton(
                    enabled = ctx.actionEnabled,
                    onClick = { onInstall(entry) },
                    colors = MiuixButtonDefaults.buttonColorsPrimary(),
                ) { MiuixText(text = ctx.actionLabel) }
            }
        }
        onUninstall?.let { uninstall ->
            TextButton(text = "卸载", onClick = uninstall)
        }
    }
}

@Composable
private fun MiuixNoteCard(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        MiuixText(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        MiuixText(text = text, color = MiuixTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------- MD3

@Composable
private fun MdDetailBody(
    ctx: DetailContext,
    onBack: () -> Unit,
    onInstall: ((MarketEntry) -> Unit)?,
    onUninstall: (() -> Unit)?,
    contentInset: Dp,
    panelColor: Color,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(start = contentInset)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = PageMaxWidth)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = panelColor,
                ) {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(IcArrowBack, contentDescription = "返回")
                    }
                }
                androidx.compose.material3.Text(
                    text = ctx.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = PageMaxWidth),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item { MdDetailHeader(ctx, onInstall, onUninstall) }
                if (ctx.screenshots.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            ctx.screenshots.forEach { RemoteImage(url = it, size = 120.dp) }
                        }
                    }
                }
                if (ctx.intro.isNotBlank()) {
                    item {
                        Column {
                            MdSegmentedColumn(title = "介绍") {
                                item { MdNoteWidget(title = "它做什么", text = ctx.intro) }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                if (ctx.notes.isNotBlank()) {
                    item {
                        Column {
                            MdSegmentedColumn(title = "更新日志") {
                                item { MdNoteWidget(title = "这一版改了什么", text = ctx.notes) }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                item {
                    MdSegmentedColumn(title = "信息") {
                        ctx.fields.forEach { (key, value) ->
                            item { MdBaseWidget(title = key, description = value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MdDetailHeader(
    ctx: DetailContext,
    onInstall: ((MarketEntry) -> Unit)?,
    onUninstall: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailIcon(ctx)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            androidx.compose.material3.Text(
                text = ctx.title,
                style = MaterialTheme.typography.titleMedium,
            )
            androidx.compose.material3.Text(
                text = ctx.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ctx.statusLine.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = ctx.statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (ctx.target is DetailTarget.MarketEntryItem) {
            val entry = ctx.marketEntry
            if (entry != null && onInstall != null) {
                androidx.compose.material3.Button(
                    enabled = ctx.actionEnabled,
                    onClick = { onInstall(entry) },
                ) { androidx.compose.material3.Text(ctx.actionLabel) }
            }
        }
        onUninstall?.let { uninstall ->
            androidx.compose.material3.TextButton(onClick = uninstall) {
                androidx.compose.material3.Text("卸载")
            }
        }
    }
}

// ---------------------------------------------------------------- 公共件

/** 头部图标：有地址就下图标（56dp，安卓端同尺寸），没有就用兜底矢量图。 */
@Composable
private fun DetailIcon(ctx: DetailContext) {
    if (ctx.iconUrl.isBlank()) {
        androidx.compose.material3.Icon(
            imageVector = ctx.fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
    } else {
        RemoteImage(url = ctx.iconUrl, size = 56.dp)
    }
}

/** 市场条目的图标/截图 URL（索引里是仓库相对路径，与安卓端 marketAssetUrl 同一套拼法）。 */
internal fun marketIconUrl(base: String, path: String): String =
    if (base.isEmpty() || path.isEmpty()) "" else base.trimEnd('/') + "/" + path.trimStart('/')

/** 信息区字段（两个引擎共用一份文案）。 */
private fun detailFields(target: DetailTarget): List<Pair<String, String>> = when (target) {
    is DetailTarget.Installed -> {
        val p: PluginRow = target.plugin
        listOf(
            "包名" to p.id,
            "作者" to p.author.ifBlank { "—" },
            "版本" to if (p.version.isBlank()) "—" else "v${p.version}",
            "运行时" to p.runtime.ifBlank { "—" },
            "应用项" to p.items.ifBlank { "无" },
            "权限" to if (p.permissions.isBlank()) "未申请权限" else p.permissions,
        ) + (p.error?.let { listOf("清单异常" to it) } ?: emptyList())
    }
    is DetailTarget.MarketEntryItem -> {
        val e = target.entry
        listOf(
            "包名" to e.id,
            "作者" to e.author.ifBlank { "—" },
            "版本" to "v${e.version}",
            "运行时" to e.runtime.ifBlank { "—" },
            "大小" to formatSize(e.size),
            "分类" to MarketCategory.of(e.category).label,
            "权限" to if (e.permissions.isEmpty()) "未申请权限" else e.permissions.joinToString(", "),
            "最低宿主" to e.minHydroLink.ifBlank { "—" },
            "状态" to if (target.installed) "已安装" else "未安装",
        )
    }
}

/**
 * 体积文案：小于 1MB 用 KB。
 *
 * 市场里的插件包真的很小（deskbuddy 才 12KB），之前统一按 MB 输出就成了 `0.00 MB`——
 * 数字没错但没人看得懂，所以小于 1MB 改用 KB。
 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}