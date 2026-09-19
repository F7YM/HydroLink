package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HydroKit HTTP 路由的用例，逐条对齐 desktop/internal/hydrokit/handler.go：
 * list、view/refresh/event 三种 trigger、屏幕查询参数、X-Hydro-Store、
 * 204（无需更新）、404（未知包/条目）、400（路径不合法）、webui 静态资源。
 */
class HydroKitRoutesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = PluginStore(File("/tmp/hydrokit-routes-test-placeholder"))

    private class FakePlugin(
        private val m: Manifest,
        private val responder: (Request) -> View?,
    ) : Runtime.Plugin {
        override fun manifest(): Manifest = m
        override fun view(surface: String, item: String, req: Request): View? = responder(req)
    }

    private val manifest = Manifests.parse(
        """
        {"package":{"id":"com.demo.weather","name":"示例天气", "author": "测试作者","version":"0.2.4"}, "author": "测试作者",
         "apps":[{"id":"main","name":"天气"}],
         "widgets":[{"id":"card","name":"卡片","refreshMs":30000}],
         "permissions":["fetch","storage"]}
        """.trimIndent()
    )

    private fun routes(responder: (Request) -> View?): Pair<HydroKitRoutes, MutableList<Request>> {
        val seen = mutableListOf<Request>()
        val rt = Runtime()
        rt.register(FakePlugin(manifest) { req ->
            seen.add(req)
            responder(req)
        })
        return HydroKitRoutes(rt, store) to seen
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()) =
        HydroHttpRequest(method = "GET", path = path, query = query)

    private fun post(
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ) = HydroHttpRequest(method = "POST", path = path, query = query, headers = headers, body = body)

    @Test
    fun listReturnsProtocolShape() {
        val (r, _) = routes { View(root = Node.text("x")) }
        val resp = r.handle(get("/api/hydro/list"))!!
        assertEquals(200, resp.status)
        val json = JsonParser.parseString(resp.body).asJsonObject
        val plugins = json.getAsJsonArray("plugins")
        assertEquals(1, plugins.size())
        val p0 = plugins[0].asJsonObject
        // 关键：字段名必须是 "package"（嵌套），与桌面端 json:"package" 对齐
        assertEquals("com.demo.weather", p0.getAsJsonObject("package").get("id").asString)
        assertEquals("示例天气", p0.getAsJsonObject("package").get("name").asString)
        assertEquals(1, p0.getAsJsonArray("apps").size())
        assertEquals(1, p0.getAsJsonArray("widgets").size())
    }

    @Test
    fun viewRequestReturnsTreeAndTriggerOpen() {
        val (r, seen) = routes { View(refreshMs = 30000, root = Node.text("hi")) }
        val resp = r.handle(post("/api/hydro/com.demo.weather/app/main/view"))!!
        assertEquals(200, resp.status)
        assertEquals("application/json", resp.contentType)
        assertEquals("no-store", resp.headers["Cache-Control"])
        val root = JsonParser.parseString(resp.body).asJsonObject.getAsJsonObject("root")
        assertEquals("text", root.get("type").asString)
        assertEquals("hi", root.getAsJsonObject("props").get("text").asString)
        assertEquals(Trigger.OPEN, seen[0].trigger)
    }

    @Test
    fun refreshAndEventSetTrigger() {
        val (r, seen) = routes { View(root = Node.text("x")) }
        r.handle(post("/api/hydro/com.demo.weather/widget/card/refresh"))
        assertEquals(Trigger.REFRESH, seen[0].trigger)

        r.handle(
            post(
                "/api/hydro/com.demo.weather/widget/card/event",
                body = """{"event":{"handler":"refresh","source":"tap"},"params":{"city":"北京"}}""",
            )
        )
        assertEquals(Trigger.EVENT, seen[1].trigger)
        assertEquals("refresh", seen[1].event!!.handler)
        assertEquals("tap", seen[1].event!!.source)
        assertEquals("北京", seen[1].params["city"])
    }

    @Test
    fun screenQueryParamsAreParsed() {
        val (r, seen) = routes { View(root = Node.text("x")) }
        r.handle(
            post(
                "/api/hydro/com.demo.weather/app/main/view",
                query = mapOf("shape" to "circle", "width" to "466", "height" to "466", "dpr" to "2", "dt" to "watch"),
            )
        )
        val s = seen[0].screen
        assertEquals("circle", s.shape)
        assertEquals(466, s.width)
        assertEquals(466, s.height)
        assertEquals(2.0, s.dpr, 0.001)
        assertEquals("watch", s.deviceType)
    }

    @Test
    fun storeHeaderBecomesRequestStore() {
        val (r, seen) = routes { View(root = Node.text("x")) }
        r.handle(
            post(
                "/api/hydro/com.demo.weather/app/main/view",
                headers = mapOf("x-hydro-store" to """{"city":"上海","n":3}"""),
            )
        )
        assertEquals("上海", seen[0].store!!["city"])
        assertEquals(3.0, seen[0].store!!["n"]) // JSON 数字读回是 Double
    }

    @Test
    fun invalidStoreHeaderIsTolerated() {
        val (r, seen) = routes { View(root = Node.text("x")) }
        r.handle(post("/api/hydro/com.demo.weather/app/main/view", headers = mapOf("x-hydro-store" to "{oops")))
        assertNull(seen[0].store)
    }

    @Test
    fun viewResultIsWrittenBackToDevice() {
        val (r, _) = routes { View(root = Node.text("x"), storage = mutableMapOf("city" to "北京")) }
        val resp = r.handle(post("/api/hydro/com.demo.weather/app/main/view"))!!
        val storage = JsonParser.parseString(resp.body).asJsonObject.getAsJsonObject("storage")
        assertEquals("北京", storage.get("city").asString)
    }

    @Test
    fun noChangeReturns204() {
        val (r, _) = routes { null }
        val resp = r.handle(post("/api/hydro/com.demo.weather/app/main/view"))!!
        assertEquals(204, resp.status)
        assertEquals("", resp.body)
    }

    @Test
    fun unknownPackageIs404() {
        val (r, _) = routes { View(root = Node.text("x")) }
        val resp = r.handle(post("/api/hydro/com.nope/app/main/view"))!!
        assertEquals(404, resp.status)
        assertTrue(resp.body.contains("未知插件包"))
    }

    @Test
    fun unknownItemIs404() {
        val (r, _) = routes { View(root = Node.text("x")) }
        val resp = r.handle(post("/api/hydro/com.demo.weather/app/nope/view"))!!
        assertEquals(404, resp.status)
    }

    @Test
    fun wrongSurfaceForItemIs404() {
        val (r, _) = routes { View(root = Node.text("x")) }
        // card 是 widget，用 app 表面请求应 404
        assertEquals(404, r.handle(post("/api/hydro/com.demo.weather/app/card/view"))!!.status)
    }

    @Test
    fun malformedPathIs400() {
        val (r, _) = routes { View(root = Node.text("x")) }
        assertEquals(400, r.handle(post("/api/hydro/com.demo.weather/app/view"))!!.status)
        assertEquals(400, r.handle(post("/api/hydro/"))!!.status)
    }

    @Test
    fun unsupportedMethodIs405() {
        val (r, _) = routes { View(root = Node.text("x")) }
        val resp = r.handle(HydroHttpRequest(method = "DELETE", path = "/api/hydro/list"))!!
        assertEquals(405, resp.status)
    }

    @Test
    fun nonHydroPathIsIgnored() {
        val (r, _) = routes { View(root = Node.text("x")) }
        assertNull(r.handle(get("/api/status")))
        assertNull(r.handle(get("/api/hydro/worker/poll"))) // worker 通道另有路由
    }

    @Test
    fun servesPluginWebUiAssets() {
        // 造一个带 web/ 的真实插件包并安装，验证 webui 路由与类型推断
        val root = tmp.newFolder("data")
        val st = PluginStore(root)
        val hpk = File(tmp.root, "w.hpk")
        ZipOutputStream(hpk.outputStream()).use { z ->
            listOf(
                "manifest.json" to """{"id":"com.demo.weather","name":"天气", "author": "测试作者","version":"1.0.0","apps":[{"id":"main"}]}""",
                "server.js" to "code",
                "web/index.html" to "<h1>设置</h1>",
                "web/app.js" to "console.log(1)",
            ).forEach { (p, c) ->
                z.putNextEntry(ZipEntry(p)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        st.install(hpk)
        val rt = Runtime()
        rt.register(FakePlugin(manifests("com.demo.weather")) { View(root = Node.text("x")) })
        val r = HydroKitRoutes(rt, st)

        val index = r.handle(get("/api/hydro/webui/com.demo.weather/"))!!
        assertEquals(200, index.status)
        assertEquals("text/html; charset=utf-8", index.contentType)
        assertTrue(index.body.contains("设置"))

        val js = r.handle(get("/api/hydro/webui/com.demo.weather/app.js"))!!
        assertEquals("application/javascript; charset=utf-8", js.contentType)

        // 路径逃逸必须失败
        assertEquals(404, r.handle(get("/api/hydro/webui/com.demo.weather/../../manifest.json"))!!.status)
        // 不存在的包
        assertEquals(404, r.handle(get("/api/hydro/webui/com.nope/index.html"))!!.status)
    }

    private fun manifests(id: String) = Manifests.parse(
        """{"package":{"id":"$id","name":"x", "author": "测试作者","version":"1.0.0"}, "author": "测试作者","apps":[{"id":"main"}]}"""
    )

    @Test
    fun contentTypeTable() {
        val r = HydroKitRoutes(Runtime())
        assertEquals("text/html; charset=utf-8", r.contentTypeFor("index.html"))
        assertEquals("application/javascript; charset=utf-8", r.contentTypeFor("a.js"))
        assertEquals("text/css; charset=utf-8", r.contentTypeFor("s.css"))
        assertEquals("image/png", r.contentTypeFor("i.png"))
        assertEquals("application/octet-stream", r.contentTypeFor("blob.bin"))
    }

    @Test
    fun emptyEventBodyStillDispatches() {
        val (r, seen) = routes { View(root = Node.text("x")) }
        val resp = r.handle(post("/api/hydro/com.demo.weather/app/main/event"))!!
        assertEquals(200, resp.status)
        assertEquals(Trigger.EVENT, seen[0].trigger)
        assertNull(seen[0].event)
    }

    @Test
    fun jsonObjectHelperBuildsObjects() {
        val o = jsonObjectOf("a" to 1, "b" to "x", "c" to true)
        assertNotNull(o.get("a"))
        assertEquals(1, o.get("a").asInt)
        assertEquals("x", o.get("b").asString)
        assertTrue(o.get("c").asBoolean)
    }

    /** 插件按平台分支要能拿到宿主 OS（桌面端同构，os=linux/windows/darwin）。 */
    @Test
    fun requestCarriesHostInfo() {
        val seen = arrayListOf<Request>()
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = Manifests.parse("""{"package":{"id":"com.x.host","version":"1.0.0"}, "author": "测试作者","apps":[{"id":"main"}]}""")
            override fun view(surface: String, item: String, req: Request): View {
                seen.add(req)
                return View(root = Node.text("ok"))
            }
        })
        val routes = HydroKitRoutes(rt, host = { mutableMapOf("os" to "android", "arch" to "arm64-v8a", "app" to "1.0.0", "sdk" to "36") })
        routes.handle(HydroHttpRequest("GET", "/api/hydro/com.x.host/app/main/view"))
        assertEquals("android", seen[0].host?.get("os"))
        assertEquals("arm64-v8a", seen[0].host?.get("arch"))
        assertEquals("36", seen[0].host?.get("sdk"))
    }

    /** 启动器要能直接取图标：包内相对路径解析成绝对 URL，绝对 URL 原样保留。 */
    @Test
    fun listResolvesItemIcons() {
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = Manifests.parse(
                """{"package":{"id":"com.x.icon","name":"图标", "author": "测试作者","version":"1.0.0"}, "author": "测试作者",
                    "apps":[{"id":"main","name":"带图标","icon":"assets/icon.png"},
                            {"id":"abs","name":"绝对","icon":"https://example.com/i.png"},
                            {"id":"none","name":"无"}]}""".trimIndent()
            )
            override fun view(surface: String, item: String, req: Request) = View(root = Node.text("x"))
        })
        val routes = HydroKitRoutes(rt, baseUrl = { "http://192.168.1.5:9000" })
        val res = routes.handle(HydroHttpRequest("GET", "/api/hydro/list"))!!
        val apps = com.google.gson.JsonParser.parseString(res.body).asJsonObject
            .getAsJsonArray("plugins")[0].asJsonObject.getAsJsonArray("apps")
        assertEquals(
            "http://192.168.1.5:9000/api/hydro/worker/asset?package=com.x.icon&path=assets%2Ficon.png",
            apps[0].asJsonObject["icon"].asString,
        )
        assertEquals("https://example.com/i.png", apps[1].asJsonObject["icon"].asString)
        assertTrue("没有图标就不该有 icon 字段", !apps[2].asJsonObject.has("icon"))
    }
}
