package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import com.hydro.link.server.media.GifProbe
import com.hydro.link.server.media.MediaJobStore
import com.hydro.link.server.media.MediaRoutes
import com.hydro.link.server.media.TestVideo
import com.hydro.link.server.media.VideoFrameSource
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M5 端到端：把 **assets 里的真 worker 胶水** 交给 Node 执行，对着
 * **真 HTTP 服务端**（NanoHTTPD + HydroKit 路由）跑通完整链路：
 *
 *   HTTP view 请求 → Runtime → WorkerPlugin → WorkerBridge
 *     → Node 里的 worker.js 轮询领取 → 加载插件代码（server.js / plugin.wasm）
 *     → 回填 View → HTTP 响应返回该树
 *
 * 这与桌面端 Go e2e（用 Node 跑真实 WorkerBundle）是同一套验证思路：
 * 不依赖 WebView / 真机，但跑的是真实产物（真实 .hpk + 真实胶水脚本）。
 */
class WorkerGlueE2ETest {

    private lateinit var server: TestServer
    private lateinit var node: Process
    private lateinit var baseUrl: String

    /** 只挂 HydroKit 的最小 NanoHTTPD 服务端（适配层与 App 里的写法一致）。 */
    private class TestServer(port: Int) : NanoHTTPD("127.0.0.1", port) {
        /** 由各用例注入（HydroKit 门面需要 Android Context，JVM 里改用同构的 Harness）。 */
        @Volatile
        var handler: ((HydroHttpRequest) -> HydroHttpResponse?)? = null

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            if (!uri.startsWith("/api/hydro")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nope")
            }
            val rawBody: String? = if (session.method == Method.POST) {
                val files = HashMap<String, String>()
                try { session.parseBody(files) } catch (e: Exception) {}
                files["postData"]
            } else null

            val h = handler ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no handler")
            val out = h(
                HydroHttpRequest(
                    method = session.method.name,
                    path = uri,
                    query = session.parameters.entries.associate { (k, v) -> k to (v.firstOrNull() ?: "") },
                    headers = session.headers.entries.associate { (k, v) -> k.lowercase() to v },
                    body = rawBody,
                )
            ) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no route")

