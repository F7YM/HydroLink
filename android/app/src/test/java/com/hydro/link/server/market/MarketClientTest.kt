package com.hydro.link.server.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 拉取（基址 failover / ETag / 缓存回落）与下载校验。
 *
 * 网络用假 Fetcher 顶掉：这些分支（直连挂了切代理、内容不是索引、全挂回缓存、
 * 指纹对不上）真机上很难稳定复现，全靠这里钉住。
 */
class MarketClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val goodJson = """
        {"schema":1,"generated_at":"2026-09-26T14:25:47Z","plugins":[
          {"id":"a.b","name":"A","author":"me","version":"1.0.0","runtime":"js",
           "description":"d","category":"other","icon":"i.png","url":"a.hpk",
           "sha256":"e39400db02ffa498fca7090738275bb0e6035121b06f91c4a7916af486b4ff8f","size":5}]}
    """.trimIndent()

    private class FakeFetcher(
        private val handler: (url: String, etag: String?) -> MarketClient.Reply,
    ) : MarketClient.Fetcher {
        val requested = mutableListOf<String>()
        override fun get(url: String, etag: String?): MarketClient.Reply {
            requested += url
            return handler(url, etag)
        }
    }

    private fun client(dir: File?, fetcher: MarketClient.Fetcher) = MarketClient(dir, fetcher)

    // ---- failover ----

    @Test
    fun `第一个基址失败就换下一个`() {
        val fetcher = FakeFetcher { url, _ ->
            if (url.startsWith("https://a")) MarketClient.Reply(500, error = "HTTP 500")
            else MarketClient.Reply(200, body = goodJson, etag = "e1")
        }
        val r = client(tmp.newFolder(), fetcher).fetch(listOf("https://a", "https://b"))
        assertEquals("https://b", r.base)
        assertNotNull(r.index)
        assertEquals(1, r.index!!.plugins.size)
        assertNull(r.error)
        assertEquals(listOf("https://a", "https://b"), r.tried)
        assertTrue(fetcher.requested.all { it.endsWith("/index.json") })
    }

    @Test
    fun `HTTP 200 但内容不是合法索引时同样要换下一个`() {
        val fetcher = FakeFetcher { url, _ ->
            if (url.startsWith("https://a")) MarketClient.Reply(200, body = "{\"schema\":99,\"plugins\":[]}")
            else MarketClient.Reply(200, body = goodJson)
        }
        val r = client(tmp.newFolder(), fetcher).fetch(listOf("https://a", "https://b"))
        assertEquals("https://b", r.base)
        assertNotNull(r.index)
    }

    @Test
    fun `全部失败且没有缓存时给出错误 没有索引`() {
        val fetcher = FakeFetcher { _, _ -> MarketClient.Reply(0, error = "connect timed out") }
        val r = client(tmp.newFolder(), fetcher).fetch(listOf("https://a", "https://b"))
        assertNull(r.index)
        assertNotNull(r.error)
    }

    @Test
    fun `全部失败时回落到本地缓存并说明是缓存`() {
        val dir = tmp.newFolder()
        val ok = FakeFetcher { _, _ -> MarketClient.Reply(200, body = goodJson, etag = "e1") }
        client(dir, ok).fetch(listOf("https://a"))
        val bad = FakeFetcher { _, _ -> MarketClient.Reply(500, error = "HTTP 500") }
        val r = client(dir, bad).fetch(listOf("https://a"))
        assertNotNull("断网时应该还能看到上次的索引", r.index)
        assertTrue(r.fromCache)
        assertNotNull(r.error)
        assertTrue("错误里要说清显示的是缓存", r.error!!.contains("缓存"))
    }

    @Test
    fun `304 表示本地缓存仍然新鲜`() {
        val dir = tmp.newFolder()
        client(dir, FakeFetcher { _, _ -> MarketClient.Reply(200, body = goodJson, etag = "e1") })
            .fetch(listOf("https://a"))
        var seenEtag: String? = null
        val r = client(dir, FakeFetcher { _, etag ->
            seenEtag = etag
            MarketClient.Reply(304, etag = "e1")
        }).fetch(listOf("https://a"))
        assertEquals("e1", seenEtag)
        assertTrue(r.fromCache)
        assertNotNull(r.index)
    }

    @Test
    fun `缓存文件坏了就当没有缓存`() {
        val dir = tmp.newFolder()
        File(dir, "index.json").writeText("garbage")
        File(dir, "index.meta").writeText("{}")
        val r = client(dir, FakeFetcher { _, _ -> MarketClient.Reply(500, error = "boom") })
            .fetch(listOf("https://a"))
        assertNull(r.index)
    }

    // ---- 下载校验 ----

    @Test
    fun `指纹与大小都对就放行`() {
        val dir = tmp.newFolder()
        val file = File(dir, "x.hpk").apply { writeText("hello") }
        val c = client(null, FakeFetcher { _, _ -> MarketClient.Reply(0) })
        val entry = MarketIndexParser.parse(goodJson).plugins.single()
        // fixture 写死 size=5 / 上面文件的 sha256 不同 → 先按实际值构造一个对的
        val real = entry.copy(size = file.length(), sha256 = c.sha256(file))
        assertNull(c.verify(file, real))
        assertTrue(file.exists())
    }

    @Test
    fun `大小对不上就拒并删掉文件`() {
        val dir = tmp.newFolder()
        val file = File(dir, "x.hpk").apply { writeText("hello") }
        val c = client(null, FakeFetcher { _, _ -> MarketClient.Reply(0) })
        val entry = MarketIndexParser.parse(goodJson).plugins.single()
        val err = c.verify(file, entry.copy(size = 999))
        assertNotNull(err)
        assertTrue(err!!.contains("大小"))
        assertTrue("校验不过的文件要删掉", !file.exists())
    }

    @Test
    fun `指纹对不上就拒并删掉文件`() {
        val dir = tmp.newFolder()
        val file = File(dir, "x.hpk").apply { writeText("hello") }
        val c = client(null, FakeFetcher { _, _ -> MarketClient.Reply(0) })
        val entry = MarketIndexParser.parse(goodJson).plugins.single()
        val err = c.verify(file, entry.copy(size = file.length(), sha256 = "0".repeat(64)))
        assertNotNull(err)
        assertTrue(err!!.contains("指纹"))
        assertTrue(!file.exists())
    }

    @Test
    fun `sha256 与命令行一致`() {
        val dir = tmp.newFolder()
        val file = File(dir, "x.hpk").apply { writeText("hello") }
        val c = client(null, FakeFetcher { _, _ -> MarketClient.Reply(0) })
        // echo -n hello | sha256sum
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", c.sha256(file))
    }

    // ---- 来源表 ----

    @Test
    fun `auto 走候选表 自定义只走自己 尾斜杠去掉`() {
        val expectedBases = listOf(MarketSources.GH_PROXY, "https://raw.githubusercontent.com/${MarketSources.REPO}/main", "https://cdn.jsdelivr.net/gh/${MarketSources.REPO}@main")
        assertEquals(expectedBases, MarketSources.bases(MarketSources.AUTO))
        assertEquals(expectedBases, MarketSources.bases(""))
        assertEquals(expectedBases, MarketSources.bases(null))
        assertEquals(listOf("https://my.mirror/x"), MarketSources.bases("https://my.mirror/x/"))
        assertEquals(3, MarketSources.candidates.size)
    }

    @Test
    fun `基址与相对路径拼接不丢斜杠也不多斜杠`() {
        assertEquals(
            "https://a/index.json",
            MarketSources.join("https://a/", "/index.json"),
        )
        assertEquals(
            "https://a/plugins/x/1.0.0/a.hpk",
            MarketSources.join("https://a", "plugins/x/1.0.0/a.hpk"),
        )
    }

    @Test
    fun `来源名用短名而不是甩一串URL`() {
        assertEquals("Gh-Proxy", MarketSources.label(null))
        assertEquals("Gh-Proxy", MarketSources.label(""))
        assertEquals("自动", MarketSources.label(MarketSources.AUTO))
        // GitHub Raw 始终排在第一个，短名与候选顺序严格对应
        assertTrue(MarketSources.candidates[0].contains("raw.githubusercontent.com"))
        assertEquals(
            listOf("GitHub Raw", "Gh-Proxy", "jsDelivr"),
            MarketSources.candidates.map { MarketSources.label(it) },
        )
        // 尾斜杠也算同一个
        assertEquals("GitHub Raw", MarketSources.label(MarketSources.candidates[0] + "/"))
        // 自定义地址原样显示
        assertEquals("https://my.mirror/x", MarketSources.label("https://my.mirror/x"))
    }
}
