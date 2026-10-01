package com.hydro.link.server.market

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 市场索引的来源基址。
 *
 * 索引里的 `url` / `icon` / `screenshots` 都是**仓库内相对路径**，所以换基址不用换索引 ——
 * 这就是「直连 / 代理 / CDN」能一键切换的前提。gh-proxy 是前缀式（把原 URL 整个包起来），
 * jsDelivr 是域名替换式，都归一化成「基址 + 相对路径」。
 */
object MarketSources {

    const val REPO = "F7YM/hydromarket-index"

    /** 设置里的取值：auto = 按顺序自动切换。 */
    const val AUTO = "auto"

    private const val RAW = "https://raw.githubusercontent.com/$REPO/main"

    /** 统一默认使用 Gh-Proxy */
    val GH_PROXY = "https://gh-proxy.com/$RAW"
    val DEFAULT = GH_PROXY

    /**
     * 镜像源候选表：**GitHub Raw 始终排在第一个**，接着是 Gh-Proxy，然后是 jsDelivr。
     */
    val candidates: List<String> = listOf(
        RAW,
        GH_PROXY,
        "https://cdn.jsdelivr.net/gh/$REPO@main",
    )

    /** 每个候选在「市场来源」弹窗里的短名，顺序与 [candidates] 严格一致。 */
    private val LABELS = listOf("GitHub Raw", "Gh-Proxy", "jsDelivr")

    /** 配置值 → 实际要试的基址列表。空时统一默认使用 Gh-Proxy 开头；auto 时按优先级依次尝试。 */
    fun bases(setting: String?): List<String> {
        val s = setting?.trim().orEmpty()
        if (s.isEmpty() || s == GH_PROXY) return listOf(GH_PROXY, RAW, "https://cdn.jsdelivr.net/gh/$REPO@main")
        if (s == AUTO) return listOf(GH_PROXY, RAW, "https://cdn.jsdelivr.net/gh/$REPO@main")
        return listOf(s.trimEnd('/'))
    }