            val status = Response.Status.values().firstOrNull { it.requestStatus == out.status } ?: Response.Status.OK
            val bin = out.bytes
            return if (bin != null) {
                newFixedLengthResponse(status, out.contentType, ByteArrayInputStream(bin), bin.size.toLong())
            } else {
                newFixedLengthResponse(status, out.contentType, out.body)
            }
        }
    }

    @Before
    fun setUp() {
        // Node 是 e2e 的前提（本机一般都有；没有就跳过而不是误报失败）
        val hasNode = runCatching {
            ProcessBuilder("node", "--version").start().waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false)
        Assume.assumeTrue("本机没有 node，跳过 worker 胶水 e2e", hasNode)

        val port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port"
        server = TestServer(port)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun tearDown() {
        runCatching { node.destroy() }
        runCatching { server.stop() }
    }

    /** 数据目录：HydroKit 需要 Context，这里给一个只用到 filesDir 的最小替身不可行，
     *  所以 e2e 直接用 PluginStore + Runtime + KitRoutes 组装（与 HydroKit 内部一致）。 */
    private class Harness(
        val dataDir: File,
        /** 宿主自报的地址（切片分段 URL 挂在它下面）。空串 = 宿主地址不可用。 */
        serverBase: String = "",
        /** 解码器：给了才能切片（JVM 里用 TestVideo 假解码器，真机上是 MediaCodec）。 */
        decoder: VideoFrameSource? = null,
    ) {
        val store = PluginStore(dataDir)
        val bridge = WorkerBridge(requestTimeoutMs = 8000, pollWindowMs = 500, pollIntervalMs = 50)
        val runtime = Runtime()
        val caps = object : KitCapabilities by KitCapabilities.NONE {
            override fun serverBase() = serverBase
        }
        val mediaJobs = decoder?.let { MediaJobStore(it, executor = Executor { r -> r.run() }) }
        val kitRoutes = KitRoutes(store, bridge, caps, media = mediaJobs)
        val pluginRoutes = HydroKitRoutes(runtime, store)
        val mediaRoutes = mediaJobs?.let { MediaRoutes(it) }
        /** 宿主页路由：读的就是 assets 里的真文件（脚本 src 有没有写错在这里会被抓到）。 */
        val hostRoutes = HostPageRoutes { name ->
            val f = File("src/main/assets/hydrokit/$name")
            if (f.isFile) f.readBytes() else null
        }

        /** 与 HydroKit.handle() 同序：宿主页 → 切片分段 → kit 能力 → 插件路由。 */
        fun handle(req: HydroHttpRequest): HydroHttpResponse? =
            hostRoutes.handle(req)
                ?: mediaRoutes?.handle(req)
                ?: kitRoutes.handle(req)
                ?: pluginRoutes.handle(req)
    }

    private fun realHpk(name: String): File? =
        listOf(File("../../desktop/examples/$name"), File("../desktop/examples/$name"))
            .firstOrNull { it.isFile }

    /** 用 Node 跑 assets 里的真胶水（worker.js），指向测试服务端。 */
    private fun startNodeWorker(h: Harness, pkg: String, entry: String, runtime: String): Process {
        val asset = File("src/main/assets/hydrokit/worker.js")
        val cfg = """
            globalThis.__HK_CONFIG = { package: "$pkg", base: "$baseUrl", entry: "$entry", runtime: "$runtime" };
            globalThis.__hkLog = (m) => console.log("[worker]", m);
            require("${asset.absolutePath.replace("\\", "/")}");
        """.trimIndent()
        val script = File.createTempFile("hk-worker-", ".js")
        script.writeText(cfg)
        val p = ProcessBuilder("node", script.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
        node = p
        return p
    }

    /**
     * 用 Node 模拟**浏览器加载宿主页**的过程（而不是直接 require 胶水）：
     *   抓 worker.html → 正则取 `<script src>` → **按页面 URL 解析**（相对路径在这里
     *   就会变成 /api/hydro/worker/worker.js）→ 取脚本 → 在带 `location` 的全局里执行。
     *
     * 这条用例存在的理由：真机上出过一次"宿主页加载成功、worker 永远离线、手表显示
     * 加载失败"，根因就是 script src 写成了相对路径。当时两个 e2e 都是绿的，因为它们
     * 用 `__HK_CONFIG` 直接 require 胶水，**绕过了 HTML 里的 script 标签**。
     */
    private fun startNodeWorkerViaHostPage(pkg: String, entry: String, runtime: String): Process {
        val pageUrl = "$baseUrl/api/hydro/worker/host?file=worker.html" +
            "&package=$pkg&base=${java.net.URLEncoder.encode(baseUrl, "UTF-8")}" +
            "&entry=$entry&runtime=$runtime"
        val cfg = """
            (async () => {
              const pageUrl = ${jsString(pageUrl)};
              const page = await fetch(pageUrl);
              if (!page.ok) { console.error("[e2e] 宿主页取不到: " + page.status); process.exit(3); }
              const html = await page.text();
              const m = html.match(/<script[^>]*\bsrc\s*=\s*["']([^"']+)["']/i);
              if (!m) { console.error("[e2e] 宿主页里没有 script src"); process.exit(4); }
              const jsUrl = new URL(m[1], pageUrl).toString();
              const jsRes = await fetch(jsUrl);
              if (!jsRes.ok) {
                console.error("[e2e] 胶水取不到（这就是真机上的 bug）: " + jsUrl + " -> " + jsRes.status);
                process.exit(5);
              }
              const code = await jsRes.text();
              globalThis.location = { href: pageUrl, search: new URL(pageUrl).search, origin: new URL(pageUrl).origin };
              globalThis.__hkLog = (msg) => console.log("[worker]", msg);
              require("vm").runInThisContext(code); // 等价于浏览器执行 <script src>
              console.log("[e2e] 宿主页按浏览器方式加载完成: " + jsUrl);
            })().catch((e) => { console.error("[e2e] 加载失败", e); process.exit(6); });
        """.trimIndent()
        val script = File.createTempFile("hk-worker-page-", ".js")
        script.writeText(cfg)
        val p = ProcessBuilder("node", script.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
        node = p
        return p
    }

    /** 拼进 Node 脚本里的字符串字面量。 */
    private fun jsString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun glueLoadsThroughTheHostPageLikeABrowser() {
        val hpk = realHpk("com.demo.weather.hpk")
        Assume.assumeTrue("仓库里没有 weather .hpk", hpk != null)
        val dir = File.createTempFile("hk-e2e-page-", "").let { it.delete(); it.mkdirs(); it }
        val h = Harness(dir)
        val info = h.store.install(hpk!!)
        h.runtime.register(WorkerPlugin(info.id, h.store.manifest(info.id), h.bridge))
        server.handler = { req -> h.handle(req) }

        // 宿主页本身必须可取（胶水靠它引进来）
        val page = httpGet("$baseUrl/api/hydro/worker/host?file=worker.html")
        assertEquals(200, page.first)
        assertTrue("宿主页应含 script 标签", page.second.contains("<script"))

        startNodeWorkerViaHostPage(info.id, info.entry, info.runtime)

        assertTrue(
            "按浏览器方式加载胶水后 worker 应上线（script src 写错就会在这里失败）",
            waitFor(6000) { h.bridge.isConnected(info.id) },
        )
        val resp = h.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/${info.id}/app/main/view",
                query = mapOf("shape" to "circle", "width" to "466", "height" to "466", "dt" to "watch"),
            )
        )
        assertEquals(200, resp!!.status)
        val root = JsonParser.parseString(resp.body).asJsonObject.getAsJsonObject("root")
        assertNotNull("宿主页加载路径下也应返回 root 树", root)
        println("== 宿主页加载路径（浏览器式）返回: ${resp.body.take(200)}")
    }

    @Test
    fun jsPluginServedThroughRealWorkerGlue() {
        val hpk = realHpk("com.demo.weather.hpk")
        Assume.assumeTrue("仓库里没有 weather .hpk", hpk != null)
        val dir = File.createTempFile("hk-e2e-", "").let { it.delete(); it.mkdirs(); it }
        val h = Harness(dir)
        val info = h.store.install(hpk!!)
        h.runtime.register(WorkerPlugin(info.id, h.store.manifest(info.id), h.bridge))
        server.handler = { req -> h.handle(req) }
        assertEquals("服务端应能响应 /api/hydro/list", 200, httpGet("$baseUrl/api/hydro/list").first)
        startNodeWorker(h, info.id, info.entry, info.runtime)

        // 等 worker 上线（轮询会建立会话）
        val online = waitFor(5000) { h.bridge.isConnected(info.id) }
        assertTrue("worker 应当已轮询上线", online)

        val resp = h.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/${info.id}/app/main/view",
                query = mapOf("shape" to "circle", "width" to "466", "height" to "466", "dt" to "watch"),
            )
        )
        assertNotNull(resp)
        assertEquals(200, resp!!.status)
        val json = JsonParser.parseString(resp.body).asJsonObject
        val root = json.getAsJsonObject("root")
        assertNotNull("返回里应有 root 树", root)
        assertTrue("根节点应有 type", root.has("type"))

        // 手表端存储的写后可读（真机上报过"开关点了关了、刷新又变回开"）：
        // 快照说 powerSave=true，toggle 后必须渲染成 false，并把 false 回传给容器落盘。
        val toggled = h.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/${info.id}/app/main/event",
                headers = mapOf("x-hydro-store" to """{"powerSave":true}"""),
                body = """{"event":{"handler":"toggle"}}""",
            )
        )
        assertEquals(200, toggled!!.status)
        val t = JsonParser.parseString(toggled.body).asJsonObject
        assertEquals("写后重读必须是新值", false, t.getAsJsonObject("storage").get("powerSave").asBoolean)
        assertTrue("渲染出来的开关状态也要跟着变", toggled.body.contains("\"checked\": false")
            || toggled.body.contains("\"checked\":false"))
        println("== weather(JS) 真实 worker 返回: ${resp.body.take(300)}")
    }

    @Test
    fun wasmPluginServedThroughRealWorkerGlue() {
        val hpk = realHpk("com.hydro.wasmfixture.hpk")
        Assume.assumeTrue("仓库里没有 wasm 夹具 .hpk", hpk != null)
        val dir = File.createTempFile("hk-e2e-wasm-", "").let { it.delete(); it.mkdirs(); it }
        val h = Harness(dir)
        val info = h.store.install(hpk!!)
        assertEquals("wasm", info.runtime)
        h.runtime.register(WorkerPlugin(info.id, h.store.manifest(info.id), h.bridge))
        server.handler = { req -> h.handle(req) }
        assertEquals("服务端应能响应 /api/hydro/list", 200, httpGet("$baseUrl/api/hydro/list").first)
        startNodeWorker(h, info.id, info.entry, info.runtime)

        val online = waitFor(5000) { h.bridge.isConnected(info.id) }
        assertTrue("wasm worker 应当已上线", online)

        val resp = h.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/${info.id}/app/main/view",
                query = mapOf("shape" to "rect", "width" to "432", "height" to "514", "dt" to "watch"),
            )
        )
        assertNotNull(resp)
        assertEquals(200, resp!!.status)
        val root = JsonParser.parseString(resp.body).asJsonObject.getAsJsonObject("root")
        assertNotNull("wasm 插件也应返回 root 树", root)
        println("== wasm 夹具真实 worker 返回: ${resp.body.take(300)}")
    }

    /**
     * 视频切片端到端（JVM 版）：**真胶水** → 真 HTTP → KitRoutes → MediaJobStore → GIF。
     *
     * 与真机只差 MediaCodec 那一段（JVM 里用 TestVideo 假解码器，但它的画面**随时间
     * 真的在变**，所以"拿静帧/渐变帧冒充切片"在这里就会红）：
     *   插件调 kit.media.prepareVideo（只传参数）→ 宿主切段 → 回一串分段 URL
     *   → 测试像手表那样**按 URL 走真 HTTP 取段** → 解码验证：GIF89a、帧数 ≥2、
     *   段内画面在动、相邻段画面不同。
     */
    @Test
    fun mediaSlicesThroughTheRealGlueAndSegmentsAreRealGifs() {
        val hpk = writeTestPlugin(
            """
            module.exports = {
              manifest() {
                return { package: { id: 'com.hydro.mediatest', name: '切片端到端', version: '1.0.0' },
                         apps: [{ id: 'main', name: '切片' }] };
              },
              // 与 bili 插件同款用法：只传参数（哪段视频、切成什么样），宿主真解码真切片
              async view(surface, item, req) {
                if (surface !== 'app' || item !== 'main') return null;
                const job = await kit.media.prepareVideo({
                  url: 'http://example.com/video.mp4',
                  headers: { Referer: 'https://example.com/' },
                  segmentMs: 1000, fps: 5, width: 64, height: 36, maxSegments: 2
                });
                return {
                  root: {
                    type: 'scroll',
                    props: {
                      error: job && job.error ? job.error : null,
                      job: job && job.job ? job.job : null,
                      segments: job && job.segments ? job.segments : null
                    }
                  }
                };
              }
            };
            """.trimIndent(),
        )
        val dir = File.createTempFile("hk-e2e-media-", "").let { it.delete(); it.mkdirs(); it }
        val h = Harness(dir, serverBase = baseUrl, decoder = TestVideo(3_000))
        val info = h.store.install(hpk)
        h.runtime.register(WorkerPlugin(info.id, h.store.manifest(info.id), h.bridge))
        server.handler = { req -> h.handle(req) }
        startNodeWorker(h, info.id, info.entry, info.runtime)
        assertTrue("worker 应当已轮询上线", waitFor(5_000) { h.bridge.isConnected(info.id) })

        val resp = h.handle(
            HydroHttpRequest(
                "POST", "/api/hydro/${info.id}/app/main/view",
                query = mapOf("dt" to "watch"),
            )
        )
        assertNotNull(resp)
        assertEquals("切片视图应当正常返回: ${resp!!.body.take(300)}", 200, resp.status)
        assertTrue(
            "宿主不支持或取流失败时必须如实报错（不许假装成功）: ${resp.body.take(300)}",
            !resp.body.contains("\"error\":\"") && !resp.body.contains("error\": \""),
        )

        val segUrls = Regex("http://[^\"]+/api/hydro/media/[^\"]+?\\.gif").findAll(resp.body)
            .map { it.value }.toList()
        assertTrue("视图里应有至少两段切片 URL: ${resp.body.take(400)}", segUrls.size >= 2)

        // 手表就是照这些 URL 直取的：走真 HTTP 取回来，验证它真是动图
        val gifs = segUrls.take(2).map { url ->
            val (code, bytes) = httpGetBytes(url)
            assertEquals("分段 URL 应当能直接取到（手表就是这么取的）: $url", 200, code)
            GifProbe.parse(bytes)
        }
        assertEquals(2, gifs.size)
        for ((i, gif) in gifs.withIndex()) {
            assertTrue("第 $i 段帧数应 ≥2（单帧 = 拿静帧冒充视频）: ${gif.frames.size}", gif.frames.size >= 2)
            assertTrue(
                "第 $i 段段内画面应真的在变（每帧一样 = 假动图）",
                !gif.frames[0].pixels.contentEquals(gif.frames[1].pixels),
            )
        }
        assertTrue(
            "相邻段首帧画面必须不同（相同 = 段号没对上 / 拿同一张图冒充）",
            !gifs[0].frames[0].pixels.contentEquals(gifs[1].frames[0].pixels),
        )
        println("== 切片端到端：${segUrls.size} 段，第 0 段 ${gifs[0].frames.size} 帧 ${gifs[0].width}x${gifs[0].height}")
    }

    /** 造一个测试插件包（真 .hpk：manifest.json + server.js）。 */
    private fun writeTestPlugin(serverJs: String, permissions: List<String> = listOf("media")): File {
        val dir = File.createTempFile("hk-e2e-plugin-", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "manifest.json").writeText(
            """
            {"id":"com.hydro.mediatest","name":"切片端到端","author":"测试","version":"1.0.0",
             "runtime":"js","entry":"server.js","apps":[{"id":"main","name":"切片"}],
             "permissions":[${permissions.joinToString(",") { "\"$it\"" }}]}
            """.trimIndent(),
        )
        File(dir, "server.js").writeText(serverJs)
        val hpk = File(dir, "com.hydro.mediatest.hpk")
        ZipOutputStream(hpk.outputStream()).use { z ->
            for (f in dir.listFiles()!!.filter { it != hpk }) {
                z.putNextEntry(ZipEntry(f.name))
                z.write(f.readBytes())
                z.closeEntry()
            }
        }
        return hpk
    }

    /** 取二进制响应（分段是 GIF，不能按文本读）。 */
    private fun httpGetBytes(url: String): Pair<Int, ByteArray> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        val code = conn.responseCode
        val bytes = (if (code < 400) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
        return code to bytes
    }

    /** 简单 GET，返回 (状态码, body)。 */
    private fun httpGet(url: String): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        return code to text
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }
}
