package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨端协议契约：这些字段是**手表容器（vela/src/pages/hydro/hydro.ux）真的会读的**，
 * 也是桌面端 Go 实现同样产出的。任何一边改名都会静默破坏手表界面，所以在这里钉死。
 *
 * 契约来源（逐条对着代码核过）：
 *   · 容器读 view.root / view.refreshMs / view.storage          hydro.ux:395,434,439
 *   · 节点字段 type/style/props/events/media                     hydro.ux:200-230
 *   · 请求带 X-Hydro-Store 头 + ?shape&width&height&dpr&dt&_=seq  hydro.ux:305,380-386
 *   · 事件体 {event:{handler,source,value}, params:{…}}          hydro.ux:354,475,495
 *   · 列表 {plugins:[{package:{id,name,version},apps,widgets}]}   桌面 handler.go + 安卓 HydroKitRoutes
 */
class ProtocolContractTest {

    private val manifest = Manifests.parse(
        """
        {"package":{"id":"com.demo.weather","name":"示例天气","version":"0.2.4"},
         "apps":[{"id":"main","name":"天气"}],
         "widgets":[{"id":"card","name":"卡片","refreshMs":30000}]}
        """.trimIndent()
    )

    private fun routes(view: View?): HydroKitRoutes {
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = manifest
            override fun view(surface: String, item: String, req: Request) = view
        })
        return HydroKitRoutes(rt)
    }

    @Test
    fun viewResponseCarriesContainerFields() {
        val v = View(
            refreshMs = 30000,
            root = Node.column(
                Node.text("hi").style("fontSize", 28),
                Node.button("刷新").on("tap", "refresh"),
                Node(NodeType.SWITCH, props = mutableMapOf("checked" to true)).on("change", "toggle"),
                Node(NodeType.INPUT, props = mutableMapOf("value" to "北京")).on("change", "city"),
            ).style("padding", 16),
            storage = mutableMapOf("city" to "北京"),
        )
        val resp = routes(v).handle(HydroHttpRequest("POST", "/api/hydro/com.demo.weather/app/main/view"))!!
        assertEquals(200, resp.status)
        val obj = JsonParser.parseString(resp.body).asJsonObject

        // 容器直接读的三个顶层字段
        assertTrue("缺少 root", obj.has("root"))
        assertEquals(30000, obj.get("refreshMs").asInt)
        assertEquals("北京", obj.getAsJsonObject("storage").get("city").asString)

        val root = obj.getAsJsonObject("root")
        assertEquals("column", root.get("type").asString)
        assertEquals(16, root.getAsJsonObject("style").get("padding").asInt)

        val kids = root.getAsJsonArray("children")
        assertEquals("text", kids[0].asJsonObject.get("type").asString)
        assertEquals("hi", kids[0].asJsonObject.getAsJsonObject("props").get("text").asString)
        // 事件键就是容器 events.tap / events.change 的来源
        assertEquals("refresh", kids[1].asJsonObject.getAsJsonObject("events").get("tap").asString)
        assertEquals("toggle", kids[2].asJsonObject.getAsJsonObject("events").get("change").asString)
        assertEquals("city", kids[3].asJsonObject.getAsJsonObject("events").get("change").asString)
    }

    @Test
    fun mediaOverridesSurviveSerialization() {
        val v = View(root = Node.text("x").style("fontSize", 28).media("circle", "fontSize" to 26))
        val resp = routes(v).handle(HydroHttpRequest("POST", "/api/hydro/com.demo.weather/app/main/view"))!!
        val root = JsonParser.parseString(resp.body).asJsonObject.getAsJsonObject("root")
        // 容器按 media[shape] 覆盖 base style
        assertEquals(26, root.getAsJsonObject("media").getAsJsonObject("circle").get("fontSize").asInt)
    }

    @Test
    fun listResponseMatchesLauncherShape() {
        val resp = routes(View(root = Node.text("x"))).handle(HydroHttpRequest("GET", "/api/hydro/list"))!!
        val p0 = JsonParser.parseString(resp.body).asJsonObject.getAsJsonArray("plugins")[0].asJsonObject
        assertNotNull("启动器读 package.id/name/version", p0.getAsJsonObject("package"))
        assertEquals("com.demo.weather", p0.getAsJsonObject("package").get("id").asString)
        assertEquals("示例天气", p0.getAsJsonObject("package").get("name").asString)
        assertEquals("0.2.4", p0.getAsJsonObject("package").get("version").asString)
        assertEquals("main", p0.getAsJsonArray("apps")[0].asJsonObject.get("id").asString)
        assertEquals("card", p0.getAsJsonArray("widgets")[0].asJsonObject.get("id").asString)
        assertEquals(30000, p0.getAsJsonArray("widgets")[0].asJsonObject.get("refreshMs").asInt)
    }

    @Test
    fun eventBodyShapeMatchesContainer() {
        // 容器发的是 {event:{handler,source,value}}（可能还有 params）
        val seen = arrayListOf<Request>()
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = manifest
            override fun view(surface: String, item: String, req: Request): View {
                seen.add(req)
                return View(root = Node.text("ok"))
            }
        })
        val routes = HydroKitRoutes(rt)
        routes.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/com.demo.weather/app/main/event",
                body = """{"event":{"handler":"city","source":"change","value":"上海"}}""",
            )
        )
        assertEquals(Trigger.EVENT, seen[0].trigger)
        assertEquals("city", seen[0].event!!.handler)
        assertEquals("change", seen[0].event!!.source)
        assertEquals("上海", seen[0].event!!.value)
    }

    @Test
    fun workerMessagesMatchDesktopShape() {
        // 桌面 bridge.go 与安卓 WorkerBridge 的报文必须一致，否则同一份胶水不能双端跑
        val bridge = WorkerBridge(requestTimeoutMs = 2000, pollWindowMs = 200, pollIntervalMs = 20)
        bridge.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))

        val respondBody = java.util.concurrent.atomic.AtomicReference<String>()
        val pollThread = Thread {
            val poll = bridge.handlePoll(
                HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather"))
            )
            val req = JsonParser.parseString(poll.body).asJsonObject.getAsJsonObject("req")
            val id = req.get("id").asLong
            respondBody.set(
                bridge.handleRespond(
                    HydroHttpRequest(
                        "POST", "/api/hydro/worker/respond",
                        body = """{"package":"com.demo.weather","id":$id,"result":{"root":{"type":"text","props":{"text":"hi"}}}}""",
                    )
                ).body
            )
        }
        pollThread.isDaemon = true
        pollThread.start()

        val out = bridge.call("com.demo.weather", "view", """{"surface":"app","item":"main","req":{"trigger":"open"}}""")
        pollThread.join(3000)
        val view = JsonParser.parseString(out).asJsonObject
        assertEquals("text", view.getAsJsonObject("root").get("type").asString)
        assertEquals("""{"status":"ok"}""", respondBody.get())

        // 契约里的两种"没找到"回执（桌面端相同）
        assertEquals(
            """{"status":"unknown_req"}""",
            bridge.handleRespond(HydroHttpRequest("POST", "/api/hydro/worker/respond", body = """{"package":"com.demo.weather","id":999}""")).body,
        )
        assertEquals(
            """{"status":"unknown_package"}""",
            bridge.handleRespond(HydroHttpRequest("POST", "/api/hydro/worker/respond", body = """{"package":"com.nope","id":1}""")).body,
        )
    }

    @Test
    fun storeCanArriveViaQueryOrBodyForInterconnect() {
        val seen = arrayListOf<Request>()
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = manifest
            override fun view(surface: String, item: String, req: Request): View {
                seen.add(req)
                return View(root = Node.text("x"))
            }
        })
        val routes = HydroKitRoutes(rt)

        // 互联通道的 GET：没有 X-Hydro-Store 头，store 走 query
        routes.handle(
            HydroHttpRequest(
                "GET", "/api/hydro/com.demo.weather/widget/card/view",
                query = mapOf("store" to """{"city":"北京"}"""),
            )
        )
        assertEquals("北京", seen[0].store!!["city"])

        // 互联通道的 POST（event）：store 放 body
        routes.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/com.demo.weather/app/main/event",
                body = """{"event":{"handler":"refresh"},"store":{"city":"上海"}}""",
            )
        )
        assertEquals("上海", seen[1].store!!["city"])
        assertEquals("refresh", seen[1].event!!.handler)
    }

    @Test
    fun screenQueryAndStoreHeaderAreHonoured() {
        val seen = arrayListOf<Request>()
        val rt = Runtime()
        rt.register(object : Runtime.Plugin {
            override fun manifest() = manifest
            override fun view(surface: String, item: String, req: Request): View {
                seen.add(req)
                return View(root = Node.text("x"))
            }
        })
        HydroKitRoutes(rt).handle(
            HydroHttpRequest(
                "GET", "/api/hydro/com.demo.weather/widget/card/view",
                query = mapOf("shape" to "circle", "width" to "466", "height" to "466", "dpr" to "2", "dt" to "watch", "_" to "7"),
                headers = mapOf("x-hydro-store" to """{"city":"北京"}"""),
            )
        )
        assertEquals("circle", seen[0].screen.shape)
        assertEquals(466, seen[0].screen.width)
        assertEquals(2.0, seen[0].screen.dpr, 0.001)
        assertEquals("watch", seen[0].screen.deviceType)
        assertEquals("北京", seen[0].store!!["city"])
        // "_" 只是缓存破坏参数，不应影响解析
        assertEquals(Trigger.OPEN, seen[0].trigger)
    }
}
