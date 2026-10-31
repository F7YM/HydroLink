package com.hydro.link.compose.market

import com.hydro.link.shared.market.MarketEntry
import com.hydro.link.shared.market.marketBases
import com.hydro.link.shared.market.marketJoin
import com.hydro.link.shared.market.MarketIndex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 插件市场客户端（桌面端）。
 *
 * 协议与安卓端 `server/market/` 完全一致（索引 schema 1，同一个 `F7YM/hydromarket-index`
 * 仓），只是把 Gson 换成 kotlinx.serialization、缓存目录换成桌面数据目录。
 * 安卓端那份在 commonMain 可用是因为它本来就在安卓模块里；桌面这份先落在 desktopMain，
 * 等两端共享时合并到一处。
 *
 * 基址顺序照安卓端：jsDelivr → GitHub Raw → Gh-Proxy（用户可在设置里改）。
 */
@Serializable
private data class IndexEntryDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("runtime") val runtime: String = "",
    @SerialName("entry") val entry: String = "",
    @SerialName("min_hydrolink") val minHydroLink: String = "",
    @SerialName("permissions") val permissions: List<String> = emptyList(),
    @SerialName("url") val url: String = "",
    @SerialName("sha256") val sha256: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("description") val description: String = "",
    @SerialName("icon") val icon: String = "",
    @SerialName("category") val category: String = "other",
    @SerialName("notes") val notes: String = "",
    @SerialName("keywords") val keywords: List<String> = emptyList(),
    @SerialName("screenshots") val screenshots: List<String> = emptyList(),
    @SerialName("homepage") val homepage: String = "",
)

/** 索引拉取结果（成功/失败原因都要能显示给用户）。 */
sealed interface MarketResult {
    data class Ok(val index: MarketIndex, val base: String) : MarketResult
    data class Err(val message: String) : MarketResult
}

class MarketClient(
    private val cacheDir: File,
    private val sourceSetting: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 按候选基址依次尝试拉索引；成功就写缓存。 */
    fun fetchIndex(): MarketResult {
        val bases = marketBases(sourceSetting())
        var lastError = "未知错误"
        for (base in bases) {
            try {
                val url = marketJoin(base, "index.json")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/json")
                }
                val code = try {
                    conn.responseCode
                } finally {
                    // 读完要断开，否则连接池会攒住
                }
                if (code != 200) {
                    lastError = "HTTP $code"
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val index = parse(text)
                writeCache(index, base)
                return MarketResult.Ok(index, base)
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName.orEmpty()
            }
        }
        // 全失败就用缓存（离线时也能看市场列表）
        val cached = readCache()
        return if (cached != null) {
            MarketResult.Ok(cached.index, cached.base)
        } else {
            MarketResult.Err("拉取插件市场失败：$lastError")
        }
    }

    /**
     * 解析索引。
     *
     * 必填字段缺一个就整份索引报错（安卓端同一套规矩）：索引是自动生成的，宁可整个拉不到，
     * 也不能显示一份半吊子列表。
     */
    fun parse(text: String): MarketIndex {
        val root = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            ?: error("索引不是 JSON 对象")
        val schema = (root["schema"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (schema != "1") error("索引 schema 版本不支持：$schema")
        val plugins = root["plugins"]
        if (plugins is kotlinx.serialization.json.JsonArray && plugins.isEmpty()) {
            error("索引里没有任何插件")
        }
        val dto = json.decodeFromJsonElement(IndexFile.serializer(), root)
        val entries = dto.plugins.map { entry ->
            if (entry.id.isBlank()) error("索引里有条目缺少 id")
            if (entry.name.isBlank()) error("${entry.id} 缺少 name")
            if (!entry.sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                error("${entry.id} 的 sha256 不是 64 位小写 hex")
            }
            MarketEntry(
                id = entry.id,
                name = entry.name,
                author = entry.author,
                version = entry.version,
                runtime = entry.runtime,
                entry = entry.entry,
                minHydroLink = entry.minHydroLink,
                permissions = entry.permissions,
                url = entry.url,
                sha256 = entry.sha256,
                size = entry.size,
                description = entry.description,
                icon = entry.icon,
                category = entry.category,
                notes = entry.notes,
                keywords = entry.keywords,
                screenshots = entry.screenshots,
                homepage = entry.homepage,
            )
        }
        return MarketIndex(entries)
    }

    /** 下载 .hpk 到 [dest]，返回 null 表示成功，否则是错误原因。 */
    fun download(base: String, entry: MarketEntry, dest: File): String? {
        return try {
            val url = marketJoin(base, entry.url)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 60000
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return "下载失败：HTTP ${conn.responseCode}"
            }
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            verify(dest, entry)
        } catch (e: Exception) {
            e.message ?: e::class.simpleName.orEmpty()
        }
    }

    /** 校验 sha256 与文件大小：索引给的摘要对不上就别装。 */
    fun verify(file: File, entry: MarketEntry): String? {
        if (entry.size > 0 && file.length() != entry.size) {
            return "文件大小不符（期望 ${entry.size}，实际 ${file.length()}）"
        }
        val actual = sha256(file)
        if (actual != entry.sha256) return "校验失败：sha256 不匹配"
        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Cached(val index: MarketIndex, val base: String)

    private fun readCache(): Cached? {
        val file = File(cacheDir, "market-index.json")
        if (!file.isFile) return null
        return try {
            val root = json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            val base = (root["base"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            Cached(parse(root.toString()), base)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(index: MarketIndex, base: String) {
        try {
            cacheDir.mkdirs()
            val file = File(cacheDir, "market-index.json")
            val payload = buildString {
                append('{')
                append("\"base\":\"").append(base).append("\",")
                append("\"fetched_at\":\"").append(nowStamp()).append("\",")
                append("\"schema\":\"1\",\"plugins\":[")
                append(index.entries.joinToString(",") { entry ->
                    val dto = IndexEntryDto(
                        id = entry.id, name = entry.name, author = entry.author,
                        version = entry.version, runtime = entry.runtime, entry = entry.entry,
                        minHydroLink = entry.minHydroLink, permissions = entry.permissions,
                        url = entry.url, sha256 = entry.sha256, size = entry.size,
                        description = entry.description, icon = entry.icon,
                        category = entry.category, notes = entry.notes,
                        keywords = entry.keywords, screenshots = entry.screenshots,
                        homepage = entry.homepage,
                    )
                    json.encodeToString(IndexEntryDto.serializer(), dto)
                })
                append("]}")
            }
            file.writeText(payload)
        } catch (e: Exception) {
            // 缓存写失败不影响本次使用
        }
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}

@Serializable
private data class IndexFile(
    // 注意：索引里的 schema 是**数字** 1（`"schema": 1`），不是字符串。声明成 String
    // 会让 kotlinx 的严格解析直接抛错（真机表现：市场页整页显示一条解析异常）。
    // 所以这里用 JsonElement，版本判断交给下面手写的检查。
    @SerialName("schema") val schema: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("plugins") val plugins: List<IndexEntryDto> = emptyList(),
)
