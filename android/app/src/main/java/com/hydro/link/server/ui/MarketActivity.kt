package com.hydro.link.server.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hydro.link.server.BuildConfig
import com.hydro.link.server.R
import com.hydro.link.server.market.MarketCatalog
import com.hydro.link.server.market.MarketClient
import com.hydro.link.server.market.MarketIndex
import com.hydro.link.server.market.MarketIndexParser
import com.hydro.link.server.market.MarketRow
import com.hydro.link.server.market.MarketSources
import com.hydro.link.server.market.MarketStatus
import com.hydro.link.server.market.UrlFetcher
import com.hydro.link.server.ui.material.ExpressiveBackButton
import com.hydro.link.server.ui.material.MdBaseWidget
import com.hydro.link.server.ui.material.MdEmptyItem
import com.hydro.link.server.ui.material.MdRadioWidget
import com.hydro.link.server.ui.material.MdSegmentedColumn
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.ui.miuix.ConfirmDialog
import com.hydro.link.server.util.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

/**
 * 插件市场（独立 Activity，两个引擎共用一套逻辑与文案）。
 *
 * 数据来自 GitHub 索引仓 `F7YM/hydromarket-index`：`index.json` 与 `.hpk` 用
 * 「基址 + 仓库内相对路径」拼，所以能在直连 / gh-proxy / jsDelivr 之间切换；
 * 下载后必须核对 `sha256` 与 `size`，不过直接拒（不进确认弹窗）。安装走
 * [MarketInstaller]（下载 → 校验 → inspect → 确认弹窗 → install），与「插件更新」同一条链路。
 *
 * 刷新是**下拉刷新**（miuix 用它自己的下拉刷新动画，MD3 用 Material 的），页面里不再挂
 * 刷新按钮，也不再显示「N 个插件 / 离线缓存 / 基址」那一串状态字样——拉取失败用系统 Toast。
 *
 * **服务端不参与**：手机端自己出网拉 GitHub，没有新增任何 HTTP 路由。
 */
class MarketActivity : AppCompatActivity() {

