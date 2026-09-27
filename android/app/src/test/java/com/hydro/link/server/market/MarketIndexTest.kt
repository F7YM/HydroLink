package com.hydro.link.server.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 索引解析与校验。
 *
 * fixture 抄的是 hydromarket-index 里 `com.hydro.deskbuddy` 那条的真实形状（字段名/嵌套），
 * 协议一改这里就红——这是故意的，别把 fixture 改成"能过就行"。
 */
class MarketIndexTest {

    private val entry = """
        {
          "id": "com.hydro.deskbuddy",
          "name": "桌搭",
          "author": "HydroLink",
          "version": "0.9.0",
          "runtime": "js",
          "entry": "server.js",
          "min_hydrolink": "",
          "permissions": ["music", "storage"],
          "description": "正在播放的圆屏界面",
          "category": "utility",
          "icon": "plugins/com.hydro.deskbuddy/0.9.0/icon.png",
          "url": "plugins/com.hydro.deskbuddy/0.9.0/com.hydro.deskbuddy.hpk",
          "sha256": "e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f",
          "size": 12985,
          "keywords": ["音乐", "歌词"],
          "notes": "首个上架版本",
          "homepage": "https://example.invalid/desk"
        }
    """.trimIndent()

    private fun indexJson(vararg plugins: String, schema: Int = 1) = """
        {"schema": $schema, "generated_at": "2026-09-26T14:25:47Z", "plugins": [${plugins.joinToString(",")}]}
    """.trimIndent()

    @Test
    fun `真实形状的索引能解析出全部字段`() {
        val index = MarketIndexParser.parse(indexJson(entry))
        assertEquals(1, index.schema)
        assertEquals("2026-09-26T14:25:47Z", index.generatedAt)
        val e = index.plugins.single()
        assertEquals("com.hydro.deskbuddy", e.id)
        assertEquals("桌搭", e.name)
        assertEquals("0.9.0", e.version)
        assertEquals("js", e.runtime)
        assertEquals(listOf("music", "storage"), e.permissions)
        assertEquals("utility", e.category)
        assertEquals(12985L, e.size)
        assertEquals(listOf("音乐", "歌词"), e.keywords)
        assertEquals("首个上架版本", e.notes)
        assertTrue(e.screenshots.isEmpty())
        assertEquals("https://example.invalid/desk", e.homepage)
    }

    @Test
    fun `可选字段缺失时给空值而不是崩`() {
        val minimal = """
            {"id":"a.b","name":"A","version":"1.0.0","runtime":"js","description":"d",
             "category":"other","icon":"i.png","url":"a.hpk",
             "sha256":"e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f","size":10}
        """.trimIndent()
        val e = MarketIndexParser.parse(indexJson(minimal)).plugins.single()
        assertEquals("", e.author)
        assertEquals("", e.minHydrolink)
        assertEquals("", e.notes)
        assertEquals(emptyList<String>(), e.permissions)
    }

    @Test
    fun `空 plugins 是合法的`() {
        assertEquals(0, MarketIndexParser.parse(indexJson()).plugins.size)
    }

    @Test
    fun `schema 不认识就拒绝`() {
        expectFormat("索引版本不支持") { MarketIndexParser.parse(indexJson(entry, schema = 2)) }
    }

    @Test
    fun `必填字段缺失逐个报出来`() {
        for (field in listOf("id", "name", "version", "runtime", "description", "category", "icon", "url", "sha256")) {
            val broken = entry.replace("\"$field\":", "\"removed_$field\":")
            expectFormat(field) { MarketIndexParser.parse(indexJson(broken)) }
        }
    }

    @Test
    fun `sha256 必须是 64 位小写十六进制`() {
        expectFormat("sha256") {
            MarketIndexParser.parse(indexJson(entry.replace("e39400db", "E39400DB")))
        }
        expectFormat("sha256") {
            MarketIndexParser.parse(indexJson(entry.replace("e39400db", "abc")))
        }
    }

    @Test
    fun `size 必须是正整数`() {
        expectFormat("size") { MarketIndexParser.parse(indexJson(entry.replace("\"size\": 12985", "\"size\": 0"))) }
    }

    @Test
    fun `category 只能是受控三值`() {
        for (bad in listOf("weather", "GAME", "")) {
            expectFormat("category") {
                MarketIndexParser.parse(indexJson(entry.replace("\"utility\"", "\"$bad\"")))
            }
        }
    }

    @Test
    fun `icon 与 url 必须是仓库内相对路径`() {
        // 绝对 URL 会把客户端指到别处去（索引生成脚本只会写相对路径）
        expectFormat("相对路径") {
            MarketIndexParser.parse(indexJson(entry.replace("\"plugins/com.hydro.deskbuddy/0.9.0/icon.png\"", "\"https://evil.invalid/i.png\"")))
        }
        expectFormat("相对路径") {
            MarketIndexParser.parse(indexJson(entry.replace("\"plugins/com.hydro.deskbuddy/0.9.0/com.hydro.deskbuddy.hpk\"", "\"/abs/a.hpk\"")))
        }
        // 目录逃逸
        expectFormat("..") {
            MarketIndexParser.parse(indexJson(entry.replace("plugins/com.hydro.deskbuddy/0.9.0/icon.png", "../../etc/icon.png")))
        }
    }

    @Test
    fun `截图同样只认相对路径`() {
        val withShot = entry.replace(
            "\"keywords\": [\"音乐\", \"歌词\"],",
            "\"keywords\": [\"音乐\"], \"screenshots\": [\"../out.png\"],",
        )
        expectFormat("..") { MarketIndexParser.parse(indexJson(withShot)) }
    }

    @Test
    fun `不是 JSON 或结构不对时给的是格式错误而不是别的异常`() {
        expectFormat("JSON") { MarketIndexParser.parse("not json") }
        expectFormat("对象") { MarketIndexParser.parse("[]") }
        expectFormat("plugins") { MarketIndexParser.parse("""{"schema":1}""") }
    }

    private fun expectFormat(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("应当抛 MarketFormatException（含「$fragment」）")
        } catch (e: MarketFormatException) {
            assertTrue(
                "错误信息应包含「$fragment」，实际：${e.message}",
                e.message?.contains(fragment) == true,
            )
        }
    }
}
