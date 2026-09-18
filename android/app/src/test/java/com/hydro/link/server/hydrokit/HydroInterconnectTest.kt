package com.hydro.link.server.hydrokit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 互联通道（XMS Wearable UDP）请求解析的回归。
 *
 * 手表不是总能走 HTTP：连上手机后走的是互联通道，报文里**没有 HTTP 头**，
 * 也没有 NanoHTTPD 帮忙解析。历史上这段缺失导致手表端插件页面在互联通道下
 * 直接 404、存储快照丢失。这里把解析行为钉死。
 */
class HydroInterconnectTest {

    @Test
    fun splitsPathAndQuery() {
        val (path, query) = HydroInterconnect.splitPath(
            "/api/hydro/com.demo.weather/app/main/view?shape=circle&width=466&height=466&dpr=2&dt=watch&_=7"
        )
        assertEquals("/api/hydro/com.demo.weather/app/main/view", path)
        assertEquals("circle", query["shape"])
        assertEquals("466", query["width"])
        assertEquals("2", query["dpr"])
        assertEquals("watch", query["dt"])
        assertEquals("7", query["_"])
    }

    @Test
    fun pathWithoutQueryKeepsEmptyMap() {
        val (path, query) = HydroInterconnect.splitPath("/api/hydro/list")
        assertEquals("/api/hydro/list", path)
        assertTrue(query.isEmpty())
    }

    @Test
    fun decodesUrlEncodedStoreSnapshot() {
        // 手表把存储快照塞进 query（互联通道没有请求头，只能用这条通道）
        val (_, query) = HydroInterconnect.splitPath(
            "/api/hydro/com.demo.weather/app/main/view?store=%7B%22city%22%3A%22%E5%8C%97%E4%BA%AC%22%7D"
        )
        assertEquals("""{"city":"北京"}""", query["store"])
    }

    @Test
    fun toleratesGarbageInsteadOfThrowing() {
        // 空段、无值、空 key、坏编码：手表端拼串出点毛病不该让整个接口 500
        val q = HydroInterconnect.parseQueryString("a=1&&b=&=2&c=%E4%B8&d")
        assertEquals("1", q["a"])
        assertEquals("", q["b"])
        assertNull(q[""])   // 段 "=2"：空 key 丢弃
        assertNull(q["d"])  // "d"：没有等号，丢弃
        // 坏 UTF-8 编码不抛异常，照样进 map
        assertTrue(q.containsKey("c"))
    }

    @Test
    fun buildsRequestWithNoHeadersAndUpperCasedMethod() {
        val req = HydroInterconnect.toRequest(
            rawPath = "/api/hydro/com.demo.weather/app/main/event?shape=rect",
            method = "post",
            body = """{"event":{"handler":"refresh"}}""",
        )
        assertEquals("POST", req.method)
        assertEquals("/api/hydro/com.demo.weather/app/main/event", req.path)
        assertEquals("rect", req.query["shape"])
        assertTrue(req.headers.isEmpty())
        assertEquals("""{"event":{"handler":"refresh"}}""", req.body)
    }

    // ---- 响应包装（互联通道只能传 JSON）----

    private val gson = com.google.gson.Gson()

    @Test
    fun wrapsJsonBodyAsIs() {
        val res = HydroHttpResponse(200, "application/json", """{"root":{"type":"text"}}""", null)
        val el = HydroInterconnect.toJsonElement(gson, res)
        assertEquals("text", el.asJsonObject["root"].asJsonObject["type"].asString)
    }

    @Test
    fun unknownRouteBecomesErrorObject() {
        val el = HydroInterconnect.toJsonElement(gson, null)
        assertEquals("error", el.asJsonObject["status"].asString)
    }

    @Test
    fun emptyBodyBecomesStatusObject() {
        // 204/空正文：手表侧需要一个对象，不能是空字符串
        val el = HydroInterconnect.toJsonElement(gson, HydroHttpResponse(204, "application/json", "", null))
        assertEquals("ok", el.asJsonObject["status"].asString)
        assertEquals(204, el.asJsonObject["code"].asInt)
    }

    @Test
    fun binaryResponseIsRejectedWithAHint() {
        // wasm/图片走不了互联通道：明确报错，而不是把二进制塞进 JSON
        val res = HydroHttpResponse(200, "application/wasm", "", ByteArray(8))
        val el = HydroInterconnect.toJsonElement(gson, res)
        assertEquals("error", el.asJsonObject["status"].asString)
        assertTrue(el.asJsonObject["message"].asString.contains("HTTP"))
    }

    @Test
    fun nonJsonBodyIsWrappedNotLost() {
        val res = HydroHttpResponse(404, "text/plain", "hydrokit: 未知插件包 \"com.nope\"", null)
        val el = HydroInterconnect.toJsonElement(gson, res)
        assertEquals(404, el.asJsonObject["code"].asInt)
        assertTrue(el.asJsonObject["body"].asString.contains("com.nope"))
    }

    /**
     * 端到端：互联通道报文 → 真路由 → 插件收到的 Request。
     * 这条用例覆盖的就是 v0.4.1 修的洞（此前 `/api/hydro/…` 在互联通道下 404）。
     */
    @Test
    fun interconnectReportReachesPluginWithStoreAndScreen() {
        val seen = arrayListOf<Request>()
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = Manifests.parse(
                """{"package":{"id":"com.demo.weather","name":"天气","version":"0.2.4"},
                    "apps":[{"id":"main","name":"天气"}]}""".trimIndent()
            )
            override fun view(surface: String, item: String, req: Request): View {
                seen.add(req)
                return View(root = Node.text("ok"))
            }
        })
        val routes = HydroKitRoutes(rt)

        val req = HydroInterconnect.toRequest(
            rawPath = "/api/hydro/com.demo.weather/app/main/view" +
                "?shape=circle&width=466&height=466&dpr=2&dt=watch" +
                "&store=%7B%22city%22%3A%22%E5%8C%97%E4%BA%AC%22%7D",
            method = "get",
            body = null,
        )
        val res = routes.handle(req) ?: error("路由未匹配：互联通道下应当命中 /api/hydro/…/view")
        assertEquals(200, res.status)
        assertEquals("circle", seen[0].screen.shape)
        assertEquals(466, seen[0].screen.width)
        assertEquals("watch", seen[0].screen.deviceType)
        assertEquals("北京", seen[0].store!!["city"])
    }
}
