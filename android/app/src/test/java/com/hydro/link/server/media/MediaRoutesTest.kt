package com.hydro.link.server.media

import com.hydro.link.server.hydrokit.HydroHttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 分段下发路由：路径解析（别把 `/api/hydro/worker/media` 之类认成自己的）、
 * 状态码语义（200/400/404/405/410）、缓存头、以及"分段内容真的是动图"。
 */
class MediaRoutesTest {

    private val direct = Executor { it.run() }

    private fun fixture(durationMs: Long = 10_000): Pair<MediaJobStore, MediaRoutes> {
        val jobs = MediaJobStore(TestVideo(durationMs), executor = direct)
        return jobs to MediaRoutes(jobs)
    }

    private fun req(method: String = "GET", path: String) = HydroHttpRequest(method, path)

    // ------------------------------------------------------------- 路径解析

    @Test
    fun parseAcceptsOnlySegmentPaths() {
        assertEquals("job" to 0, MediaRoutes.parse("/api/hydro/media/job/0.gif"))
        assertEquals("job" to 12, MediaRoutes.parse("/api/hydro/media/job/12"))
        assertEquals("job" to 3, MediaRoutes.parse("/api/hydro/media/job/3.gif?r=2"))
        assertEquals("job" to 3, MediaRoutes.parse("/api/hydro/media/job/3.gif/"))

        assertNull(MediaRoutes.parse("/api/hydro/media"))
        assertNull(MediaRoutes.parse("/api/hydro/media/job"))
        assertNull(MediaRoutes.parse("/api/hydro/media/job/x.gif"))
        assertNull(MediaRoutes.parse("/api/hydro/media/job/1/extra.gif"))
        // 别把 worker 的 media 能力端点抢过来（它是 POST 的 JSON 端点）
        assertNull(MediaRoutes.parse("/api/hydro/worker/media"))
        assertNull(MediaRoutes.parse("/api/hydro/mediax/job/0.gif"))
        assertNull(MediaRoutes.parse("/api/music/art"))
    }

    @Test
    fun nonMediaPathIsNotHandled() {
        val (_, routes) = fixture()
        assertNull(routes.handle(req(path = "/api/hydro/worker/media")))
        assertNull(routes.handle(req(path = "/api/music/art")))
    }

    // ------------------------------------------------------------- 下发

    @Test
    fun servesGifWithCacheHeader() {
        val (jobs, routes) = fixture()
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4"))

        val resp = routes.handle(req(path = "/api/hydro/media/${job.id}/0.gif"))!!
        assertEquals(200, resp.status)
        assertEquals("image/gif", resp.contentType)
        assertEquals("public, max-age=600", resp.headers["Cache-Control"])
        assertTrue("必须是二进制回包", resp.bytes != null)
        assertEquals("GIF89a", String(resp.bytes!!, 0, 6, Charsets.US_ASCII))

        // 与作业里那一段逐字节一致（路由不做二次加工）
        val direct = jobs.segment(job.id, 0)
        assertTrue(direct.contentEquals(resp.bytes!!))
    }

    @Test
    fun servedSegmentIsARealAnimation() {
        val (jobs, routes) = fixture()
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4"))

        val gif = routes.handle(req(path = "/api/hydro/media/${job.id}/1.gif"))!!.bytes!!
        val probe = GifProbe.parse(gif)
        assertEquals(6, probe.frames.size)
        // 段内画面必须真的在动（静态封面冒充会被这条抓住）
        val diff = probe.frames[0].pixels.zip(probe.frames[1].pixels).count { it.first != it.second }
        assertTrue("相邻帧一样，切片是假的", diff > probe.frames[0].pixels.size / 100)
    }

    @Test
    fun outOfRangeAndUnknownJobsAreDistinguished() {
        val (jobs, routes) = fixture(durationMs = 3_000)
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4"))
        assertEquals(3, job.segmentCount)

        assertEquals(200, routes.handle(req(path = "/api/hydro/media/${job.id}/2.gif"))!!.status)
        assertEquals(404, routes.handle(req(path = "/api/hydro/media/${job.id}/3.gif"))!!.status)
        assertTrue(routes.handle(req(path = "/api/hydro/media/zzz/0.gif"))!!.body.contains("未知切片作业"))
    }

    @Test
    fun releasedJobIsGone() {
        val (jobs, routes) = fixture()
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4"))
        jobs.release(job.id)
        val resp = routes.handle(req(path = "/api/hydro/media/${job.id}/0.gif"))!!
        assertEquals(410, resp.status)
    }

    @Test
    fun methodAndPathErrors() {
        val (jobs, routes) = fixture()
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4"))

        assertEquals(405, routes.handle(req(method = "POST", path = "/api/hydro/media/${job.id}/0.gif"))!!.status)
        assertEquals(405, routes.handle(req(method = "DELETE", path = "/api/hydro/media/${job.id}/0.gif"))!!.status)
        assertEquals(400, routes.handle(req(path = "/api/hydro/media/${job.id}"))!!.status)
        assertEquals(400, routes.handle(req(path = "/api/hydro/media/${job.id}/x.gif"))!!.status)
    }
}
