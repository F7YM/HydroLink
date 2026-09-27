package com.hydro.link.server.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态判定 / 搜索 / 分类 / 排序。 */
class MarketCatalogTest {

    private fun entry(
        id: String,
        version: String = "1.0.0",
        category: String = "utility",
        keywords: List<String> = emptyList(),
        description: String = "描述",
        author: String = "作者",
        minHydrolink: String = "",
        name: String = "插件$id",
    ) = MarketEntry(
        id = id,
        name = name,
        author = author,
        version = version,
        runtime = "js",
        entry = "server.js",
        minHydrolink = minHydrolink,
        permissions = listOf("storage"),
        description = description,
        category = category,
        icon = "i.png",
        url = "a.hpk",
        sha256 = "e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f",
        size = 10,
        keywords = keywords,
    )

    private fun indexOf(vararg entries: MarketEntry) =
        MarketIndex(1, "2026-09-26T00:00:00Z", entries.toList())

    // ---- 状态 ----

    @Test
    fun `没装过是可安装`() {
        assertEquals(MarketStatus.AVAILABLE, MarketCatalog.status(entry("a"), null, "1.1.0"))
        assertEquals(MarketStatus.AVAILABLE, MarketCatalog.status(entry("a"), "", "1.1.0"))
    }

    @Test
    fun `同版本是已安装 索引更新是可更新 索引更旧也算已安装（装下去会被禁降级挡住）`() {
        assertEquals(MarketStatus.INSTALLED, MarketCatalog.status(entry("a", "1.2.0"), "1.2.0", "1.1.0"))
        assertEquals(MarketStatus.UPDATE, MarketCatalog.status(entry("a", "1.3.0"), "1.2.0", "1.1.0"))
        assertEquals(MarketStatus.INSTALLED, MarketCatalog.status(entry("a", "1.1.0"), "1.2.0", "1.1.0"))
    }

    @Test
    fun `min_hydrolink 高于宿主版本就是不兼容`() {
        val e = entry("a", minHydrolink = "2.0.0")
        assertEquals(MarketStatus.INCOMPATIBLE, MarketCatalog.status(e, null, "1.1.0"))
        // 宿主够新 → 回到正常判定
        assertEquals(MarketStatus.AVAILABLE, MarketCatalog.status(e, null, "2.0.0"))
        assertEquals(MarketStatus.AVAILABLE, MarketCatalog.status(entry("a", minHydrolink = "1.1.0"), null, "1.1.0"))
    }

    @Test
    fun `按钮文案跟着状态走`() {
        assertEquals("安装", MarketCatalog.actionLabel(MarketStatus.AVAILABLE))
        assertEquals("更新", MarketCatalog.actionLabel(MarketStatus.UPDATE))
        assertEquals("重新安装", MarketCatalog.actionLabel(MarketStatus.INSTALLED))
        assertEquals("不兼容", MarketCatalog.actionLabel(MarketStatus.INCOMPATIBLE))
    }

    // ---- 搜索 / 分类 ----

    @Test
    fun `搜索命中名称 包名 作者 描述 关键词且大小写不敏感`() {
        val e = entry("com.demo.weather", name = "示例天气", author = "HydroLink", description = "预报未来三天", keywords = listOf("下雨"))
        assertTrue(MarketCatalog.matches(e, "天气"))
        assertTrue(MarketCatalog.matches(e, "com.demo"))
        assertTrue(MarketCatalog.matches(e, "hydrolink"))
        assertTrue(MarketCatalog.matches(e, "预报"))
        assertTrue(MarketCatalog.matches(e, "下雨"))
        assertTrue(MarketCatalog.matches(e, "示例天气"))
        assertTrue(!MarketCatalog.matches(e, "音乐"))
        assertTrue(MarketCatalog.matches(e, "   ")) // 空查询 = 全匹配
    }

    @Test
    fun `分类过滤只留本类 全部则全留`() {
        val rows = MarketCatalog.rows(
            indexOf(
                entry("a", category = "game"),
                entry("b", category = "utility"),
                entry("c", category = "other"),
            ),
            emptyMap(),
            "1.1.0",
        )
        assertEquals(3, MarketCatalog.filter(rows, "", MarketCatalog.CATEGORY_ALL).size)
        assertEquals(listOf("a"), MarketCatalog.filter(rows, "", "game").map { it.entry.id })
        assertEquals(0, MarketCatalog.filter(rows, "", "utility").count { it.entry.id == "a" })
    }

    @Test
    fun `分类计数不受分类筛选影响 但受搜索影响`() {
        val rows = MarketCatalog.rows(
            indexOf(
                entry("a", category = "game", name = "游戏A"),
                entry("b", category = "game", name = "游戏B"),
                entry("c", category = "utility"),
            ),
            emptyMap(),
            "1.1.0",
        )
        val all = MarketCatalog.counts(rows, "")
        assertEquals(3, all[MarketCatalog.CATEGORY_ALL])
        assertEquals(2, all["game"])
        assertEquals(1, all["utility"])
        assertEquals(0, all["other"])

        val searched = MarketCatalog.counts(rows, "游戏A")
        assertEquals(1, searched[MarketCatalog.CATEGORY_ALL])
        assertEquals(1, searched["game"])
    }

    @Test
    fun `排序把已装和可更新排在前面 同级按 id 稳定`() {
        val rows = MarketCatalog.rows(
            indexOf(
                entry("z.new"),
                entry("m.old"),
                entry("a.update", version = "2.0.0"),
                entry("b.same"),
            ),
            mapOf(
                "m.old" to "1.0.0",
                "a.update" to "1.0.0",
                "b.same" to "1.0.0",
            ),
            "1.1.0",
        )
        // 已装（按 id）→ 可更新 → 未装
        assertEquals(
            listOf(
                "b.same" to MarketStatus.INSTALLED,
                "m.old" to MarketStatus.INSTALLED,
                "a.update" to MarketStatus.UPDATE,
                "z.new" to MarketStatus.AVAILABLE,
            ),
            rows.map { it.entry.id to it.status },
        )
    }

    @Test
    fun `搜索与分类可以叠加`() {
        val rows = MarketCatalog.rows(
            indexOf(
                entry("a", category = "game", name = "音乐游戏"),
                entry("b", category = "game", name = "策略游戏"),
                entry("c", category = "utility", name = "音乐工具"),
            ),
            emptyMap(),
            "1.1.0",
        )
        val found = MarketCatalog.filter(rows, "音乐", "game")
        assertEquals(listOf("a"), found.map { it.entry.id })
    }
}