    fun join(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * 来源在「市场来源」弹窗里显示的名字。
     * 默认显示 Gh-Proxy；自动 / GitHub Raw / Gh-Proxy / jsDelivr；填了别的地址就原样显示。
     */
    fun label(setting: String?): String {
        val s = setting?.trim().orEmpty()
        if (s.isEmpty()) return "Gh-Proxy"
        if (s == AUTO) return "自动"
        val hit = candidates.indexOfFirst { it == s || it.trimEnd('/') == s.trimEnd('/') }
        if (hit >= 0) return LABELS[hit]
        return s
    }
}

/**
 * 拉索引 / 下载包 / 校验 sha256。
 *
 * 网络走 [Fetcher] 抽象：真实现是 [UrlFetcher]（HttpURLConnection），单测塞假实现，
 * 这样 failover 顺序、304、缓存、校验失败这些分支都能在 JVM 里跑。
 * 不依赖 Android API。
 */
class MarketClient(
    private val cacheDir: File?,
    private val fetcher: Fetcher = UrlFetcher(),
    private val gson: Gson = Gson(),
) {

    fun interface Fetcher {
        /** [etag] 非空时带 `If-None-Match`；返回 304 表示本地缓存仍然新鲜。 */
        fun get(url: String, etag: String?): Reply
    }

    data class Reply(val code: Int, val body: String? = null, val etag: String? = null, val error: String? = null)

    /** 一次 fetch 的结果。[index] 为 null 表示彻底没拿到（连缓存也没有）。 */
    data class Result(
        val index: MarketIndex?,
        val base: String,
        val fromCache: Boolean,
        val etag: String,
        val updatedAt: String,
        val error: String?,
        val tried: List<String>,
    )

    private val cacheIndex get() = cacheDir?.let { File(it, "index.json") }
    private val cacheMeta get() = cacheDir?.let { File(it, "index.meta") }

    /**
     * 按基址顺序试索引：首个 200 且 schema 合法（或 304 命中缓存）的胜出。
     * 全失败时回落到本地缓存（离线也能看，只是标一下是缓存）。
     */
    fun fetch(bases: List<String>, etag: String? = null): Result {
        val cached = readCache()
        val tried = mutableListOf<String>()
        var lastError = "无法获取插件市场"
        for (base in bases) {
            val url = MarketSources.join(base, "index.json")
            tried += base
            val reply = try {
                fetcher.get(url, etag ?: cached?.etag)
            } catch (e: IOException) {
                Reply(0, error = e.message ?: e.javaClass.simpleName)
            }
            when {
                reply.code == 304 && cached != null ->
                    return Result(cached.index, base, true, cached.etag, cached.updatedAt, null, tried)

                reply.code == 200 && reply.body != null -> {
                    val parsed = try {
                        MarketIndexParser.parse(reply.body)
                    } catch (e: MarketFormatException) {
                        lastError = "$base：${e.message}"
                        null
                    }
                    if (parsed != null) {
                        val newEtag = reply.etag.orEmpty()
                        writeCache(parsed, newEtag, base)
                        return Result(parsed, base, false, newEtag, nowStamp(), null, tried)
                    }
                }

                else -> lastError = reply.error?.let { "$base：$it" }
                    ?: "$base：HTTP ${reply.code}"
            }
        }
        val fallback = readCache()
        return Result(
            index = fallback?.index,
            base = fallback?.base.orEmpty(),
            fromCache = fallback != null,
            etag = fallback?.etag.orEmpty(),
            updatedAt = fallback?.updatedAt.orEmpty(),
            error = if (fallback != null) "$lastError（显示的是上次缓存）" else lastError,
            tried = tried,
        )
    }

    /**
     * 下载 `.hpk` 到 [dest] 并校验 sha256 + size。
     * 返回 null 表示成功，否则是给用户看的一句话（校验不过直接删掉半截文件）。
     */
    fun download(base: String, entry: MarketEntry, dest: File): String? {
        val url = MarketSources.join(base, entry.url)
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) return "下载失败：HTTP ${conn.responseCode}"
                conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            } finally {
                conn.disconnect()
            }
            verify(dest, entry)
        } catch (e: Exception) {
            dest.delete()
            "下载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 校验已下载文件；不过就删掉并给出原因。 */
    fun verify(file: File, entry: MarketEntry): String? {
        if (!file.isFile) return "下载失败：文件不存在"
        val size = file.length()
        if (size != entry.size) {
            file.delete()
            return "校验失败：大小对不上（索引 ${entry.size}，实际 $size）"
        }
        val sha = sha256(file)
        if (sha != entry.sha256) {
            file.delete()
            return "校验失败：指纹对不上（索引 ${entry.sha256.take(12)}，实际 ${sha.take(12)}）"
        }
        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- 缓存（index.json + etag/来源/时间） ----

    private data class Cached(val index: MarketIndex, val etag: String, val base: String, val updatedAt: String)

    private fun readCache(): Cached? {
        val indexFile = cacheIndex ?: return null
        val metaFile = cacheMeta ?: return null
        if (!indexFile.isFile || !metaFile.isFile) return null
        return try {
            val index = MarketIndexParser.parse(indexFile.readText(Charsets.UTF_8))
            val meta = gson.fromJson(metaFile.readText(Charsets.UTF_8), CacheMeta::class.java)
            Cached(index, meta.etag.orEmpty(), meta.base.orEmpty(), meta.updatedAt.orEmpty())
        } catch (e: Exception) {
            // 缓存坏了就当没有：下一次 fetch 会覆盖它
            null
        }
    }

    private fun writeCache(index: MarketIndex, etag: String, base: String) {
        val indexFile = cacheIndex ?: return
        val metaFile = cacheMeta ?: return
        try {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(gson.toJson(index), Charsets.UTF_8)
            metaFile.writeText(
                gson.toJson(CacheMeta(etag, base, nowStamp())),
                Charsets.UTF_8,
            )
        } catch (e: IOException) {
            // 缓存写不进去不影响这次拉取
        }
    }

    private data class CacheMeta(val etag: String?, val base: String?, val updatedAt: String?)

    private fun nowStamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
}

/** 真网络实现：HttpURLConnection + 跟随重定向（raw / 代理 / CDN 都会 30x）。 */
class UrlFetcher(
    private val connectTimeout: Int = 10_000,
    private val readTimeout: Int = 20_000,
) : MarketClient.Fetcher {

    override fun get(url: String, etag: String?): MarketClient.Reply {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = this@UrlFetcher.connectTimeout
                readTimeout = this@UrlFetcher.readTimeout
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                if (!etag.isNullOrEmpty()) setRequestProperty("If-None-Match", etag)
            }
            when (val code = conn.responseCode) {
                304 -> MarketClient.Reply(304, etag = conn.getHeaderField("ETag"))
                200 -> MarketClient.Reply(
                    200,
                    body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
                    etag = conn.getHeaderField("ETag"),
                )
                else -> MarketClient.Reply(code, error = "HTTP $code")
            }
        } catch (e: JsonSyntaxException) {
            MarketClient.Reply(0, error = e.message)
        } catch (e: Exception) {
            MarketClient.Reply(0, error = e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
