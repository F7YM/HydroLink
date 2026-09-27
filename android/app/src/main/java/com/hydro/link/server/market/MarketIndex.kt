package com.hydro.link.server.market

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * 插件市场索引（对应 GitHub 仓 `F7YM/hydromarket-index` 的 `index.json`，schema 1）。
 *
 * 与包内 manifest 的分工（协议见该仓 README）：`id/name/author/version/runtime/entry/
 * min_hydrolink/permissions` 全部由索引生成脚本从 `.hpk` 里解析出来，是**包内事实的镜像**；
 * `description/category/icon/keywords/screenshots` 是市场侧元数据（`meta.json`）。
 *
 * 这里只做纯数据与解析，不碰网络与 Android API —— 解析/校验逻辑要能在 JVM 单测里跑。
 */
data class MarketEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val runtime: String,
    val entry: String,
    val minHydrolink: String,
    val permissions: List<String>,
    val description: String,
    val category: String,
    val icon: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val keywords: List<String> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val homepage: String = "",
    /** 本版本更新日志（可选，索引仓 meta.json 的 notes）。「插件更新」弹窗会显示。 */
    val notes: String = "",
)

data class MarketIndex(
    val schema: Int,
    val generatedAt: String,
    val plugins: List<MarketEntry>,
)

/** 索引不合法（schema 不认识、必填字段缺失、sha256 格式错……）。 */
class MarketFormatException(message: String) : Exception(message)

object MarketIndexParser {

    /** 当前客户端只认 schema 1；以后索引加字段不算破坏，但升 schema 要两头发版。 */
    const val SCHEMA = 1

    /** 分类取值与索引仓脚本一致，改这里等于改协议，两端 + 生成脚本都要跟。 */
    val CATEGORIES = listOf("game", "utility", "other")

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun parse(json: String): MarketIndex {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonSyntaxException) {
            throw MarketFormatException("索引不是合法 JSON：${e.message}")
        }
        if (!root.isJsonObject) throw MarketFormatException("索引根节点不是对象")
        val obj = root.asJsonObject

        val schema = obj.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw MarketFormatException("索引缺 schema")
        if (schema != SCHEMA) throw MarketFormatException("索引版本不支持：$schema（本客户端支持 $SCHEMA）")

        val arr = obj.get("plugins")
            ?: throw MarketFormatException("索引缺 plugins")
        if (!arr.isJsonArray) throw MarketFormatException("索引的 plugins 不是数组")

        val plugins = arr.asJsonArray.mapNotNull { el ->
            if (el == null || !el.isJsonObject) throw MarketFormatException("plugins 里有非对象条目")
            readEntry(el.asJsonObject)
        }
        return MarketIndex(
            schema = schema,
            generatedAt = str(obj, "generated_at"),
            plugins = plugins,
        )
    }

    private fun readEntry(o: com.google.gson.JsonObject): MarketEntry {
        val id = req(o, "id")
        val name = req(o, "name")
        val version = req(o, "version")
        val runtime = req(o, "runtime")
        val description = req(o, "description")
        val category = req(o, "category")
        val icon = req(o, "icon")
        val url = req(o, "url")
        val sha256 = req(o, "sha256")
        val size = o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        if (size <= 0) throw MarketFormatException("$id: size 必须是正整数")
        if (!SHA256.matches(sha256)) throw MarketFormatException("$id: sha256 不是 64 位小写十六进制")
        if (category !in CATEGORIES) {
            throw MarketFormatException("$id: category 只能是 ${CATEGORIES.joinToString("/")}（现在是 $category）")
        }
        // 相对路径：换镜像（raw / gh-proxy / jsDelivr）时索引不用改，也顺带挡掉
        // 索引里塞绝对 URL 把客户端指到别处去。`..` 一律拒（目录逃逸）。
        relPath(id, "icon", icon)
        relPath(id, "url", url)
        return MarketEntry(
            id = id,
            name = name,
            author = str(o, "author"),
            version = version,
            runtime = runtime,
            entry = str(o, "entry"),
            minHydrolink = str(o, "min_hydrolink"),
            permissions = strings(o, "permissions"),
            description = description,
            category = category,
            icon = icon,
            url = url,
            sha256 = sha256,
            size = size,
            keywords = strings(o, "keywords"),
            screenshots = strings(o, "screenshots").also {
                it.forEach { p -> relPath(id, "screenshot", p) }
            },
            homepage = str(o, "homepage"),
            notes = str(o, "notes"),
        )
    }

    private fun str(o: com.google.gson.JsonObject, field: String): String =
        o.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

    private fun req(o: com.google.gson.JsonObject, field: String): String =
        str(o, field).ifEmpty { throw MarketFormatException("索引条目缺必填字段 $field") }

    private fun strings(o: com.google.gson.JsonObject, field: String): List<String> {
        val el = o.get(field) ?: return emptyList()
        if (el.isJsonNull) return emptyList()
        if (!el.isJsonArray) throw MarketFormatException("$field 应该是数组")
        return el.asJsonArray.map { it?.asString?.trim().orEmpty() }.filter { it.isNotEmpty() }
    }

    /** 必须是仓库内相对路径：没有 scheme、不以 / 开头、不含 `..`。 */
    private fun relPath(id: String, field: String, path: String) {
        if (path.contains("://") || path.startsWith("/")) {
            throw MarketFormatException("$id: $field 必须是仓库内相对路径（现在是 $path）")
        }
        if (path.split('/').any { it == ".." }) {
            throw MarketFormatException("$id: $field 不允许包含 ..（$path）")
        }
    }
}
