package com.hydro.link.server.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件更新检查（插件 Tab 的刷新按钮触发的那条路径）。
 *
 * 这里钉的是三件事：只报「索引更新」的、本地更旧的不报（否则装下去会被禁降级挡住）、
 * 以及宿主版本不够时只让用户看更新日志但不让他装。
 */
class MarketUpdatesTest {

    private fun entry(
        id: String,
        version: String,
        minHydrolink: String = "",
        notes: String = "",
    ) = MarketEntry(
        id = id,
        name = "插件$id",
        author = "me",
        version = version,
        runtime = "js",
        entry = "server.js",
        minHydrolink = minHydrolink,
        permissions = listOf("storage"),
        description = "描述",
        category = "utility",
        icon = "i.png",
        url = "a.hpk",
        sha256 = "e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f",
        size = 20480,
        notes = notes,
    )

    private fun indexOf(vararg entries: MarketEntry) =
        MarketIndex(1, "2026-09-27T00:00:00Z", entries.toList())

    @Test
    fun `索引版本更高才算更新`() {
        val index = indexOf(entry("a", "1.2.0"), entry("b", "1.0.0"), entry("c", "2.0.0"))
        val updates = MarketUpdates.compute(index, mapOf("a" to "1.1.0", "b" to "1.0.0", "c" to "2.0.0"), "1.1.0")
        assertEquals(listOf("a"), updates.map { it.entry.id })
        assertEquals("1.1.0", updates.single().currentVersion)
        assertTrue(updates.single().compatible)
    }

    @Test
    fun `没装的与本地更新（索引更旧）都不报`() {
        val index = indexOf(entry("new", "1.0.0"), entry("ahead", "1.0.0"))
        val updates = MarketUpdates.compute(index, mapOf("ahead" to "2.0.0"), "1.1.0")
        assertTrue("没装的归市场管、更新列表里不该有", updates.isEmpty())
    }

    @Test
    fun `索引拉不到时返回空而不是崩`() {
        assertTrue(MarketUpdates.compute(null, mapOf("a" to "1.0.0"), "1.1.0").isEmpty())
    }

    @Test
    fun `多条更新按 id 排序`() {
        val index = indexOf(entry("z", "2.0.0"), entry("a", "2.0.0"), entry("m", "2.0.0"))
        val updates = MarketUpdates.compute(index, mapOf("z" to "1", "a" to "1", "m" to "1"), "1.1.0")
        assertEquals(listOf("a", "m", "z"), updates.map { it.entry.id })
    }

    @Test
    fun `宿主版本不够时标记为不兼容`() {
        val index = indexOf(entry("a", "2.0.0", minHydrolink = "2.0.0"))
        val update = MarketUpdates.compute(index, mapOf("a" to "1.0.0"), "1.1.0").single()
        assertFalse(update.compatible)
        // 宿主够新就正常
        assertTrue(MarketUpdates.compute(index, mapOf("a" to "1.0.0"), "2.0.0").single().compatible)
        // 没写 min_hydrolink = 不限
        assertTrue(MarketUpdates.hostVersionCovers("1.1.0", ""))
    }

    @Test
    fun `更新弹窗正文带上更新日志 版本变化与来源`() {
        val index = indexOf(entry("a", "1.2.0", notes = "修了歌词错行"))
        val update = MarketUpdates.compute(index, mapOf("a" to "1.1.0"), "1.1.0").single()
        val text = MarketUpdates.dialogText(update, "https://a", "1.1.0")
        assertTrue(text.contains("v1.2.0"))
        assertTrue(text.contains("v1.1.0"))
        assertTrue(text.contains("修了歌词错行"))
        assertTrue(text.contains("https://a"))
        assertTrue(text.contains("storage"))
        assertFalse(text.contains("版本过低"))
    }

    @Test
    fun `版本不够时正文写明差在哪 没有日志也给兜底文案`() {
        val index = indexOf(entry("a", "2.0.0", minHydrolink = "2.0.0"))
        val update = MarketUpdates.compute(index, mapOf("a" to "1.0.0"), "1.1.0").single()
        val text = MarketUpdates.dialogText(update, "", "1.1.0")
        assertTrue(text.contains("HydroLink 版本过低"))
        assertTrue(text.contains("2.0.0"))
        assertTrue(text.contains("1.1.0"))
        assertTrue("没写更新日志也得有句话", text.contains("没有提供更新日志"))
    }
}
