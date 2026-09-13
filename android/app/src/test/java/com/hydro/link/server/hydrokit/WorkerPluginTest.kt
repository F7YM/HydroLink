package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * WorkerPlugin：把插件的 view 调用经 WorkerBridge 投给 worker，
 * 再把 worker 回填的 JSON 解析成 View（对应桌面端 JsPlugin 的行为）。
 */
class WorkerPluginTest {

    private val manifest = Manifests.parse(
        """
        {"package":{"id":"com.demo.weather","name":"天气","version":"1.0.0"},
         "apps":[{"id":"main","name":"天气"}],"widgets":[{"id":"card","refreshMs":30000}]}
        """.trimIndent()
    )

    private fun bridge() = WorkerBridge(requestTimeoutMs = 2000, pollWindowMs = 200, pollIntervalMs = 20)

    /** 起一个"假 worker"：领一次请求，按 responder 回填，返回 payload 供断言。 */
    private fun fakeWorker(
        b: WorkerBridge,
        pkg: String,
        responder: (String) -> String,
    ): AtomicReference<String> {
        val seen = AtomicReference<String>()
        val t = Thread {
            val poll = b.handlePoll(
                HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to pkg))
            )
            val req = JsonParser.parseString(poll.body).asJsonObject.getAsJsonObject("req")
            val id = req.get("id").asLong
            seen.set(req.toString())
            val result = responder(req.toString())
            b.handleRespond(
                HydroHttpRequest(
                    "POST", "/api/hydro/worker/respond",
                    body = """{"package":"$pkg","id":$id,"result":$result}""",
                )
            )
        }
        t.isDaemon = true
        t.start()
        return seen
    }

    @Test
    fun viewRoundTripDeliversRequestAndParsesView() {
        val b = bridge()
        b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))
        val seen = fakeWorker(b, "com.demo.weather") { """{"refreshMs":30000,"root":{"type":"column"}}""" }

        val plugin = WorkerPlugin("com.demo.weather", manifest, b)
        val view = plugin.view(
            Surface.WIDGET, "card",
            Request(
                trigger = Trigger.OPEN,
                screen = Screen(shape = "circle", width = 466, height = 466, dpr = 2.0),
                store = mutableMapOf("city" to "北京"),
            ),
        )
        assertNotNull(view)
        assertEquals(30000, view!!.refreshMs)
        assertEquals("column", view.root.type)

        val payload = seen.get()
        // 投递给 worker 的 req 三元组里的 surface/item 必须与请求一致
        assertTrue("payload 缺少 surface: $payload", payload.contains("\"surface\":\"widget\""))
        assertTrue("payload 缺少 item: $payload", payload.contains("\"item\":\"card\""))
        assertTrue(payload.contains("\"trigger\":\"open\""))
        assertTrue(payload.contains("\"shape\":\"circle\""))
        assertTrue(payload.contains("北京"))
    }

    @Test
    fun acceptsWrappedViewEnvelope() {
        val b = bridge()
        b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))
        fakeWorker(b, "com.demo.weather") { """{"ok":true,"view":{"root":{"type":"text","props":{"text":"hi"}}}}""" }
        val view = WorkerPlugin("com.demo.weather", manifest, b)
            .view(Surface.APP, "main", Request(trigger = Trigger.OPEN))
        assertEquals("text", view!!.root.type)
        assertEquals("hi", view.root.props!!["text"])
    }

    @Test
    fun emptyResultMeansNoChange() {
        val b = bridge()
        b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))
        fakeWorker(b, "com.demo.weather") { """{}""" }
        assertNull(
            WorkerPlugin("com.demo.weather", manifest, b)
                .view(Surface.APP, "main", Request(trigger = Trigger.TIMER))
        )
    }

    @Test
    fun offlineWorkerSurfacesClearError() {
        val b = bridge()
        val err = runCatching {
            WorkerPlugin("com.demo.weather", manifest, b)
                .view(Surface.APP, "main", Request(trigger = Trigger.OPEN))
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("offline"))
    }

    @Test
    fun brokenJsonFromWorkerIsReported() {
        val b = bridge()
        b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))
        fakeWorker(b, "com.demo.weather") { "\"just-a-string\"" }
        val err = runCatching {
            WorkerPlugin("com.demo.weather", manifest, b)
                .view(Surface.APP, "main", Request(trigger = Trigger.OPEN))
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("不是对象"))
    }

    @Test
    fun runtimeDispatchThroughWorkerPlugin() {
        // 端到端（进程内）：Runtime → WorkerPlugin → Bridge → 假 worker → View
        val b = bridge()
        b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "com.demo.weather")))
        fakeWorker(b, "com.demo.weather") { """{"root":{"type":"text","props":{"text":"来自 worker"}}}""" }
        val rt = Runtime()
        rt.register(WorkerPlugin("com.demo.weather", manifest, b))
        val view = rt.dispatch("com.demo.weather", Surface.APP, "main", Request(trigger = Trigger.OPEN))
        assertEquals("来自 worker", view!!.root.props!!["text"])
    }
}
