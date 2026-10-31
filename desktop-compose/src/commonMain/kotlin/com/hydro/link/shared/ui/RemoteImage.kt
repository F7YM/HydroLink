package com.hydro.link.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程图片（插件图标、市场截图）。
 *
 * **不引 Coil/Glide**：这些图都很小（索引里的图标 2KB、截图几百 KB），进程内 LRU +
 * Skia 直接解码就够，与安卓端 `MarketCommon.RemoteImage` 同一套取舍。
 *
 * 两个必须：
 * · 下载放 IO 线程（在 UI 线程拉图会冻住整个窗口）；
 * · **手动跟 301**：jsDelivr 对 `/gh/...` 会 301 到 `raw.githubusercontent.com`（跨协议），
 *   而 `HttpURLConnection` 只跟同协议跳转，不手动跟就是"图标永远空白"。
 */
@Composable
fun RemoteImage(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    /** 还没解码出来时的底色（跟主题走，别写死一个黑块）。 */
    placeholderColor: Color = Color(0xFF2A2E36),
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { RemoteImages.load(url) }
    }
    Box(
        modifier = modifier.size(size).background(placeholderColor, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** 进程内图片缓存：同一个 URL 只下一次（插件/市场列表反复刷新时很省）。 */
private object RemoteImages {
    private const val MAX_ENTRIES = 96
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 加载失败返回 null（调用方显示占位块，不让一张坏图把整页搞崩）。 */
    fun load(url: String): ImageBitmap? {
        synchronized(cache) { cache[url] }?.let { return it }
        val result = runCatching { fetch(url) }.getOrNull()
        if (result != null) synchronized(cache) { cache[url] = result }
        return result
    }

    private fun fetch(url: String): ImageBitmap? {
        var current = url
        var conn: HttpURLConnection? = null
        repeat(5) {          // 最多跟 5 跳，兜住 CDN 链
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false   // 自己跟（要跨协议）
                connectTimeout = 5000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) return null
                // 相对 Location 也要能拼（CDN 里不少）
                current = URL(URL(current), next).toString()
                return@repeat
            }
            if (code != 200) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            return Image.makeFromEncoded(bytes)?.toComposeImageBitmap()
        }
        conn?.disconnect()
        return null
    }
}