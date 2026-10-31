package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Worker 通道用例，对齐桌面端实现的行为：
 * 长轮询领取、respond 回填、worker 离线快速失败 + 唤醒、超时、错误回填。
 */
class WorkerBridgeTest {

    private fun bridge(
        timeoutMs: Long = 10_000,
        pollWindowMs: Long = 300,
        pollIntervalMs: Long = 20,
    ) = WorkerBridge(timeoutMs, pollWindowMs, pollIntervalMs)

    private fun poll(pkg: String) = HydroHttpRequest(
        method = "GET", path = "/api/hydro/worker/poll", query = mapOf("package" to pkg),
    )

    private fun respond(pkg: String, id: Long, result: String? = null, error: String? = null) =
        HydroHttpRequest(
            method = "POST", path = "/api/hydro/worker/respond",
            body = buildString {
                append("""{"package":"$pkg","id":$id""")
                if (result != null) append(""","result":$result""")
                if (error != null) append(""","error":"$error"""")
                append("}")
            },
        )

    @Test
    fun offlineWorkerFailsFastAndFiresWake() {
        val b = bridge()
        val woken = AtomicReference<String>()
        val latch = CountDownLatch(1)
        b.onNeedWake = { pkg -> woken.set(pkg); latch.countDown() }

        val err = runCatching { b.call("com.a.b", "view", "{}") }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("worker is offline"))
        assertTrue("应触发唤醒回调", latch.await(2, TimeUnit.SECONDS))
        assertEquals("com.a.b", woken.get())
    }

    @Test
    fun pollDeliversPendingRequestAndRespondCompletes() {
        val b = bridge()
        // 先让 worker 连上（一次空轮询即可建会话）
        b.handlePoll(poll("com.a.b"))
        assertTrue(b.isConnected("com.a.b"))

        val result = AtomicReference<String>()
        val caller = Thread {
            result.set(b.call("com.a.b", "view", """{"surface":"app","item":"main","req":{"trigger":"open"}}"""))
        }
        caller.start()

        val pollResp = b.handlePoll(poll("com.a.b"))
        assertEquals(200, pollResp.status)
        val json = JsonParser.parseString(pollResp.body).asJsonObject
        val req = json.getAsJsonObject("req")
        val id = req.get("id").asLong
        assertEquals("app", req.get("surface").asString)
        assertEquals("main", req.get("item").asString)
        assertEquals("open", req.getAsJsonObject("req").get("trigger").asString)

        val resp = b.handleRespond(respond("com.a.b", id, result = """{"refreshMs":30000}"""))
        assertEquals("""{"status":"ok"}""", resp.body)

        caller.join(3000)
        assertEquals("""{"refreshMs":30000}""", result.get())
    }

    @Test
    fun emptyPollReturnsEmptyObject() {
        val b = bridge(pollWindowMs = 120, pollIntervalMs = 20)
        val resp = b.handlePoll(poll("com.a.b"))
        assertEquals(200, resp.status)
        assertEquals("{}", resp.body)
    }

    @Test
    fun respondUnknownPackageOrRequest() {
        val b = bridge()
        assertEquals("""{"status":"unknown_package"}""", b.handleRespond(respond("com.nope", 1)).body)
        b.handlePoll(poll("com.a.b"))
        assertEquals("""{"status":"unknown_req"}""", b.handleRespond(respond("com.a.b", 99)).body)
    }

    @Test
    fun workerErrorPropagatesToCaller() {
        val b = bridge()
        b.handlePoll(poll("com.a.b"))
        val holder = AtomicReference<Throwable?>()
        val caller = Thread {
            holder.set(runCatching { b.call("com.a.b", "view", "{}") }.exceptionOrNull())
        }
        caller.start()

        val pollResp = b.handlePoll(poll("com.a.b"))
        val id = JsonParser.parseString(pollResp.body).asJsonObject.getAsJsonObject("req").get("id").asLong
        b.handleRespond(respond("com.a.b", id, error = "boom"))
        caller.join(3000)
        assertNotNull(holder.get())
        assertTrue(holder.get()!!.message!!.contains("boom"))
    }

    @Test
    fun callTimesOutWhenWorkerNeverAnswers() {
        val b = bridge(timeoutMs = 150, pollWindowMs = 50)
        b.handlePoll(poll("com.a.b"))
        val err = runCatching { b.call("com.a.b", "view", "{}") }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("超时"))
    }

    @Test
    fun invalidPollIsRejected() {
        val b = bridge()
        assertEquals(400, b.handlePoll(HydroHttpRequest("GET", "/api/hydro/worker/poll")).status)
        assertEquals(
            400,
            b.handlePoll(
                HydroHttpRequest("GET", "/api/hydro/worker/poll", query = mapOf("package" to "bad/../id"))
            ).status,
        )
    }

    @Test
    fun invalidRespondBodyIsRejected() {
        val b = bridge()
        assertEquals(400, b.handleRespond(HydroHttpRequest("POST", "/api/hydro/worker/respond")).status)
        assertEquals(
            400,
            b.handleRespond(HydroHttpRequest("POST", "/api/hydro/worker/respond", body = "{oops")).status,
        )
        assertEquals(
            400,
            b.handleRespond(HydroHttpRequest("POST", "/api/hydro/worker/respond", body = "[1,2]")).status,
        )
    }

    @Test
    fun disconnectRemovesSession() {
        val b = bridge()
        b.handlePoll(poll("com.a.b"))
        assertTrue(b.isConnected("com.a.b"))
        b.disconnect("com.a.b")
        assertFalse(b.isConnected("com.a.b"))
        assertEquals(emptySet<String>(), b.connectedPackages())
    }

    @Test
    fun packageIdValidation() {
        assertTrue(WorkerBridge.validPackageId("com.demo.weather"))
        assertTrue(WorkerBridge.validPackageId("a-b_c.1"))
        assertFalse(WorkerBridge.validPackageId(""))
        assertFalse(WorkerBridge.validPackageId("中文"))
        assertFalse(WorkerBridge.validPackageId("../evil"))
        assertFalse(WorkerBridge.validPackageId("a".repeat(129)))
    }

    @Test
    fun routesDispatchPollAndRespond() {
        val b = bridge()
        val routes = WorkerRoutes(b)
        assertEquals(200, routes.handle(poll("com.a.b"))!!.status)
        assertNotNull(routes.handle(respond("com.a.b", 1)))
        assertEquals(404, routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/nope"))!!.status)
        // 非 worker 路径由本类忽略
        assertEquals(null, routes.handle(HydroHttpRequest("GET", "/api/hydro/list")))
    }
}
