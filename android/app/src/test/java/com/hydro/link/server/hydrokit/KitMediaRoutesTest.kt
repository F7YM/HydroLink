package com.hydro.link.server.hydrokit

import com.google.gson.JsonParser
import com.hydro.link.server.media.MediaJobStore
import com.hydro.link.server.media.MediaRoutes
import com.hydro.link.server.media.TestVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `kit.media`（宿主内置视频切片）的能力端点：
 *
 *  · 权限门控（没声明 `media` 就是 403，与其它能力一致）；
 *  · 宿主不支持时（桌面端 / 没注入作业存储）明确回 501，插件据此回退静态封面；
 *  · `prepare` 回的**分段 URL 真的能取到 GIF**（这是"插件只传参数、宿主负责切片"
 *    这条设计的验收点：URL 与实际路由必须对得上，错一位就是手表上的一片空白）；
 *  · hint/status/release 的生命周期语义。
 */
class KitMediaRoutesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 只覆盖 serverBase 的宿主能力（其余全部委托给"什么都没有"的 NONE）。 */
    private class FakeCaps(private val base: String = "http://192.168.1.5:9000") :
        KitCapabilities by KitCapabilities.NONE {
        override fun serverBase() = base
    }

    private val direct = Executor { it.run() }

    private fun storeWithPlugin(permissions: List<String>): PluginStore {
        val st = PluginStore(tmp.newFolder("data-${System.nanoTime()}"))
        val hpk = File(tmp.root, "p-${System.nanoTime()}.hpk")
        val perms = permissions.joinToString(",") { "\"$it\"" }
        val manifest = """
            {"id":"com.hydro.bili","name":"哔哩","author":"测试作者","version":"1.0.0","runtime":"js","entry":"server.js",
             "apps":[{"id":"main"}],"permissions":[$perms]}
        """.trimIndent()
        ZipOutputStream(hpk.outputStream()).use { z ->
            for ((p, c) in mapOf("manifest.json" to manifest, "server.js" to "// code")) {
                z.putNextEntry(ZipEntry(p)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
        st.install(hpk)
        return st
    }

    private class Fixture(
        val jobs: MediaJobStore?,
        val routes: KitRoutes,
        val mediaRoutes: MediaRoutes,
    )

    private fun fixture(
        permissions: List<String> = listOf("media"),
        base: String = "http://192.168.1.5:9000",
        durationMs: Long = 10_000,
        withStore: Boolean = true,
    ): Fixture {
        val st = storeWithPlugin(permissions)
        val jobs = if (withStore) MediaJobStore(TestVideo(durationMs), executor = direct) else null
        val routes = KitRoutes(
            st,
            WorkerBridge(),
            FakeCaps(base),
            { _ -> },
            { emptyMap() },
            jobs,
        )
        val mediaRoutes = MediaRoutes(jobs ?: MediaJobStore(TestVideo(durationMs), executor = direct))
        return Fixture(jobs, routes, mediaRoutes)
    }

    private fun post(path: String, body: String) = HydroHttpRequest("POST", path, body = body)
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject
    private val mediaPath = "/api/hydro/worker/media"

    // ------------------------------------------------------------- 门控与宿主能力

    @Test
    fun mediaCapabilityRequiresPermission() {
        val f = fixture(permissions = emptyList())
        val resp = f.routes.handle(
            post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4"}"""),
        )!!
        assertEquals(403, resp.status)
        assertTrue(resp.body.contains("权限不足"))
    }

    @Test
    fun unknownPluginIsForbidden() {
        val f = fixture()
        val resp = f.routes.handle(
            post(mediaPath, """{"package":"com.nope","op":"prepare","url":"https://e.com/v.mp4"}"""),
        )!!
        assertEquals(403, resp.status)
    }

    @Test
    fun hostWithoutDecoderAnswers501() {
        // 桌面端就是这种情况：明确说不支持，插件据此回退静态封面（而不是假装成功）
        val f = fixture(withStore = false)
        val resp = f.routes.handle(
            post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4"}"""),
        )!!
        assertEquals(501, resp.status)
        assertTrue(resp.body.contains("不支持视频解码"))
    }

    @Test
    fun missingHostBaseIs503() {
        val f = fixture(base = "")
        val resp = f.routes.handle(
            post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4"}"""),
        )!!
        assertEquals(503, resp.status)
    }

    // ------------------------------------------------------------- prepare

    @Test
    fun prepareReturnsSegmentUrlsWithClampedParameters() {
        val f = fixture(durationMs = 5_000)
        val resp = f.routes.handle(
            post(
                mediaPath,
                """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4",
                    "headers":{"Referer":"https://www.bilibili.com","User-Agent":"Mozilla/5.0"},
                    "segmentMs":200,"fps":999,"width":9999,"height":40,"maxSegments":40}""",
            ),
        )!!
        assertEquals(200, resp.status)
        val body = json(resp.body)

        assertEquals(5_000L, body.get("durationMs").asLong)
        assertEquals(20, body.get("fps").asInt)          // 夹到上限
        // width/height 是**实际输出尺寸**（盒子等比内缩、不放大，见 SegmentPlan.outputSize）：
        // 传进来的盒子 9999x40 先夹到 480x40，320x180 的源等比内缩进去 → 71x40。
        // 这就是插件按"播放区盒子"传参的语义：直接拉伸到盒子会把 16:9 画面压扁。
        assertEquals(71, body.get("width").asInt)
        assertEquals(40, body.get("height").asInt)
        assertEquals(25, body.get("segmentCount").asInt) // 5000ms / 200ms

        val segments = body.getAsJsonArray("segments")
        assertEquals(25, segments.size())
        val first = segments[0].asJsonObject
        assertEquals(0, first.get("index").asInt)
        assertEquals(200, first.get("durationMs").asInt)
        assertTrue(first.get("url").asString.startsWith("http://192.168.1.5:9000/api/hydro/media/"))

        // 分段 URL 与实际路由必须对得上：拿回来直接请求，必须真是 GIF
        val url = first.get("url").asString
        val got = f.mediaRoutes.handle(HydroHttpRequest("GET", url.removePrefix("http://192.168.1.5:9000")))!!
        assertEquals(200, got.status)
        assertEquals("image/gif", got.contentType)
        assertEquals("GIF89a", String(got.bytes!!, 0, 6, Charsets.US_ASCII))
        // 画幅 = 实际输出尺寸（等比内缩进盒子、不放大）：71x40，而不是拉伸成 480x40
        val gif = com.hydro.link.server.media.GifProbe.parse(got.bytes!!)
        assertEquals(71, gif.width)
        assertEquals(40, gif.height)
    }

    @Test
    fun segmentUrlsCarryTheMatchingSegment() {
        // 2 秒视频、每段 1200ms → 两段；两段的画面必须不同（段号对不上就会一模一样）
        val f = fixture(durationMs = 2_000)
        val segments = json(
            f.routes.handle(
                post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4"}"""),
            )!!.body,
        ).getAsJsonArray("segments")

        val pixels = mutableListOf<IntArray>()
        for (el in segments) {
            val path = el.asJsonObject.get("url").asString.removePrefix("http://192.168.1.5:9000")
            val gif = f.mediaRoutes.handle(HydroHttpRequest("GET", path))!!.bytes!!
            pixels.add(com.hydro.link.server.media.GifProbe.parse(gif).frames[0].pixels)
        }
        assertEquals(2, pixels.size)
        assertTrue("第 1 段与第 0 段画面相同（段号没对上）", !pixels[0].contentEquals(pixels[1]))
    }

    @Test
    fun prepareRejectsBadUrlAndUnknownOp() {
        val f = fixture()
        val bad = f.routes.handle(
            post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"file:///tmp/v.mp4"}"""),
        )!!
        assertEquals(400, bad.status)

        val nope = f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"frobnicate"}"""))!!
        assertEquals(400, nope.status)
        assertTrue(nope.body.contains("未知操作"))

        val noIndex = f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"hint"}"""))!!
        assertEquals(400, noIndex.status)
    }

    // ------------------------------------------------------------- hint / status / release

    @Test
    fun hintStatusAndReleaseLifecycle() {
        val f = fixture()
        val jobs = f.jobs!!
        val prepared = json(
            f.routes.handle(
                post(mediaPath, """{"package":"com.hydro.bili","op":"prepare","url":"https://e.com/v.mp4"}"""),
            )!!.body,
        )
        val job = prepared.get("job").asString
        assertEquals(listOf(0, 1), jobs.status(job)["cached"])

        val hint = json(
            f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"hint","job":"$job","index":2}"""))!!.body,
        )
        assertEquals(true, hint.get("ok").asBoolean)
        assertEquals(2, hint.getAsJsonArray("queued").size())

        val status = json(
            f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"status","job":"$job"}"""))!!.body,
        )
        assertEquals(job, status.get("job").asString)
        assertEquals(9, status.get("segmentCount").asInt) // 10s / 1.2s
        assertEquals(false, status.get("released").asBoolean)

        val released = f.routes.handle(
            post(mediaPath, """{"package":"com.hydro.bili","op":"release","job":"$job"}"""),
        )!!
        assertEquals(200, released.status)
        // 释放后：能力端点与分段路由都要回 410（不是 404，让手表知道"这个作业没了"）
        assertEquals(404, f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"release","job":"$job"}"""))!!.status)
        assertEquals(410, f.routes.handle(post(mediaPath, """{"package":"com.hydro.bili","op":"status","job":"$job"}"""))!!.status)
        assertEquals(410, f.mediaRoutes.handle(HydroHttpRequest("GET", "/api/hydro/media/$job/0.gif"))!!.status)
    }

    @Test
    fun workerPathMustBePost() {
        val f = fixture()
        assertEquals(405, f.routes.handle(HydroHttpRequest("GET", mediaPath))!!.status)
    }
}
