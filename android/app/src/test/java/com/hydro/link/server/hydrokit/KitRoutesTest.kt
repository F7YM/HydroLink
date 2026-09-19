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
        override fun perf() = mapOf<String, Any?>("cpu" to 12.5, "memPercent" to 63.2, "memUsedMB" to 4096, "memTotalMB" to 8192)
        override fun music() = mapOf<String, Any?>("hasMedia" to true, "title" to "歌", "artist" to "手", "playing" to true, "artUrl" to "file:///tmp/a.jpg")
        override fun musicAction(action: String): Boolean { lastMusicAction = action; return true }
        override fun notify(title: String, text: String): Boolean { lastNotify = title to text; return true }
        override fun lock(): Boolean { locked = true; return true }
        override fun commandList() = listOf<Map<String, Any?>>(mapOf("id" to 0, "name" to "hello", "command" to "echo hi"))
        override fun runCommand(index: Int): Map<String, Any?> { ranIndex = index; return mapOf("success" to true, "exitCode" to 0, "output" to "hi") }
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
        assertEquals(200, f.routes.handle(post("/api/hydro/worker/notify", """{"package":"com.demo.weather","title":"标题","text":"正文"}"""))!!.status)
        assertEquals("标题" to "正文", f.caps.lastNotify)

        f.routes.handle(post("/api/hydro/worker/music", """{"package":"com.demo.weather","action":"next"}"""))
        assertEquals("next", f.caps.lastMusicAction)

        assertTrue(json(f.routes.handle(post("/api/hydro/worker/lock", """{"package":"com.demo.weather"}"""))!!.body).get("status").asString == "ok")
        assertTrue(f.caps.locked)

        val list = json(f.routes.handle(post("/api/hydro/worker/commands", """{"package":"com.demo.weather","op":"list"}"""))!!.body)
        assertEquals(1, list.getAsJsonArray("commands").size())

        f.routes.handle(post("/api/hydro/worker/commands", """{"package":"com.demo.weather","op":"run","index":2}"""))
        assertEquals(2, f.caps.ranIndex)

        val perf = json(f.routes.handle(post("/api/hydro/worker/system", """{"package":"com.demo.weather","op":"perf"}"""))!!.body)
        assertEquals(12.5, perf.get("cpu").asDouble, 0.01)
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
}
