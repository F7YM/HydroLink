package com.hydro.link.server.update

import com.hydro.link.server.market.MarketClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateTest {

    private class FakeFetcher(
        private val handler: (url: String) -> MarketClient.Reply,
    ) : MarketClient.Fetcher {
        val requested = mutableListOf<String>()
        override fun get(url: String, etag: String?): MarketClient.Reply {
            requested += url
            return handler(url)
        }
    }

    private val sampleJson = """
        {
          "version": "1.2.0",
          "version_code": 12,
          "publish_date": "2026-10-02",
          "title": "HydroLink v1.2.0",
          "changelog": [
            "新增：App 自动检查更新与网盘分发支持",
            "优化：插件市场弹窗规范与按钮强调色",
            "修复：若干已知体验问题"
          ],
          "download_url": "https://www.123pan.com/s/sample",
          "backup_url": "https://wwx.lanzou.com/sample",
          "password": "abcd"
        }
    """.trimIndent()

    @Test
    fun `解析标准 app_json 成功`() {
        val info = AppUpdateHelper.parse(sampleJson)
        assertEquals("1.2.0", info.version)
        assertEquals(12, info.versionCode)
        assertEquals("2026-10-02", info.publishDate)
        assertEquals("HydroLink v1.2.0", info.title)
        assertEquals(3, info.changelog.size)
        assertEquals("新增：App 自动检查更新与网盘分发支持", info.changelog[0])
        assertEquals("https://www.123pan.com/s/sample", info.downloadUrl)
        assertEquals("https://wwx.lanzou.com/sample", info.backupUrl)
        assertEquals("abcd", info.password)
    }

    @Test
    fun `解析 changelog 为纯文本单字符串的情况`() {
        val json = """
            {
              "version": "1.2.1",
              "changelog": "第一行日志\n第二行日志"
            }
        """.trimIndent()
        val info = AppUpdateHelper.parse(json)
        assertEquals("1.2.1", info.version)
        assertEquals(listOf("第一行日志", "第二行日志"), info.changelog)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `缺少 version 抛出异常`() {
        val json = """{"title": "No version"}"""
        AppUpdateHelper.parse(json)
    }

    @Test
    fun `版本判定 isNewer`() {
        assertTrue(AppUpdateHelper.isNewer("1.2.0", "1.1.0"))
        assertTrue(AppUpdateHelper.isNewer("1.0.1", "1.0.0"))
        assertTrue(AppUpdateHelper.isNewer("2.0.0", "1.9.9"))
        assertFalse(AppUpdateHelper.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateHelper.isNewer("1.0.0", "1.0.1"))
        assertFalse(AppUpdateHelper.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `自动检查与稍后提醒频控逻辑`() {
        val today = "2026-10-02"
        val yesterday = "2026-10-01"

        // 每天最多自动检查 1 次
        assertFalse(AppUpdateHelper.shouldAutoCheck(today, today))
        assertTrue(AppUpdateHelper.shouldAutoCheck(yesterday, today))
        assertTrue(AppUpdateHelper.shouldAutoCheck("", today))

        // 稍后提醒今日不再提醒
        assertFalse(AppUpdateHelper.shouldPrompt(today, today))
        assertTrue(AppUpdateHelper.shouldPrompt(yesterday, today))
        assertTrue(AppUpdateHelper.shouldPrompt("", today))
    }

    @Test
    fun `解析分端分类 changelog（手机端、桌面端、手表端）`() {
        val json = """
            {
              "version": "1.2.1",
              "changelog": {
                "手机端": [
                  "新增：App 自动检查更新与网盘分发支持",
                  "修复：若干已知体验问题"
                ],
                "桌面端": [
                  "新增：Compose 双引擎更新弹窗"
                ],
                "手表端": [
                  "优化：互联通信稳定性"
                ]
              }
            }
        """.trimIndent()
        val info = AppUpdateHelper.parse(json)
        val text = AppUpdateHelper.formatChangelog(info.changelog)
        assertTrue(text.contains("【手机端】"))
        assertTrue(text.contains("1. 新增：App 自动检查更新与网盘分发支持"))
        assertTrue(text.contains("2. 修复：若干已知体验问题"))
        assertTrue(text.contains("【桌面端】"))
        assertTrue(text.contains("1. 新增：Compose 双引擎更新弹窗"))
        assertTrue(text.contains("【手表端】"))
        assertTrue(text.contains("1. 优化：互联通信稳定性"))
    }

    @Test
    fun `更新弹窗正文生成`() {
        val info = AppUpdateHelper.parse(sampleJson)
        val text = AppUpdateHelper.dialogText(info, "1.1.0")
        assertTrue(text.contains("最新版本：v1.2.0（当前 v1.1.0）"))
        assertTrue(text.contains("发布日期：2026-10-02"))
        assertTrue(text.contains("网盘提取码：abcd（前往时自动复制）"))
        assertTrue(text.contains("1. 新增：App 自动检查更新与网盘分发支持"))
    }

    @Test
    fun `AppUpdateClient 顺序尝试镜像源并在首个成功时返回`() {
        val fetcher = FakeFetcher { url ->
            if (url.startsWith("https://bad1.com") || url.startsWith("https://bad2.com")) {
                MarketClient.Reply(code = 404, error = "Not found")
            } else if (url.startsWith("https://ok.com")) {
                MarketClient.Reply(code = 200, body = sampleJson)
            } else {
                throw IOException("Network error")
            }
        }

        val client = AppUpdateClient(fetcher)
        val result = client.fetchUpdate(listOf("https://bad1.com", "https://bad2.com", "https://ok.com"))

        assertNotNull(result)
        assertEquals("1.2.0", result!!.version)
        assertEquals(3, fetcher.requested.size)
        assertTrue(fetcher.requested[0].startsWith("https://bad1.com"))
        assertTrue(fetcher.requested[1].startsWith("https://bad2.com"))
        assertTrue(fetcher.requested[2].startsWith("https://ok.com"))
    }

    @Test
    fun `AppUpdateClient 所有源失败时返回 null`() {
        val fetcher = FakeFetcher {
            MarketClient.Reply(code = 500, error = "Server error")
        }
        val client = AppUpdateClient(fetcher)
        val result = client.fetchUpdate(listOf("https://a.com", "https://b.com"))
        assertNull(result)
    }
}