    private val ui = mutableStateOf(MarketUi())
    private lateinit var client: MarketClient
    private var prevWindowDialog: ((String, String, String, String?, (() -> Unit)?, (() -> Unit)?, Boolean, Int, String?) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = DataManager.get(this).loadConfig()
        val miuix = cfg.miuix
        SystemBars.apply(this, miuix)
        client = MarketClient(cacheDir = File(cacheDir, "marketplace"), fetcher = UrlFetcher())
        ui.value = ui.value.copy(sourceSetting = cfg.marketSource)

        if (miuix) {
            // 确认弹窗借 EngineDialog 的 miuix 钩子（由本页的 WindowDialog 渲染）；
            // 记住上一个（MainActivity 的）并在销毁时还回去，别把人家的钩子吞了。
            prevWindowDialog = EngineDialog.window
            EngineDialog.window = { title, message, positive, negative, onNegative, onConfirm, positiveEnabled, countdownSeconds, dangerWarning ->
                ui.value = ui.value.copy(
                    dialog = ConfirmDialog(
                        title = title,
                        message = message,
                        positive = positive,
                        negative = negative,
                        onNegative = onNegative,
                        onConfirm = onConfirm,
                        positiveEnabled = positiveEnabled,
                        countdownSeconds = countdownSeconds,
                        dangerWarning = dangerWarning,
                    )
                )
                true
            }
        }

        setContent {
            val content: @Composable () -> Unit = {
                MarketScreen(
                    state = ui.value,
                    miuix = miuix,
                    onBack = { finish() },
                    onQuery = { q -> ui.value = ui.value.copy(query = q) },
                    onCategory = { c -> ui.value = ui.value.copy(category = c) },
                    onRefresh = ::refresh,
                    onInstall = ::install,
                    onOpenDetail = { row ->
                        // 详情页是独立 Activity：开/关用系统转场、返回走系统返回栈。
                        // 页内 NavHost 手写的滑入淡入手感与应用其他页不一致，不要再走那条路
                        startActivity(MarketDetailActivity.intent(this, row.entry, ui.value.base))
                    },
                    onPickSource = { ui.value = ui.value.copy(sourcePicker = true) },
                    onCloseSource = { ui.value = ui.value.copy(sourcePicker = false) },
                    onSource = ::setSource,
                    onDismissDialog = { ui.value = ui.value.copy(dialog = null) },
                )
            }
            // 关键：NavHost 必须拿到 ViewTree 上的 NavigationEventDispatcherOwner，
            // 系统返回键才会被 NavController 接管（pop 回列表）而不是直接 finish 掉
            // Activity。setContent 里的组合拿不到，就显式把 Activity 自己 provide 进去
            // —— MainActivity 当初也是这么绕的，别删。
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.provides(this)
            ) {
                if (miuix) {
                    MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
                } else {
                    HydroMaterialTheme { content() }
                }
            }
        }
        refresh()
    }

    override fun onDestroy() {
        if (prevWindowDialog != null) EngineDialog.window = prevWindowDialog
        prevWindowDialog = null
        super.onDestroy()
    }

    // ---- 数据 ----

    private fun refresh() {
        if (ui.value.loading) return
        ui.value = ui.value.copy(loading = true)
        val sources = MarketSources.bases(DataManager.get(this).loadConfig().marketSource)
        Thread {
            val result = client.fetch(sources)
            runOnUiThread {
                val rows = result.index?.let { index ->
                    MarketCatalog.rows(index, installedVersions(), BuildConfig.VERSION_NAME)
                }.orEmpty()
                ui.value = ui.value.copy(
                    loading = false,
                    index = result.index,
                    rows = rows,
                    base = result.base,
                    fromCache = result.fromCache,
                    error = result.error,
                )
                // 拉取失败（断网/全挂了）用系统 Toast 提示；页面里不再挂状态字样
                result.error?.let { MarketInstaller.toast(this, it) }
            }
        }.start()
    }

    /** 已装插件 id → 版本（市场只比版本）。 */
    private fun installedVersions(): Map<String, String> =
        PluginUi.server()?.hydroKit?.store?.list()
            ?.associate { it.id to it.version }
            .orEmpty()

    private fun setSource(setting: String) {
        val dm = DataManager.get(this)
        val cfg = dm.loadConfig()
        cfg.marketSource = setting
        dm.saveConfig(cfg)
        ui.value = ui.value.copy(sourcePicker = false, sourceSetting = setting)
        refresh()
    }

    private fun install(row: MarketRow) {
        MarketInstaller.install(
            activity = this,
            client = client,
            base = ui.value.base.ifEmpty { MarketSources.bases(ui.value.sourceSetting).first() },
            entry = row.entry,
            positiveLabel = MarketCatalog.actionLabel(row.status),
            onBusy = { id -> ui.value = ui.value.copy(busyId = id) },
            onDone = { message ->
                MarketInstaller.toast(this, message)
                refresh()
            },
        )
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MarketActivity::class.java)
    }
}

/** 一页市场的界面状态（两个引擎共用）。 */
private data class MarketUi(
    val loading: Boolean = false,
    val index: MarketIndex? = null,
    val rows: List<MarketRow> = emptyList(),
    val query: String = "",
    val category: String = MarketCatalog.CATEGORY_ALL,
    val base: String = "",
    val sourceSetting: String = MarketSources.AUTO,
    val fromCache: Boolean = false,
    val error: String? = null,
    val busyId: String = "",
    val sourcePicker: Boolean = false,
    val dialog: ConfirmDialog? = null,
)

// ---------------------------------------------------------------- 界面



/** 详情页与列表共用的信息行（标题 + 值）。 */

