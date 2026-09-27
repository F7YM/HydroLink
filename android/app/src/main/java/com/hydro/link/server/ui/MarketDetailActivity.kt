package com.hydro.link.server.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.hydro.link.server.R
import com.hydro.link.server.market.MarketCatalog
import com.hydro.link.server.market.MarketClient
import com.hydro.link.server.market.MarketEntry
import com.hydro.link.server.market.MarketSources
import com.hydro.link.server.market.MarketStatus
import com.hydro.link.server.market.UrlFetcher
import com.hydro.link.server.ui.material.ExpressiveBackButton
import com.hydro.link.server.ui.material.MdBaseWidget
import com.hydro.link.server.ui.material.MdNoteWidget
import com.hydro.link.server.ui.material.MdSegmentedColumn
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.util.DataManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File

/**
 * 插件市场 · 详情页（**独立 Activity**）。
 *
 * 为什么是独立 Activity 而不是列表页里的一层：用户要的是**系统转场**——开/关都由系统
 * 动画驱动，返回键走系统返回栈（finish 后上一页按系统动画回来）。用 NavHost 在页内做
 * 就只能自己写动画，手感和应用其他地方对不上（这正是之前被吐槽的地方）。
 *
 * 条目通过 Intent 传 JSON（Gson）：市场索引条目是不可变数据，序列化传递比重建一套
 * 拉取链路简单，也不会因为刷新导致详情页内容跳变。
 */
class MarketDetailActivity : AppCompatActivity() {

    private val busy = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = intent.getStringExtra(EXTRA_ENTRY)?.let { json ->
            runCatching { Gson().fromJson(json, MarketEntry::class.java) }.getOrNull()
        }
        if (entry == null) {
            finish()
            return
        }
        val base = intent.getStringExtra(EXTRA_BASE).orEmpty()
        val miuix = DataManager.get(this).loadConfig().miuix
        SystemBars.apply(this, miuix)

        // 本地版本用于状态标签（已装/可更新）；拉不到就按「未装」处理
        val installedVersion = PluginUi.server()?.hydroKit?.store?.list()
            ?.firstOrNull { it.id == entry.id }?.version
        val status = MarketCatalog.status(
            entry,
            installedVersion,
            com.hydro.link.server.BuildConfig.VERSION_NAME,
        )
        val row = com.hydro.link.server.market.MarketRow(entry, status, installedVersion.orEmpty())

        setContent {
            val content: @Composable () -> Unit = {
                DetailScreen(
                    miuix = miuix,
                    base = base,
                    row = row,
                    busy = busy.value,
                    onBack = { finish() },
                    onInstall = {
                        MarketInstaller.install(
                            activity = this,
                            client = MarketClient(
                                cacheDir = File(cacheDir, "marketplace"),
                                fetcher = UrlFetcher(),
                            ),
                            base = base.ifEmpty {
                                MarketSources.bases(DataManager.get(this).loadConfig().marketSource).first()
                            },
                            entry = entry,
                            positiveLabel = MarketCatalog.actionLabel(status),
                            onBusy = { busy.value = it.isNotEmpty() },
                            onDone = { message ->
                                busy.value = false
                                MarketInstaller.toast(this, message)
                                // 装完状态变了（已装/可更新），回列表让用户看到
                                finish()
                            },
                        )
                    },
                )
            }
            if (miuix) {
                MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
            } else {
                HydroMaterialTheme { content() }
            }
        }
    }

    companion object {
        private const val EXTRA_ENTRY = "entry"
        private const val EXTRA_BASE = "base"

        fun intent(context: Context, entry: MarketEntry, base: String): Intent =
            Intent(context, MarketDetailActivity::class.java).apply {
                putExtra(EXTRA_ENTRY, Gson().toJson(entry))
                putExtra(EXTRA_BASE, base)
            }
    }
}

@Composable
private fun DetailScreen(
    miuix: Boolean,
    base: String,
    row: com.hydro.link.server.market.MarketRow,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
    if (miuix) {
        MiuixDetailBody(base, row, busy, onBack, onInstall)
    } else {
        MdDetailBody(base, row, busy, onBack, onInstall)
    }
}

@Composable
private fun MiuixDetailBody(
    base: String,
    row: com.hydro.link.server.market.MarketRow,
    busy: Boolean,
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
            HeaderRow(base, row, busy, onInstall, miuix = true)
            if (row.status == MarketStatus.INCOMPATIBLE) {
                MiuixText(
                    text = "HydroLink 版本过低（需要 v" + row.entry.minHydrolink + "+）",
                    fontSize = 12.sp,
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
                        RemoteImage(marketAssetUrl(base, path), 120.dp)
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
                            text = "关键词：" + row.entry.keywords.joinToString("、"),
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
private fun MdDetailBody(
    base: String,
    row: com.hydro.link.server.market.MarketRow,
    busy: Boolean,
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
            HeaderRow(base, row, busy, onInstall, miuix = false)
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
                        RemoteImage(marketAssetUrl(base, path), 120.dp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 与列表同一套 MD 组件：长文本用 MdNoteWidget（不截断），键值用 MdBaseWidget
            Column {
                MdSegmentedColumn(title = "介绍") {
                    item {
                        MdNoteWidget(
                            title = "它做什么",
                            text = row.entry.description +
                                if (row.entry.keywords.isEmpty()) ""
                                else "\n\n关键词：" + row.entry.keywords.joinToString("、"),
                        )
                    }
                }
                if (row.entry.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    MdSegmentedColumn(title = "更新日志") {
                        item { MdNoteWidget(title = "这一版改了什么", text = row.entry.notes) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                MdSegmentedColumn(title = "信息") {
                    detailFields(row).forEach { (label, value) ->
                        item { MdBaseWidget(title = label, description = value) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 图标 + 名称/版本/状态 + 安装按钮（同一行，四个区块对齐）。 */
@Composable
private fun HeaderRow(
    base: String,
    row: com.hydro.link.server.market.MarketRow,
    busy: Boolean,
    onInstall: () -> Unit,
    miuix: Boolean,
) {
    val label = if (busy) "下载中…" else MarketCatalog.actionLabel(row.status)
    val enabled = row.status != MarketStatus.INCOMPATIBLE && !busy
    if (miuix) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(marketAssetUrl(base, row.entry.icon), 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                MiuixText(text = row.entry.name, color = MiuixTheme.colorScheme.onSurface)
                MiuixText(
                    text = "v" + row.entry.version + " · " + row.entry.author,
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
            Spacer(Modifier.width(12.dp))
            // 主题色实心按钮（两个引擎一致）
            MiuixButton(
                enabled = enabled,
                onClick = onInstall,
                colors = MiuixButtonDefaults.buttonColorsPrimary(),
            ) { MiuixText(text = label) }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(marketAssetUrl(base, row.entry.icon), 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "v" + row.entry.version + " · " + row.entry.author,
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
            Spacer(Modifier.width(12.dp))
            // 主题色实心按钮（不是文字链钮）：这是详情页的主操作
            Button(enabled = enabled, onClick = onInstall) { Text(label) }
        }
    }
}

