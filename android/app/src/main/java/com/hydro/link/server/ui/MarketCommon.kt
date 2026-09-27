package com.hydro.link.server.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import com.hydro.link.server.market.MarketRow
import com.hydro.link.server.market.MarketSources
import com.hydro.link.server.market.MarketStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 插件市场两个 Activity（列表页 / 详情页）共用的显示层小件。
 *
 * 分出来是因为**两个都要**（详情页是独立 Activity，用系统转场），各写一遍必然长歪。
 */
internal fun categoryLabel(category: String): String = when (category) {
    "game" -> "游戏"
    "utility" -> "工具"
    "other" -> "其他"
    else -> category
}

/**
 * 行内状态提示。**「已安装」不显示**：按钮上的「重新安装」已经说明装过了，再挂一行
 * 字纯属噪音（用户明确要求去掉）。只留真正需要用户做点什么的状态。
 */
internal fun statusLabel(row: MarketRow): String = when (row.status) {
    MarketStatus.AVAILABLE, MarketStatus.INSTALLED -> ""
    MarketStatus.UPDATE -> "可更新"
    MarketStatus.INCOMPATIBLE -> "需要 HydroLink ${row.entry.minHydrolink}+"
}

@Composable
internal fun MiuixStatusColor(status: MarketStatus): Color = when (status) {
    MarketStatus.AVAILABLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    MarketStatus.INSTALLED, MarketStatus.UPDATE -> MiuixTheme.colorScheme.primary
    MarketStatus.INCOMPATIBLE -> Color(0xFFE5484D)
}

@Composable
internal fun MdStatusColor(status: MarketStatus): Color = when (status) {
    MarketStatus.AVAILABLE -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    MarketStatus.INSTALLED, MarketStatus.UPDATE -> androidx.compose.material3.MaterialTheme.colorScheme.primary
    MarketStatus.INCOMPATIBLE -> Color(0xFFE5484D)
}

/** 详情页的信息行（标题 + 值）。 */
internal fun detailFields(row: MarketRow): List<Pair<String, String>> {
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

/**
 * 远程图标/截图（索引里是仓库内相对路径，基址可切）。
 *
 * 不引 Coil/Glide：图标 ≤256KB，直接内存解码 + 进程内 LRU 缓存就够，
 * 少一个依赖少一份许可证与安装包体积。url 为空（还没拉到索引）就不请求。
 */
@Composable
internal fun RemoteImage(url: String, size: Dp) {
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

/** 索引条目 → 绝对 URL（base 为空说明还没拉到索引，返回空串表示别去请求）。 */
internal fun marketAssetUrl(base: String, path: String): String =
    if (base.isEmpty()) "" else MarketSources.join(base, path)