/** 一个文本弹窗的内容（两个引擎各画各的壳，内容共用）。 */
private data class TextDialogSpec(
    val title: String,
    val body: String,
    val positive: String,
    val positiveEnabled: Boolean = true,
    val onPositive: () -> Unit = {},
    val onDismiss: () -> Unit,
    val extra: (@Composable () -> Unit)? = null,
)

@Composable
private fun MarketScreen(
    state: MarketUi,
    miuix: Boolean,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (MarketRow) -> Unit,
    onOpenDetail: (MarketRow) -> Unit,
    onPickSource: () -> Unit,
    onCloseSource: () -> Unit,
    onSource: (String) -> Unit,
    onDismissDialog: () -> Unit,
) {
    val spec: TextDialogSpec? = if (state.sourcePicker) {
        TextDialogSpec(
            title = "市场来源",
            body = "",
            positive = "",
            onDismiss = onCloseSource,
            extra = {
                if (miuix) {
                    MiuixSourceOptions(state.sourceSetting, onSource)
                } else {
                    MdSourceOptions(state.sourceSetting, onSource)
                }
            },
        )
    } else {
        null
    }

    if (miuix) {
        MiuixMarketScreen(
            state, spec, onBack, onQuery, onCategory, onRefresh, onInstall, onOpenDetail, onPickSource,
        )
        MiuixConfirmHost(state.dialog, onDismissDialog)
    } else {
        MdMarketScreen(
            state, spec, onBack, onQuery, onCategory, onRefresh, onInstall, onOpenDetail, onPickSource,
        )
        MdConfirmHost(state.dialog, onDismissDialog)
    }

    if (miuix) {
        MiuixConfirmHost(state.dialog, onDismissDialog)
    } else {
        MdConfirmHost(state.dialog, onDismissDialog)
    }
}

private const val ROUTE_LIST = "list"
private const val ROUTE_DETAIL = "detail"
private const val ARG_ID = "id"

// ---------- miuix ----------

