package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * kit 能力端点：权限门控（安全相关）、kit.storage 语义、ABI 上下文形状、
 * 资产端点（含二进制 wasm 与路径逃逸）、以及各能力端点的成功/失败分支。
 */
class KitRoutesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 记录调用的假能力实现。 */
    private class FakeCaps : KitCapabilities {
        var lastNotify: Pair<String, String>? = null
        var lastMusicAction: String? = null
        var locked = false
        var ranIndex = -1
        // 可切换的失败开关：验证统一后的失败回包（500 {"error":…}）
        var notifyResult = true
        var musicActionResult = true
        var lockResult = true
        var lastWolWake: Triple<String, String?, Int?>? = null
        var lastWolProbe: Triple<String, Int?, Int?>? = null
        var lastUdpSend: Triple<String, Int, ByteArray>? = null
        var wolResult = true
        var udpResult = true
        override fun perf() = mapOf<String, Any?>("cpu" to 12.5, "memPercent" to 63.2, "memUsedMB" to 4096, "memTotalMB" to 8192)
        override fun music() = mapOf<String, Any?>("hasMedia" to true, "title" to "歌", "artist" to "手", "playing" to true, "artUrl" to "file:///tmp/a.jpg")
        override fun musicAction(action: String): Boolean { lastMusicAction = action; return musicActionResult }
        override fun notify(title: String, text: String): Boolean { lastNotify = title to text; return notifyResult }
        override fun lock(): Boolean { if (lockResult) locked = true; return lockResult }
        override fun commandList() = listOf<Map<String, Any?>>(mapOf("id" to 0, "name" to "hello", "command" to "echo hi"))
        override fun runCommand(index: Int): Map<String, Any?> { ranIndex = index; return mapOf("success" to true, "exitCode" to 0, "output" to "hi") }
        override fun wolWake(mac: String, host: String?, port: Int?): Map<String, Any?> {
            lastWolWake = Triple(mac, host, port)
            return if (wolResult) mapOf("ok" to true) else mapOf("error" to "WOL 失败")
        }
        override fun wolProbe(host: String, port: Int?, timeout: Int?): Map<String, Any?> {
            lastWolProbe = Triple(host, port, timeout)
            return mapOf("online" to true, "rttMs" to 15)
        }
        override fun udpSend(host: String, port: Int, data: ByteArray, broadcast: Boolean): Map<String, Any?> {
            lastUdpSend = Triple(host, port, data)
            return if (udpResult) mapOf("ok" to true, "bytesSent" to data.size) else mapOf("error" to "UDP 发送失败")
        }
        override fun serverBase() = "http://192.168.1.5:9000"
    }

    private class Fixture(val store: PluginStore, val routes: KitRoutes, val caps: FakeCaps)

    /** 装一个带指定权限的插件。 */
    private fun fixture(permissions: List<String>, runtime: String = "js", entry: String = "server.js"): Fixture {
        val root = tmp.newFolder("data-${System.nanoTime()}")
        val st = PluginStore(root)
        val hpk = File(tmp.root, "p-${System.nanoTime()}.hpk")
        val perms = permissions.joinToString(",") { "\"$it\"" }
        val manifest = """
            {"id":"com.demo.weather","name":"天气", "author": "测试作者","version":"1.0.0","runtime":"$runtime","entry":"$entry",
             "apps":[{"id":"main"}],"permissions":[$perms]}
        """.trimIndent()
        ZipOutputStream(hpk.outputStream()).use { z ->
            mapOf(
                "manifest.json" to manifest,
                "server.js" to "// code",
                "plugin.wasm" to "\u0000asm\u0001\u0000\u0000\u0000",
            ).forEach { (p, c) ->
                z.putNextEntry(ZipEntry(p)); z.write(c.toByteArray(Charsets.ISO_8859_1)); z.closeEntry()
            }
        }
        st.install(hpk)
        val caps = FakeCaps()
        return Fixture(st, KitRoutes(st, WorkerBridge(), caps), caps)
    }

    private fun post(path: String, body: String) = HydroHttpRequest("POST", path, body = body)
    private fun get(path: String, query: Map<String, String>) = HydroHttpRequest("GET", path, query = query)
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    // ---- 权限门控 ----

    @Test
    fun storageRequiresPermission() {
        val f = fixture(emptyList())
        val resp = f.routes.handle(post("/api/hydro/worker/store", """{"package":"com.demo.weather","op":"get","key":"k"}"""))!!
        assertEquals(403, resp.status)
        assertTrue(resp.body.contains("权限不足"))
    }

    @Test
    fun eachCapabilityIsGated() {
        val f = fixture(emptyList())
        val cases = listOf(
            "/api/hydro/worker/notify" to """{"package":"com.demo.weather","title":"t","text":"x"}""",
            "/api/hydro/worker/music" to """{"package":"com.demo.weather","action":"playpause"}""",
            "/api/hydro/worker/lock" to """{"package":"com.demo.weather"}""",
            "/api/hydro/worker/commands" to """{"package":"com.demo.weather","op":"list"}""",
            "/api/hydro/worker/system" to """{"package":"com.demo.weather","op":"perf"}""",
        )
        for ((path, body) in cases) {
            assertEquals("$path 应 403", 403, f.routes.handle(post(path, body))!!.status)
        }
    }

    @Test
    fun unknownPluginIsForbidden() {
        val f = fixture(listOf("storage"))
        val resp = f.routes.handle(post("/api/hydro/worker/store", """{"package":"com.nope","op":"get","key":"k"}"""))!!
        assertEquals(403, resp.status)
        assertTrue(resp.body.contains("plugin 不存在"))
    }

    // ---- 宿主平台信息 ----

    @Test
    fun platformEndpointReportsTheHostOS() {
        // 默认夹具没注入宿主信息：明确报错，而不是回一个空对象让插件去猜
        val bare = fixture(emptyList())
        assertEquals(503, bare.routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/platform"))!!.status)

        val routes = KitRoutes(
            bare.store, WorkerBridge(), FakeCaps(), {},
            { mapOf("os" to "android", "arch" to "arm64-v8a", "app" to "1.1.0") },
        )
        val resp = routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/platform"))!!
        assertEquals(200, resp.status)
        val obj = json(resp.body)
        assertEquals("android", obj["os"].asString)
        assertEquals("arm64-v8a", obj["arch"].asString)
        assertEquals("1.1.0", obj["app"].asString)
    }

    // ---- 能力端点成功分支 ----

    @Test
    fun capabilitiesWorkWhenPermitted() {
        val f = fixture(listOf("storage", "notify", "music", "lock", "commands", "system"))
        val notifyResp = f.routes.handle(post("/api/hydro/worker/notify", """{"package":"com.demo.weather","title":"标题","text":"正文"}"""))!!
        assertEquals(200, notifyResp.status)
        assertTrue(json(notifyResp.body).get("ok").asBoolean)
        assertEquals("标题" to "正文", f.caps.lastNotify)

        val musicResp = f.routes.handle(post("/api/hydro/worker/music", """{"package":"com.demo.weather","action":"next"}"""))!!
        assertEquals("next", f.caps.lastMusicAction)
        val musicBody = json(musicResp.body)
        assertTrue(musicBody.get("ok").asBoolean)
        assertEquals("next", musicBody.get("action").asString)

        assertTrue(json(f.routes.handle(post("/api/hydro/worker/lock", """{"package":"com.demo.weather"}"""))!!.body).get("ok").asBoolean)
        assertTrue(f.caps.locked)

        val list = json(f.routes.handle(post("/api/hydro/worker/commands", """{"package":"com.demo.weather","op":"list"}"""))!!.body)
        assertEquals(1, list.getAsJsonArray("commands").size())

        val runResp = f.routes.handle(post("/api/hydro/worker/commands", """{"package":"com.demo.weather","op":"run","index":0}"""))!!
        assertEquals(200, runResp.status)
        assertEquals(0, f.caps.ranIndex)
        val runBody = json(runResp.body)
        assertEquals(0, runBody.get("returncode").asInt)
        assertTrue(runBody.has("output"))
        // 序号越界：400（统一后在路由层就挡住，不再落到能力层）
        assertEquals(400, f.routes.handle(post("/api/hydro/worker/commands", """{"package":"com.demo.weather","op":"run","index":2}"""))!!.status)

        val perf = json(f.routes.handle(post("/api/hydro/worker/system", """{"package":"com.demo.weather","op":"perf"}"""))!!.body)
        assertEquals(12.5, perf.get("cpu").asDouble, 0.01)

        // system op=music：与桌面一致，返回媒体信息而不是 400
        val media = json(f.routes.handle(post("/api/hydro/worker/system", """{"package":"com.demo.weather","op":"music"}"""))!!.body)
        assertTrue(media.get("hasMedia").asBoolean)
        assertEquals("歌", media.get("title").asString)
    }

    // ---- 统一后的失败回包（非 2xx + {"error":…}，与桌面一致）----

    @Test
    fun capabilityFailuresAreServerError() {
        val f = fixture(listOf("notify", "music", "lock"))

        f.caps.notifyResult = false
        val notify = f.routes.handle(post("/api/hydro/worker/notify", """{"package":"com.demo.weather","title":"t","text":"x"}"""))!!
        assertEquals(500, notify.status)
        assertTrue(notify.body.contains("通知失败"))

        f.caps.musicActionResult = false
        val music = f.routes.handle(post("/api/hydro/worker/music", """{"package":"com.demo.weather","action":"play"}"""))!!
        assertEquals(500, music.status)
        assertTrue(music.body.contains("没有活动的媒体会话"))

        f.caps.lockResult = false
        val lock = f.routes.handle(post("/api/hydro/worker/lock", """{"package":"com.demo.weather"}"""))!!
        assertEquals(500, lock.status)
        assertTrue(lock.body.contains("锁屏失败"))
    }

    @Test
    fun musicRejectsUnknownAction() {
        val f = fixture(listOf("music"))
        val resp = f.routes.handle(post("/api/hydro/worker/music", """{"package":"com.demo.weather","action":"nonsense"}"""))!!
        assertEquals(400, resp.status)
        assertTrue(resp.body.contains("无效动作"))
        assertEquals(null, f.caps.lastMusicAction)
    }

    // ---- kit.storage ----

    @Test
    fun storageCrudThroughRoutes() {
        val f = fixture(listOf("storage"))
        val pkg = "com.demo.weather"

        assertEquals(200, f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"set","key":"city","value":"北京"}"""))!!.status)
        val got = json(f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"get","key":"city"}"""))!!.body)
        assertTrue(got.get("found").asBoolean)
        assertEquals("北京", got.get("value").asString)

        val missing = json(f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"get","key":"nope"}"""))!!.body)
        assertTrue(!missing.get("found").asBoolean)
        // 与桌面一致：未命中也回 value:null
        assertTrue(missing.has("value") && missing.get("value").isJsonNull)

        f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"remove","key":"city"}"""))
        assertTrue(!json(f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"get","key":"city"}"""))!!.body).get("found").asBoolean)

        f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"set","key":"a","value":1}"""))
        f.routes.handle(post("/api/hydro/worker/store", """{"package":"$pkg","op":"clear"}"""))
        assertEquals(0, f.store.storeAll(pkg).size)
    }

    @Test
    fun watchScopeIsRejectedServerSide() {
        val f = fixture(listOf("storage"))
        val resp = f.routes.handle(post("/api/hydro/worker/store", """{"package":"com.demo.weather","op":"set","key":"k","value":1,"scope":"watch"}"""))!!
        assertEquals(400, resp.status)
    }

    // ---- ABI 上下文 ----

    @Test
    fun abiContextIsPermissionGated() {
        val f = fixture(listOf("system", "music", "storage"))
        f.store.storeSet("com.demo.weather", "city", "上海")
        val ctx = json(
            f.routes.handle(
                post("/api/hydro/worker/context", """{"package":"com.demo.weather","surface":"app","item":"main","req":{"trigger":"open"}}""")
            )!!.body
        )
        assertEquals(1, ctx.get("abi").asInt)
        assertEquals("com.demo.weather", ctx.get("package").asString)
        assertEquals("app", ctx.get("surface").asString)
        assertEquals("http://192.168.1.5:9000", ctx.getAsJsonObject("server").get("base").asString)
        assertEquals("open", ctx.getAsJsonObject("req").get("trigger").asString)
        assertNotNull(ctx.getAsJsonObject("system"))
        assertNotNull(ctx.getAsJsonObject("music"))
        assertEquals("上海", ctx.getAsJsonObject("storage").get("city").asString)
        assertNotNull(ctx.getAsJsonObject("time").get("unix"))
    }

    @Test
    fun abiContextOmitsUnpermittedSections() {
        val f = fixture(listOf("storage"))
        val ctx = json(f.routes.handle(post("/api/hydro/worker/context", """{"package":"com.demo.weather"}"""))!!.body)
        assertEquals(null, ctx.get("system"))
        assertEquals(null, ctx.get("music"))
        assertNotNull(ctx.get("storage"))
    }

    // ---- 资产（worker 拉取入口 / wasm）----

    @Test
    fun assetServesTextAndBinary() {
        val f = fixture(listOf("storage"))
        val js = f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather", "path" to "server.js")))!!
        assertEquals(200, js.status)
        assertEquals("application/javascript; charset=utf-8", js.contentType)
        assertEquals("// code", js.body)

        val wasm = f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather", "path" to "plugin.wasm")))!!
        assertEquals(200, wasm.status)
        assertEquals("application/wasm", wasm.contentType)
        assertNotNull("wasm 必须以字节返回（不能被文本编码破坏）", wasm.bytes)
        assertEquals(8, wasm.bytes!!.size)
        // wasm magic：\0asm
        assertEquals(0x00, wasm.bytes!![0].toInt())
        assertEquals("asm", String(wasm.bytes!!, 1, 3, Charsets.US_ASCII))
    }

    @Test
    fun assetRejectsEscapeAndUnknown() {
        val f = fixture(listOf("storage"))
        assertEquals(400, f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather", "path" to "../manifest.json")))!!.status)
        assertEquals(400, f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather", "path" to "/etc/passwd")))!!.status)
        assertEquals(400, f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather")))!!.status)
        assertEquals(404, f.routes.handle(get("/api/hydro/worker/asset", mapOf("package" to "com.demo.weather", "path" to "nope.js")))!!.status)
    }

    // ---- 路由分派 ----

    @Test
    fun unknownWorkerEndpointIs404AndOtherPathsIgnored() {
        val f = fixture(listOf("storage"))
        assertEquals(404, f.routes.handle(post("/api/hydro/worker/nope", "{}"))!!.status)
        assertEquals(null, f.routes.handle(get("/api/hydro/list", emptyMap())))
        assertEquals(null, f.routes.handle(get("/api/status", emptyMap())))
    }

    @Test
    fun badJsonBodiesAreRejected() {
        val f = fixture(listOf("storage"))
        assertEquals(400, f.routes.handle(post("/api/hydro/worker/store", "{oops"))!!.status)
        assertEquals(400, f.routes.handle(post("/api/hydro/worker/system", "not json"))!!.status)
        assertEquals(405, f.routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/store"))!!.status)
    }

    // ---- wol & udp 能力 ----

    @Test
    fun wolPermissionGateAndWake() {
        val fNoPerm = fixture(listOf("storage"))
        val unauth = fNoPerm.routes.handle(post("/api/hydro/worker/wol/wake", """{"package":"com.demo.weather","mac":"AA:BB:CC:DD:EE:FF"}"""))!!
        assertEquals(403, unauth.status)
        assertTrue(unauth.body.contains("权限不足: wol"))

        val f = fixture(listOf("wol"))
        // 非法 MAC 校验
        val badMac = f.routes.handle(post("/api/hydro/worker/wol/wake", """{"package":"com.demo.weather","mac":"invalid-mac"}"""))!!
        assertEquals(400, badMac.status)
        assertTrue(badMac.body.contains("无效的 MAC 地址"))

        // 非法端口校验
        val badPort = f.routes.handle(post("/api/hydro/worker/wol/wake", """{"package":"com.demo.weather","mac":"AA:BB:CC:DD:EE:FF","port":99999}"""))!!
        assertEquals(400, badPort.status)
        assertTrue(badPort.body.contains("无效端口"))

        // 成功唤醒
        val ok = f.routes.handle(post("/api/hydro/worker/wol/wake", """{"package":"com.demo.weather","mac":"AA:BB:CC:DD:EE:FF","host":"192.168.1.255","port":9}"""))!!
        assertEquals(200, ok.status)
        assertEquals(Triple("AA:BB:CC:DD:EE:FF", "192.168.1.255", 9), f.caps.lastWolWake)

        // 失败回包
        f.caps.wolResult = false
        val fail = f.routes.handle(post("/api/hydro/worker/wol/wake", """{"package":"com.demo.weather","mac":"AA-BB-CC-DD-EE-FF"}"""))!!
        assertEquals(500, fail.status)
        assertTrue(fail.body.contains("WOL 失败"))
    }

    @Test
    fun wolProbeTest() {
        val fNoPerm = fixture(listOf("storage"))
        assertEquals(403, fNoPerm.routes.handle(post("/api/hydro/worker/wol/probe", """{"package":"com.demo.weather","host":"192.168.1.100"}"""))!!.status)

        val f = fixture(listOf("wol"))
        val missingHost = f.routes.handle(post("/api/hydro/worker/wol/probe", """{"package":"com.demo.weather","host":""}"""))!!
        assertEquals(400, missingHost.status)

        val ok = f.routes.handle(post("/api/hydro/worker/wol/probe", """{"package":"com.demo.weather","host":"192.168.1.100","port":3389,"timeout":1500}"""))!!
        assertEquals(200, ok.status)
        val res = json(ok.body)
        assertTrue(res.get("online").asBoolean)
        assertEquals(15, res.get("rttMs").asInt)
        assertEquals(Triple("192.168.1.100", 3389, 1500), f.caps.lastWolProbe)
    }

    @Test
    fun udpPermissionGateAndSend() {
        val fNoPerm = fixture(listOf("storage"))
        assertEquals(403, fNoPerm.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":8080,"data":"hi"}"""))!!.status)

        val f = fixture(listOf("udp"))
        // 缺少 host / 无效端口
        assertEquals(400, f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"","port":8080,"data":"hi"}"""))!!.status)
        assertEquals(400, f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":-1,"data":"hi"}"""))!!.status)

        // 默认 UTF-8
        val okUtf8 = f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":9000,"data":"hello"}"""))!!
        assertEquals(200, okUtf8.status)
        assertEquals(Triple("127.0.0.1", 9000, "hello".toByteArray(Charsets.UTF_8).toList()),
            Triple(f.caps.lastUdpSend!!.first, f.caps.lastUdpSend!!.second, f.caps.lastUdpSend!!.third.toList()))

        // Base64 解码
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val okB64 = f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":9000,"data":"$b64","encoding":"base64"}"""))!!
        assertEquals(200, okB64.status)
        assertEquals(listOf<Byte>(1, 2, 3, 4), f.caps.lastUdpSend!!.third.toList())

        // Hex 解码
        val okHex = f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":9000,"data":"0A 0B 0C","encoding":"hex"}"""))!!
        assertEquals(200, okHex.status)
        assertEquals(listOf<Byte>(10, 11, 12), f.caps.lastUdpSend!!.third.toList())

        // 失败回包
        f.caps.udpResult = false
        val fail = f.routes.handle(post("/api/hydro/worker/udp/send", """{"package":"com.demo.weather","host":"127.0.0.1","port":9000,"data":"x"}"""))!!
        assertEquals(500, fail.status)
        assertTrue(fail.body.contains("UDP 发送失败"))
    }
}
