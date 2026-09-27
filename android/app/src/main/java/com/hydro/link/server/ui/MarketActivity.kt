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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
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
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.ui.miuix.ConfirmDialog
import com.hydro.link.server.util.DataManager
import kotlinx.coroutines.Dispatchers
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
    private var prevWindowDialog: ((String, String, String, String?, (() -> Unit)?, (() -> Unit)?, Boolean) -> Boolean)? = null

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
            EngineDialog.window = { title, message, positive, negative, onNegative, onConfirm, positiveEnabled ->
                ui.value = ui.value.copy(
                    dialog = ConfirmDialog(
                        title = title,
                        message = message,
                        positive = positive,
                        negative = negative,
                        onNegative = onNegative,
                        onConfirm = onConfirm,
                        positiveEnabled = positiveEnabled,
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

private fun categoryLabel(category: String): String = when (category) {
    "game" -> "游戏"
    "utility" -> "工具"
    "other" -> "其他"
    else -> category
}

private fun statusLabel(row: MarketRow): String = when (row.status) {
    MarketStatus.AVAILABLE -> ""
    MarketStatus.INSTALLED -> "已安装"
    MarketStatus.UPDATE -> "可更新"
    MarketStatus.INCOMPATIBLE -> "需要 HydroLink ${row.entry.minHydrolink}+"
}

/** 详情页与列表共用的信息行（标题 + 值）。 */
private fun detailFields(row: MarketRow): List<Pair<String, String>> {
    val e = row.entry
    return buildList {
        add("版本" to "v${e.version}")
        add("作者" to e.author.ifBlank { "—" })
        add("包名" to e.id)
        add("分类" to categoryLabel(e.category))
        add("运行时" to e.runtime)
        add("大小" to "${e.size / 1024} KB")
        add("权限" to if (e.permissions.isEmpty()) "未申请权限" else e.permissions.joinToString("、"))
        add("指纹" to e.sha256.take(12))
        if (e.minHydrolink.isNotBlank()) add("最低宿主" to "HydroLink v${e.minHydrolink}")
        if (e.homepage.isNotBlank()) add("主页" to e.homepage)
    }
}

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

    // 详情页走**真的返回栈**（NavController）：系统返回 = 回列表，再返回才退出 Activity。
    // 早先图省事用一个 state 字段整页覆盖，系统返回会直接退出到主界面——那种写法不算
    // 导航栈，用户按返回的预期是「回到刚才那页」。
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = ROUTE_LIST,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally(tween(240)) { it } + fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it } + fadeOut(tween(200)) },
    ) {
        composable(ROUTE_LIST) {
            val goDetail: (MarketRow) -> Unit = { row ->
                // 路径里只放值（`detail/<id>`）：写成 `detail/id=<值>` 的话整段会被当成
                // {id} 的值，详情页找不到对应条目 → 立刻退回列表，看起来就是「点不开」
                nav.navigate("$ROUTE_DETAIL/${row.entry.id}")
            }
            if (miuix) {
                MiuixMarketScreen(
                    state, spec, onBack, onQuery, onCategory, onRefresh, onInstall, goDetail,
                    onPickSource,
                )
            } else {
                MdMarketScreen(
                    state, spec, onBack, onQuery, onCategory, onRefresh, onInstall, goDetail,
                    onPickSource,
                )
            }
        }
        composable("$ROUTE_DETAIL/{$ARG_ID}") { entry ->
            val id = entry.arguments?.getString(ARG_ID).orEmpty()
            val row = state.rows.firstOrNull { it.entry.id == id }
            if (row == null) {
                // 索引刷新后这个插件不在了（下了架/过滤没了）：别停在空详情页
                LaunchedEffect(id) { nav.popBackStack() }
            } else if (miuix) {
                MiuixDetailScreen(
                    state = state,
                    row = row,
                    onBack = { nav.popBackStack() },
                    onInstall = {
                        nav.popBackStack()
                        if (row.status != MarketStatus.INCOMPATIBLE) onInstall(row)
                    },
                )
            } else {
                MdDetailScreen(
                    state = state,
                    row = row,
                    onBack = { nav.popBackStack() },
                    onInstall = {
                        nav.popBackStack()
                        if (row.status != MarketStatus.INCOMPATIBLE) onInstall(row)
                    },
                )
            }
        }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = { Text("搜索插件") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                        Text(
                            text = if (state.rows.isEmpty()) "市场里还没有插件" else "没有匹配的插件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                visible.forEach { row ->
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDetail(row) }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RemoteImage(iconUrl(state, row), 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = row.entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "v${row.entry.version} · ${row.entry.author}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                MarketAction(state = state, row = row, miuix = false, onInstall = onInstall)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = row.entry.description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = categoryLabel(row.entry.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = statusLabel(row),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MdStatusColor(row.status),
                                )
                                if (row.entry.permissions.isNotEmpty()) {
                                    Text(
                                        text = row.entry.permissions.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    MdTextDialogHost(spec)
}

// ---------------------------------------------------------------- 详情页

/**
 * 插件详情（整页，两个引擎各一套外观）。
 *
 * 单独一页而不是弹窗：图标、描述、更新日志、截图放不进一个小对话框里；
 * 顶栏返回键/系统返回都只是回到列表。
 */
@Composable
private fun MiuixDetailScreen(
    state: MarketUi,
    row: MarketRow,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
    MiuixScaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            MiuixTopAppBar(
                title = row.entry.name,
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(iconUrl(state, row), 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    MiuixText(text = row.entry.name, color = MiuixTheme.colorScheme.onSurface)
                    MiuixText(
                        text = "v${row.entry.version} · ${row.entry.author}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (statusLabel(row).isNotEmpty()) {
                        MiuixText(
                            text = statusLabel(row),
                            fontSize = 11.sp,
                            color = MiuixStatusColor(row.status),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                // 安装/更新按钮就摆在图标右侧；不兼容时灰显，原因写在下面
                MiuixTextButton(
                    text = if (state.busyId == row.entry.id) "下载中…"
                    else MarketCatalog.actionLabel(row.status),
                    enabled = row.status != MarketStatus.INCOMPATIBLE && state.busyId.isEmpty(),
                    onClick = onInstall,
                    colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                )
            }
            if (row.status == MarketStatus.INCOMPATIBLE) {
                MiuixText(
                    text = "HydroLink 版本过低（需要 v" + row.entry.minHydrolink + "+）",
                    fontSize = 12.sp,
                    color = Color(0xFFE5484D),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                // 安装/更新按钮就摆在图标右侧；不兼容时灰显，原因写在下面
                TextButton(
                    enabled = row.status != MarketStatus.INCOMPATIBLE && state.busyId.isEmpty(),
                    onClick = onInstall,
                ) {
                    Text(
                        if (state.busyId == row.entry.id) "下载中…"
                        else MarketCatalog.actionLabel(row.status)
                    )
                }
            }
            if (row.status == MarketStatus.INCOMPATIBLE) {
                Text(
                    text = "HydroLink 版本过低（需要 v" + row.entry.minHydrolink + "+）",
                    color = Color(0xFFE5484D),
                )
            }
            if (row.entry.screenshots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.entry.screenshots.forEach { path ->
                        RemoteImage(
                            MarketSources.join(state.base, path),
                            120.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MiuixCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    MiuixText(text = "介绍", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    MiuixText(text = row.entry.description, color = MiuixTheme.colorScheme.onSurface)
                    if (row.entry.keywords.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MiuixText(
                            text = "关键词：${row.entry.keywords.joinToString("、")}",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (row.entry.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                MiuixCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        MiuixText(text = "更新日志", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        MiuixText(text = row.entry.notes, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MiuixCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    detailFields(row).forEach { (label, value) ->
                        MiuixText(
                            text = label,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        MiuixText(text = value, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdDetailScreen(
    state: MarketUi,
    row: MarketRow,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(row.entry.name) },
                navigationIcon = { ExpressiveBackButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(iconUrl(state, row), 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "v${row.entry.version} · ${row.entry.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (statusLabel(row).isNotEmpty()) {
                        Text(
                            text = statusLabel(row),
                            style = MaterialTheme.typography.labelSmall,
                            color = MdStatusColor(row.status),
                        )
                    }
                }
            }
            if (row.entry.screenshots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.entry.screenshots.forEach { path ->
                        RemoteImage(MarketSources.join(state.base, path), 120.dp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "介绍") {
                Text(text = row.entry.description)
                if (row.entry.keywords.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "关键词：${row.entry.keywords.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.entry.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SectionCard(title = "更新日志") {
                    Text(text = row.entry.notes)
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "信息") {
                detailFields(row).forEach { (label, value) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = value)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
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

@Composable
private fun MiuixStatusColor(status: MarketStatus): Color = when (status) {
    MarketStatus.AVAILABLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    MarketStatus.INSTALLED, MarketStatus.UPDATE -> MiuixTheme.colorScheme.primary
    MarketStatus.INCOMPATIBLE -> Color(0xFFE5484D)
}

@Composable
private fun MdStatusColor(status: MarketStatus): Color = when (status) {
    MarketStatus.AVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    MarketStatus.INSTALLED, MarketStatus.UPDATE -> MaterialTheme.colorScheme.primary
    MarketStatus.INCOMPATIBLE -> Color(0xFFE5484D)
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
    WindowDialog(
        show = dialog != null,
        onDismissRequest = onDismiss,
        title = shown.title,
        content = {
            Column {
                Text(
                    text = shown.message,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
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
                    MiuixTextButton(
                        modifier = Modifier.weight(1f),
                        text = shown.positive,
                        enabled = shown.positiveEnabled,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = { Text(dialog.message) },
        confirmButton = {
            TextButton(enabled = dialog.positiveEnabled, onClick = {
                val cb = dialog.onConfirm
                onDismiss()
                cb?.invoke()
            }) { Text(dialog.positive) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdSourceOptions(current: String, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceOptions().forEach { value ->
                FilterChip(
                    selected = value == current,
                    onClick = { onPick(value) },
                    label = { Text(MarketSources.label(value)) },
                )
            }
        }
    }
}

/**
 * 远程图标（索引里的 icon 是仓库内相对路径，基址可切）。
 *
 * 不引 Coil/Glide：图标 ≤256KB，直接内存解码 + 进程内 LRU 缓存就够，
 * 少一个依赖少一份许可证与安装包体积。base 为空（还没拉到索引）就不请求。
 */
@Composable
private fun RemoteImage(url: String, size: Dp) {
    var bitmap by remember(url) { mutableStateOf(if (url.isEmpty()) null else RemoteImages.get(url)) }
    LaunchedEffect(url) {
        if (url.isNotEmpty() && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { RemoteImages.load(url) }
            if (loaded != null) bitmap = loaded
        }
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private object RemoteImages {
    private const val MAX = 64
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MAX
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = cache[url]

    fun load(url: String): ImageBitmap? = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        val bytes = try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        if (decoded != null) synchronized(this) { cache[url] = decoded }
        decoded
    } catch (e: Exception) {
        null
    }
}