@Composable
private fun MiuixMarketScreen(
    state: MarketUi,
    spec: TextDialogSpec?,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (MarketRow) -> Unit,
    onOpenDetail: (MarketRow) -> Unit,
    onPickSource: () -> Unit,
) {
    val visible = MarketCatalog.filter(state.rows, state.query, state.category)
    val counts = MarketCatalog.counts(state.rows, state.query)
    MiuixScaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            MiuixTopAppBar(
                title = "插件市场",
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    MiuixIconButton(onClick = onPickSource) {
                        MiuixIcon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "市场来源",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        // 下拉刷新（miuix 自带动画）。**整个下拉容器要往下让出顶栏高度**：指示器画在容器
        // 顶边，不让开就会跑到顶栏底下被盖住。
        PullToRefresh(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()),
            refreshTexts = PULL_TEXTS,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            ) {
                item {
                    MiuixTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = "搜索插件",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MiuixCategoryChip(
                            "全部",
                            state.category == MarketCatalog.CATEGORY_ALL,
                            counts[MarketCatalog.CATEGORY_ALL] ?: 0,
                        ) { onCategory(MarketCatalog.CATEGORY_ALL) }
                        MarketIndexParser.CATEGORIES.forEach { c ->
                            MiuixCategoryChip(categoryLabel(c), state.category == c, counts[c] ?: 0) {
                                onCategory(c)
                            }
                        }
                    }
                }
                if (visible.isEmpty() && !state.loading) {
                    item {
                        MiuixText(
                            text = if (state.rows.isEmpty()) "市场里还没有插件" else "没有匹配的插件",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                visible.forEach { row ->
                    item {
                        MiuixCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable { onOpenDetail(row) },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RemoteImage(iconUrl(state, row), 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        MiuixText(
                                            text = row.entry.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                        MiuixText(
                                            text = "v${row.entry.version} · ${row.entry.author}",
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    MarketAction(state = state, row = row, miuix = true, onInstall = onInstall)
                                }
                                Spacer(Modifier.height(6.dp))
                                MiuixText(
                                    text = row.entry.description,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MiuixText(
                                        text = categoryLabel(row.entry.category),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    MiuixText(
                                        text = statusLabel(row),
                                        fontSize = 11.sp,
                                        color = MiuixStatusColor(row.status),
                                    )
                                    if (row.entry.permissions.isNotEmpty()) {
                                        MiuixText(
                                            text = row.entry.permissions.joinToString(" · "),
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    MiuixTextDialogHost(spec)
}

@Composable
private fun MiuixCategoryChip(label: String, selected: Boolean, count: Int, onClick: () -> Unit) {
    MiuixTextButton(
        text = "$label $count",
        onClick = onClick,
        colors = if (selected) {
            MiuixButtonDefaults.textButtonColorsPrimary()
        } else {
            MiuixButtonDefaults.textButtonColors()
        },
    )
}

// ---------- MD3 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdMarketScreen(
    state: MarketUi,
    spec: TextDialogSpec?,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (MarketRow) -> Unit,
    onOpenDetail: (MarketRow) -> Unit,
    onPickSource: () -> Unit,
) {
    val visible = MarketCatalog.filter(state.rows, state.query, state.category)
    val counts = MarketCatalog.counts(state.rows, state.query)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text("插件市场") },
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = onPickSource) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "市场来源",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        // 下拉刷新（Material 自带）。同样要让开顶栏，否则指示器被顶栏盖住
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = inner.calculateBottomPadding() + 24.dp,
                ),
            ) {
                // 列表一律走 MD 组件（与主页「服务器信息」同一套 MdSegmentedColumn +
                // MdBaseWidget），不再手搓行
                item {
                    MdSegmentedColumn {
                        item {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = onQuery,
                                label = { Text("搜索插件") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item {
                            // 分类标签要能左右滑：分类会随索引增长（现在是 3 个，
                            // 以后加），固定宽度排不下就得能滑
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.category == MarketCatalog.CATEGORY_ALL,
                                    onClick = { onCategory(MarketCatalog.CATEGORY_ALL) },
                                    label = { Text("全部 ${counts[MarketCatalog.CATEGORY_ALL] ?: 0}") },
                                )
                                MarketIndexParser.CATEGORIES.forEach { c ->
                                    FilterChip(
                                        selected = state.category == c,
                                        onClick = { onCategory(c) },
                                        label = { Text("${categoryLabel(c)} ${counts[c] ?: 0}") },
                                    )
                                }
                            }
                        }
                        if (visible.isEmpty() && !state.loading) {
                            item {
                                MdEmptyItem(
                                    if (state.rows.isEmpty()) "市场里还没有插件" else "没有匹配的插件"
                                )
                            }
                        }
                        visible.forEach { row ->
                            item {
                                MdBaseWidget(
                                    title = row.entry.name,
                                    description = buildString {
                                        append("v").append(row.entry.version)
                                        append(" · ").append(row.entry.author)
                                        append(" · ").append(categoryLabel(row.entry.category))
                                        if (row.entry.permissions.isNotEmpty()) {
                                            append(" · ").append(row.entry.permissions.joinToString(" "))
                                        }
                                        append('\n').append(row.entry.description)
                                    },
                                    descriptionMaxLines = 3,
                                    leading = {
                                        RemoteImage(marketAssetUrl(state.base, row.entry.icon), 40.dp)
                                    },
                                    onClick = { onOpenDetail(row) },
                                    trailing = {
                                        if (state.busyId == row.entry.id) {
                                            Text(
                                                text = "下载中…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else if (row.status != MarketStatus.INCOMPATIBLE) {
                                            TextButton(onClick = { onInstall(row) }) {
                                                Text(MarketCatalog.actionLabel(row.status))
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

    MdTextDialogHost(spec)
}

// ---------------------------------------------------------------- 公共小件

private fun iconUrl(state: MarketUi, row: MarketRow): String =
    if (state.base.isEmpty()) "" else MarketSources.join(state.base, row.entry.icon)

@Composable
private fun MarketAction(
    state: MarketUi,
    row: MarketRow,
    miuix: Boolean,
    onInstall: (MarketRow) -> Unit,
) {
    if (state.busyId == row.entry.id) {
        if (miuix) {
            MiuixText(text = "下载中…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            Text(
                text = "下载中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (row.status == MarketStatus.INCOMPATIBLE) return
    if (miuix) {
        MiuixTextButton(
            text = MarketCatalog.actionLabel(row.status),
            onClick = { onInstall(row) },
            colors = MiuixButtonDefaults.textButtonColorsPrimary(),
        )
    } else {
        TextButton(onClick = { onInstall(row) }) { Text(MarketCatalog.actionLabel(row.status)) }
    }
}



/** 来源选项：自动 / jsDelivr / GitHub Raw / Gh-Proxy（值 → 显示名由 [MarketSources.label] 给）。 */
private fun sourceOptions(): List<String> =
    listOf(MarketSources.AUTO) + MarketSources.candidates

/**
 * miuix 下拉刷新的四段提示文案。
 *
 * 库里默认是英文（"Release to refresh"…），在中文界面里突兀，直接覆盖掉。
 */
private val PULL_TEXTS = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")

// ---------- 弹窗（退出动画：置空 state 时不能立刻拆组合） ----------

/**
 * miuix 的文本弹窗宿主。
 *
 * 关窗时**保留最后一份内容**、只把 `show` 置 false，WindowDialog 才播得出退场动画；
 * 直接返回 null 拆掉组合的话就是「弹出有动画、消失没有」。
 */
@Composable
private fun MiuixTextDialogHost(spec: TextDialogSpec?) {
    var last by remember { mutableStateOf<TextDialogSpec?>(null) }
    if (spec != null) last = spec
    val shown = last ?: return
    WindowDialog(
        show = spec != null,
        onDismissRequest = { shown.onDismiss() },
        title = shown.title,
        content = {
            Column {
                if (shown.body.isNotBlank()) {
                    Text(
                        text = shown.body,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
                shown.extra?.invoke()
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiuixTextButton(
                        modifier = Modifier.weight(1f),
                        text = if (shown.positive.isEmpty()) "关闭" else "取消",
                        onClick = { shown.onDismiss() },
                    )
                    if (shown.positive.isNotEmpty()) {
                        MiuixTextButton(
                            modifier = Modifier.weight(1f),
                            text = shown.positive,
                            enabled = shown.positiveEnabled,
                            onClick = { shown.onPositive() },
                            colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdTextDialogHost(spec: TextDialogSpec?) {
    if (spec == null) return
    AlertDialog(
        onDismissRequest = spec.onDismiss,
        title = { Text(spec.title) },
        text = {
            Column {
                if (spec.body.isNotBlank()) Text(spec.body)
                spec.extra?.invoke()
            }
        },
        confirmButton = {
            if (spec.positive.isEmpty()) {
                TextButton(onClick = spec.onDismiss) { Text("关闭") }
            } else {
                TextButton(enabled = spec.positiveEnabled, onClick = spec.onPositive) { Text(spec.positive) }
            }
        },
        dismissButton = {
            if (spec.positive.isNotEmpty()) TextButton(onClick = spec.onDismiss) { Text("取消") }
        },
    )
}

/** 确认弹窗宿主（EngineDialog 投递进来的）：miuix 用 WindowDialog，MD3 用 AlertDialog。 */
@Composable
private fun MiuixConfirmHost(dialog: ConfirmDialog?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<ConfirmDialog?>(null) }
    if (dialog != null) last = dialog
    val shown = last ?: return
    var remaining by remember(shown) { mutableStateOf(shown.countdownSeconds) }
    LaunchedEffect(shown) {
        if (shown.countdownSeconds > 0) {
            remaining = shown.countdownSeconds
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
        }
    }
    val isCancelable = shown.negative != null
    WindowDialog(
        show = dialog != null,
        onDismissRequest = {
            if (isCancelable) onDismiss()
        },
        title = shown.title,
        content = {
            Column {
                if (!shown.dangerWarning.isNullOrBlank()) {
                    Text(
                        text = shown.dangerWarning,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                val scrollState = rememberScrollState()
                Text(
                    text = shown.message,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .drawVerticalScrollbar(
                            state = scrollState,
                            thumbColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        .verticalScroll(scrollState)
                        .padding(end = 10.dp),
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.negative != null) {
                        MiuixTextButton(
                            modifier = Modifier.weight(1f),
                            text = shown.negative,
                            onClick = {
                                val cb = shown.onNegative
                                onDismiss()
                                cb?.invoke()
                            },
                        )
                    }
                    val posText = if (remaining > 0) "${shown.positive} (${remaining}s)" else shown.positive
                    MiuixTextButton(
                        modifier = Modifier.weight(1f),
                        text = posText,
                        enabled = shown.positiveEnabled && remaining <= 0,
                        onClick = {
                            val cb = shown.onConfirm
                            onDismiss()
                            cb?.invoke()
                        },
                        colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

@Composable
private fun MdConfirmHost(dialog: ConfirmDialog?, onDismiss: () -> Unit) {
    if (dialog == null) return
    var remaining by remember(dialog) { mutableStateOf(dialog.countdownSeconds) }
    LaunchedEffect(dialog) {
        if (dialog.countdownSeconds > 0) {
            remaining = dialog.countdownSeconds
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
        }
    }
    val posText = if (remaining > 0) "${dialog.positive} (${remaining}s)" else dialog.positive
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .drawVerticalScrollbar(
                        state = scrollState,
                        thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp)
            ) {
                if (!dialog.dangerWarning.isNullOrBlank()) {
                    Text(
                        text = dialog.dangerWarning,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Text(dialog.message)
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.positiveEnabled && remaining <= 0,
                onClick = {
                    val cb = dialog.onConfirm
                    onDismiss()
                    cb?.invoke()
                }
            ) { Text(posText) }
        },
        dismissButton = if (dialog.negative != null) {
            {
                TextButton(onClick = {
                    val cb = dialog.onNegative
                    onDismiss()
                    cb?.invoke()
                }) { Text(dialog.negative) }
            }
        } else null,
    )
}

// ---------------------------------------------------------------- 来源弹窗内容


/**
 * 「市场来源」弹窗里的选项列表。选中哪个哪个用主题色；miuix 侧按钮之间留 8dp
 * （不排间距会挤成一坨，很难看）。
 */
@Composable
private fun MiuixSourceOptions(current: String, onPick: (String) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        sourceOptions().forEach { value ->
            MiuixTextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                text = MarketSources.label(value),
                onClick = { onPick(value) },
                colors = if (value == current) {
                    MiuixButtonDefaults.textButtonColorsPrimary()
                } else {
                    MiuixButtonDefaults.textButtonColors()
                },
            )
        }
    }
}

/**
 * MD 侧来源选择：与设置页「主题风格」「授权器」同一套 [MdRadioWidget]（不是 chip）。
 * 弹窗里放单选行，语义和别处一致：选中哪一项一眼看得出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdSourceOptions(current: String, onPick: (String) -> Unit) {
    Column {
        MdRadioWidget(
            title = "自动",
            description = null,
            selected = current == MarketSources.AUTO,
        ) { onPick(MarketSources.AUTO) }
        MarketSources.candidates.forEach { base ->
            MdRadioWidget(
                title = MarketSources.label(base),
                description = null,
                selected = base == current,
            ) { onPick(base) }
        }
    }
}
