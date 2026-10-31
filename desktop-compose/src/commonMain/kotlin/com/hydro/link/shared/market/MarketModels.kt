package com.hydro.link.shared.market

/**
 * 插件市场索引的数据形状（协议 schema 1，与安卓端 `server/market/MarketIndex.kt` 一致）。
 *
 * 索引由 `F7YM/hydromarket-index` 仓的脚本从各插件包内的 manifest 生成：
 * `id/name/author/version/runtime/entry/min_hydrolink/permissions` 来自包内 manifest，
 * `url/sha256/size` 由脚本算，`description/icon/category/notes/keywords/screenshots/homepage`
 * 来自 `meta.json`。
 */
data class MarketEntry(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val runtime: String = "",
    val entry: String = "",
    val minHydroLink: String = "",
    val permissions: List<String> = emptyList(),
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val description: String = "",
    val icon: String = "",
    val category: String = "other",
    val notes: String = "",
    val keywords: List<String> = emptyList(),
    val screenshots: List<String> = emptyList(),
    val homepage: String = "",
) {
    /** 副标题：作者 · v版本 · 运行时。 */
    val pkgLine: String get() = buildString {
        if (author.isNotBlank()) append(author).append(" · ")
        append("v").append(version)
        if (runtime.isNotBlank()) append(" · ").append(runtime)
    }
}

/** 分类只有三档（协议受控，不要自造）。 */
enum class MarketCategory(val key: String, val label: String) {
    All("all", "全部"),
    Game("game", "游戏"),
    Utility("utility", "实用"),
    Other("other", "其他");

    companion object {
        fun of(key: String?): MarketCategory =
            entries.firstOrNull { it.key == key } ?: Other
    }
}

data class MarketIndex(val entries: List<MarketEntry> = emptyList()) {
    fun search(query: String, category: MarketCategory): List<MarketEntry> {
        val q = query.trim()
        return entries.filter { entry ->
            (category == MarketCategory.All || MarketCategory.of(entry.category) == category) &&
                (q.isEmpty() ||
                    entry.name.contains(q, ignoreCase = true) ||
                    entry.description.contains(q, ignoreCase = true) ||
                    entry.author.contains(q, ignoreCase = true) ||
                    entry.keywords.any { it.contains(q, ignoreCase = true) })
        }
    }
}

/** 市场状态：装插件的进度/结果都要能显示。 */
data class MarketState(
    val entries: List<MarketEntry> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val installing: String? = null,
    val installed: Set<String> = emptySet(),
    /** 这次索引是从哪个基址拉到的（下载 .hpk 要用同一个基址）。 */
    val baseHint: String? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && !loading
}

/**
 * 市场镜像源（协议侧只认这四个值）。
 *
 * 顺序即优先级：默认「自动」按 jsDelivr → GitHub Raw → Gh-Proxy 依次尝试
 * （用户定的顺序，jsDelivr 排第一）。
 */
enum class MarketSources(val key: String, val label: String, val summary: String) {
    GitHubRaw("https://raw.githubusercontent.com/F7YM/hydromarket-index/main", "GitHub Raw", "raw.githubusercontent.com"),
    GhProxy("https://gh-proxy.com/https://raw.githubusercontent.com/F7YM/hydromarket-index/main", "Gh-Proxy", "gh-proxy.com（GitHub Raw 的代理）"),
    JsDelivr("https://cdn.jsdelivr.net/gh/F7YM/hydromarket-index@main", "jsDelivr", "cdn.jsdelivr.net"),
    Auto("auto", "自动", "按 Gh-Proxy → GitHub Raw → jsDelivr 依次尝试"),
    ;

    companion object {
        /** 默认使用 Gh-Proxy */
        val DEFAULT = GhProxy

        /** 兜底成 Gh-Proxy：配置里存了不认识的值（或空）默认指向 Gh-Proxy */
        fun of(key: String?): MarketSources = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 「自动」时按优先级给出的候选基址（Gh-Proxy → GitHub Raw → jsDelivr）。 */
fun marketBases(setting: String?): List<String> = when (val src = MarketSources.of(setting)) {
    MarketSources.Auto -> listOf(
        MarketSources.GhProxy.key,
        MarketSources.GitHubRaw.key,
        MarketSources.JsDelivr.key,
    )
    else -> listOf(src.key)
}

/** 单个基址（「自动」取优先级最高的那个）。 */
fun marketFirstBase(setting: String?): String = marketBases(setting).first()

/** 拼 URL（基址末尾可能有斜杠，路径开头也可能有）。 */
fun marketJoin(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')
