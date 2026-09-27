package com.hydro.link.server.market

import com.hydro.link.server.hydrokit.PluginStore

/** 一个插件在「本地已装」视角下的状态。 */
enum class MarketStatus {
    /** 没装过。 */
    AVAILABLE,

    /** 已装且就是索引里这版。 */
    INSTALLED,

    /** 已装，但索引里更新（点安装 = 升级）。 */
    UPDATE,

    /** 索引里这版要求更高的宿主版本，装了也跑不起来。 */
    INCOMPATIBLE,
}

/** 列表一行：索引条目 + 本地状态。 */
data class MarketRow(
    val entry: MarketEntry,
    val status: MarketStatus,
    val installedVersion: String,
)

/**
 * 搜索 / 分类 / 排序，纯函数（可 JVM 单测）。
 *
 * 排序固定「已装 → 可更新 → 其余」，同级按 id 稳定排：列表每次刷新顺序不变，
 * 用户不会看到「点一下刷新条目就跳位」。
 */
object MarketCatalog {

    const val CATEGORY_ALL = "all"

    /**
     * 状态判定。[installed] 为该 id 的本地版本（没装传 null），[hostVersion] 是宿主版本。
     * 版本比较直接用 [PluginStore.compareVersions] —— 与本地安装的「禁降级」判定同一个函数，
     * 免得市场说可更新、装的时候又说降级。
     */
    fun status(entry: MarketEntry, installed: String?, hostVersion: String): MarketStatus {
        if (entry.minHydrolink.isNotBlank() &&
            PluginStore.compareVersions(hostVersion, entry.minHydroLinkForCompare()) < 0
        ) {
            return MarketStatus.INCOMPATIBLE
        }
        if (installed.isNullOrBlank()) return MarketStatus.AVAILABLE
        return when {
            PluginStore.compareVersions(entry.version, installed) > 0 -> MarketStatus.UPDATE
            else -> MarketStatus.INSTALLED
        }
    }

    /** 全部行（不过滤），排序同 [filter]。 */
    fun rows(
        index: MarketIndex,
        installed: Map<String, String>,
        hostVersion: String,
    ): List<MarketRow> = index.plugins
        .map { MarketRow(it, status(it, installed[it.id], hostVersion), installed[it.id].orEmpty()) }
        .sortedWith(compareBy({ rank(it.status) }, { it.entry.id }))

    /**
     * 列表排序权重：已装 → 可更新 → 未装 → 不兼容。
     *
     * 写成显式的 `when` 而不是 `status.ordinal`：枚举里加一个状态就会把整个列表顺序
     * 悄悄改掉（ordinal 是声明顺序，不是「该不该往前排」）。不兼容的沉到最后。
     */
    private fun rank(status: MarketStatus): Int = when (status) {
        MarketStatus.INSTALLED -> 0
        MarketStatus.UPDATE -> 1
        MarketStatus.AVAILABLE -> 2
        MarketStatus.INCOMPATIBLE -> 3
    }

    /** 搜索 + 分类过滤（在 [rows] 的结果上做，保持排序）。 */
    fun filter(rows: List<MarketRow>, query: String, category: String): List<MarketRow> =
        rows.filter { row ->
            (category.isBlank() || category == CATEGORY_ALL || row.entry.category == category) &&
                matches(row.entry, query)
        }

    /** 分类 chip 的计数：全部 + 每个分类各有多少条（不受分类筛选影响，只受搜索影响）。 */
    fun counts(rows: List<MarketRow>, query: String): Map<String, Int> {
        val searched = rows.filter { matches(it.entry, query) }
        val out = LinkedHashMap<String, Int>()
        out[CATEGORY_ALL] = searched.size
        for (c in MarketIndexParser.CATEGORIES) {
            out[c] = searched.count { it.entry.category == c }
        }
        return out
    }

    /** 搜索：名称 / 描述 / 作者 / 关键词 / 包名，子串匹配（大小写不敏感）。 */
    fun matches(entry: MarketEntry, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return entry.name.contains(q, true) ||
            entry.id.contains(q, true) ||
            entry.author.contains(q, true) ||
            entry.description.contains(q, true) ||
            entry.keywords.any { it.contains(q, true) }
    }

    /** 列表/详情上显示的按钮文案。 */
    fun actionLabel(status: MarketStatus): String = when (status) {
        MarketStatus.AVAILABLE -> "安装"
        MarketStatus.UPDATE -> "更新"
        MarketStatus.INSTALLED -> "重新安装"
        MarketStatus.INCOMPATIBLE -> "不兼容"
    }
}

/** `min_hydrolink` 为空表示不限；这里只做一次空值收敛，比较交给 compareVersions。 */
private fun MarketEntry.minHydroLinkForCompare(): String = minHydrolink.ifBlank { "0" }
