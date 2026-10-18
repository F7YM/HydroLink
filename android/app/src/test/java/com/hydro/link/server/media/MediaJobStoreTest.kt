package com.hydro.link.server.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 切片作业调度：前两段预热、懒生成、滑窗缓存、释放/过期语义、参数夹取。
 *
 * 用 [TestVideo] 出**真画面**，再交给 [GifProbe]（独立最小解码器）解回来断言
 * 「相邻段画面不同、段内帧真的在动」——这是「不许拿静态封面冒充视频切片」这条
 * 需求的自动化判据。
 */
class MediaJobStoreTest {

    private val direct = Executor { it.run() }

    private fun store(video: VideoFrameSource, clock: () -> Long = System::currentTimeMillis) =
        MediaJobStore(video, clock = clock, executor = direct)

    private fun spec(
        segmentMs: Int = 1200,
        fps: Int = 5,
        url: String = "https://example.com/v.mp4",
        startMs: Long = 0,
        // 单测里用比默认更小的输出尺寸：同一条代码路径，速度快一个量级
    ) = VideoSliceSpec(url = url, startMs = startMs, segmentMs = segmentMs, fps = fps, width = 120, height = 68)

    // ------------------------------------------------------------- 基本流程

    @Test
    fun prepareWarmsFirstTwoSegmentsAndPreparesTheRest() {
        val video = TestVideo(durationMs = 10_000)
        val jobs = store(video)
        val job = jobs.prepare("com.demo.weather", spec())

        // 10 秒 / 1.2 秒 → 9 段
        assertEquals(9, job.segmentCount)
        assertEquals(320, job.info.width)
        assertEquals(10_000L, job.info.durationMs)

        val st = jobs.status(job.id)
        assertEquals(listOf(0, 1), st["cached"])
        assertEquals(2, video.framesCalls) // 前两段真的切了，不是只记了个账

        // 第 3 段按需生成：请求它之后缓存是 [2,3] —— "谁在取就顺手预热下一段"，
        // 所以缓存里是"当前段 + 下一段"，上一段被滑窗挤掉（窗口 ≤2 段）
        val seg2 = jobs.segment(job.id, 2)
        assertTrue(seg2.size > 100)
        assertEquals(listOf(2, 3), jobs.status(job.id)["cached"])
        assertEquals(4, video.framesCalls)
    }

    @Test
    fun sampleTimesFollowTheSegmentPlan() {
        val video = TestVideo(durationMs = 5_000)
        val jobs = store(video)
        val job = jobs.prepare("com.demo.weather", spec(fps = 5, segmentMs = 1200))

        // prepare 按顺序预热 0/1 两段：采样时刻必须是"每段从 (段号 × 段长) 起、每 1000/fps 一帧"
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L, 1000L), video.timesCalls[0].toList())
        assertEquals(listOf(1200L, 1400L, 1600L, 1800L, 2000L, 2200L), video.timesCalls[1].toList())

        // 还没取过的第 3 段：规划到 3600ms 起（解码器只按这个时刻表去 seek/取帧）
        assertEquals(
            listOf(3600L, 3800L, 4000L, 4200L, 4400L, 4600L),
            SegmentPlan.sampleTimesMs(job.spec, 3, job.info.durationMs).toList(),
        )
    }

    @Test
    fun segmentsCarryRealPerFrameMotionAndDifferFromEachOther() {
        val video = TestVideo(durationMs = 5_000)
        val jobs = store(video)
        val job = jobs.prepare("com.demo.weather", spec(fps = 5, segmentMs = 1200))

        val seg0 = GifProbe.parse(jobs.segment(job.id, 0))
        val seg1 = GifProbe.parse(jobs.segment(job.id, 1))

        // 每段 6 帧（1200ms × 5fps），帧延迟 20 厘秒（= 200ms）
        assertEquals(6, seg0.frames.size)
        for (f in seg0.frames) assertEquals(20, f.delayCentisecs)

        // 段内相邻帧必须真的不同（静态画面冒充会被这条抓住）
        for (i in 1 until seg0.frames.size) {
            val diff = diffRatio(seg0.frames[i - 1].pixels, seg0.frames[i].pixels)
            assertTrue("第 $i 帧与前一帧几乎一样（diff=$diff）", diff > 0.01)
        }
        // 相邻两段的第一帧也必须不同（不同时刻的画面）
        val crossDiff = diffRatio(seg0.frames[0].pixels, seg1.frames[0].pixels)
        assertTrue("相邻两段画面一样（diff=$crossDiff）", crossDiff > 0.01)
    }

    @Test
    fun lastSegmentIsShorterButStillValid() {
        // 时长 2600ms、每段 1200ms → 段 0/1 满、段 2 只剩 200ms（1 帧）
        val video = TestVideo(durationMs = 2_600)
        val jobs = store(video)
        val job = jobs.prepare("com.demo.weather", spec())
        assertEquals(3, job.segmentCount)

        val last = GifProbe.parse(jobs.segment(job.id, 2))
        assertEquals(1, last.frames.size)
        assertEquals(1_200, SegmentPlan.segmentDurationMs(job.spec, 0, job.info.durationMs))
        assertEquals(200, SegmentPlan.segmentDurationMs(job.spec, 2, job.info.durationMs))
    }

    @Test
    fun hintWarmsTheNextWindowWithoutBlocking() {
        val video = TestVideo(durationMs = 10_000)
        val jobs = store(video)
        val job = jobs.prepare("com.demo.weather", spec())
        val before = video.framesCalls

        val res = jobs.hint(job.id, 2)
        assertEquals(true, res["ok"])
        assertEquals(listOf(2, 3), res["queued"])
        assertEquals(before + 2, video.framesCalls)
        // 被挤掉的是最旧的段
        assertEquals(listOf(2, 3), jobs.status(job.id)["cached"])

        // 已经在缓存里的段不会被重复排队
        val again = jobs.hint(job.id, 2)
        assertEquals(emptyList<Int>(), again["queued"])
    }

    // ------------------------------------------------------------- 生命周期

    @Test
    fun releaseMakesSegmentsGone() {
        val jobs = store(TestVideo(durationMs = 10_000))
        val job = jobs.prepare("com.demo.weather", spec())
        assertEquals(true, jobs.release(job.id))
        assertErrorStatus(410) { jobs.segment(job.id, 0) }
        assertErrorStatus(410) { jobs.status(job.id) }
        assertFalse("重复释放应当返回 false", jobs.release(job.id))
    }

    @Test
    fun unknownJobIsNotFound() {
        val jobs = store(TestVideo(durationMs = 10_000))
        assertErrorStatus(404) { jobs.segment("nope", 0) }
        assertErrorStatus(404) { jobs.status("nope") }
    }

    @Test
    fun idleJobsExpire() {
        var now = 1_000_000L
        val video = TestVideo(durationMs = 10_000)
        val jobs = MediaJobStore(
            video,
            idleTimeoutMs = 60_000,
            clock = { now },
            executor = direct,
        )
        val job = jobs.prepare("com.demo.weather", spec())

        now += 59_000
        jobs.expireIdle()
        assertTrue("没过期不该被回收", jobs.segment(job.id, 0).isNotEmpty())

        now += 61_000
        jobs.expireIdle()
        assertErrorStatus(410) { jobs.segment(job.id, 0) }
    }

    @Test
    fun thirdJobEvictsTheOldest() {
        val video = TestVideo(durationMs = 10_000)
        val jobs = MediaJobStore(video, maxJobs = 2, executor = direct)
        val a = jobs.prepare("com.a", spec(url = "https://example.com/a.mp4"))
        val b = jobs.prepare("com.b", spec(url = "https://example.com/b.mp4"))
        val c = jobs.prepare("com.c", spec(url = "https://example.com/c.mp4"))

        assertErrorStatus(410) { jobs.segment(a.id, 0) }
        assertTrue(jobs.segment(b.id, 0).isNotEmpty())
        assertTrue(jobs.segment(c.id, 0).isNotEmpty())
    }

    @Test
    fun segmentBeyondTheVideoIsNotFound() {
        val jobs = store(TestVideo(durationMs = 3_000))
        val job = jobs.prepare("com.demo.weather", spec())
        assertEquals(3, job.segmentCount)
        assertErrorStatus(404) { jobs.segment(job.id, 3) }
        assertErrorStatus(400) { jobs.segment(job.id, -1) }
    }

    // ------------------------------------------------------------- 参数与失败

    @Test
    fun parametersAreClampedInsteadOfTrusted() {
        val video = TestVideo(durationMs = 10_000)
        val jobs = store(video)
        val job = jobs.prepare(
            "com.demo.weather",
            VideoSliceSpec(
                url = "  https://example.com/v.mp4  ",
                headers = (1..20).associate { "h$it" to "v" },
                startMs = -5,
                segmentMs = 10,
                fps = 999,
                width = 9_999,
                height = 4,
                maxSegments = 10_000,
            ).normalized(),
        )
        assertEquals("https://example.com/v.mp4", job.spec.url)
        assertEquals("startMs 被夹到 0", 0L, job.spec.startMs)
        assertEquals(VideoSliceSpec.MIN_SEGMENT_MS, job.spec.segmentMs)
        assertEquals(VideoSliceSpec.MAX_FPS, job.spec.fps)
        assertEquals(VideoSliceSpec.MAX_SIZE, job.spec.width)
        assertEquals(VideoSliceSpec.MIN_SIZE, job.spec.height)
        assertEquals(VideoSliceSpec.MAX_SEGMENTS_LIMIT, job.spec.maxSegments)
        assertEquals(VideoSliceSpec.MAX_HEADERS, job.spec.headers.size)
    }

    @Test
    fun nonHttpUrlIsRejected() {
        val jobs = store(TestVideo(durationMs = 10_000))
        assertErrorStatus(400) { jobs.prepare("com.demo.weather", spec(url = "file:///tmp/v.mp4")) }
        assertErrorStatus(400) { jobs.prepare("com.demo.weather", spec(url = "")) }
    }

    @Test
    fun startBeyondTheVideoIsRejected() {
        val jobs = store(TestVideo(durationMs = 3_000))
        assertErrorStatus(400) {
            jobs.prepare("com.demo.weather", spec(startMs = 5_000))
        }
    }

    @Test
    fun probeFailureIsReportedAndLeavesNoJobBehind() {
        val jobs = store(TestVideo(durationMs = 10_000, failProbe = true))
        assertErrorStatus(500) { jobs.prepare("com.demo.weather", spec()) }
    }

    @Test
    fun decodeFailureOnFirstSegmentFailsPrepareAndDropsTheJob() {
        val jobs = store(TestVideo(durationMs = 10_000, failFrames = true))
        // 取帧直接抛异常 → 第一段切不出来 → 作业没意义，直接失败
        val e = assertErrorStatus(500) { jobs.prepare("com.demo.weather", spec()) }
        assertTrue("错误信息应说明是解码失败: ${e.message}", e.message!!.contains("解码"))
        assertEquals(0, jobs.jobCount())
    }

    @Test
    fun durationUnknownFallsBackToMaxSegments() {
        val jobs = store(TestVideo(durationMs = -1))
        val job = jobs.prepare("com.demo.weather", VideoSliceSpec(url = "https://example.com/v.mp4", maxSegments = 7))
        assertEquals(7, job.segmentCount)
        // 时长未知时不能给出"超出范围"的 404（后面取到哪算哪）
        assertTrue(jobs.segment(job.id, 6).isNotEmpty())
    }

    // ------------------------------------------------------------- 工具

    private fun assertErrorStatus(
        expected: Int,
        block: () -> Unit,
    ): MediaError {
        try {
            block()
        } catch (e: MediaError) {
            assertEquals("HTTP 状态应当匹配：${e.message}", expected, e.status)
            return e
        }
        fail("应当抛出 MediaError($expected)")
        return MediaError(0, "")
    }

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        assertTrue(a.isNotEmpty())
        if (a.size != b.size) return 1.0
        var changed = 0
        for (i in a.indices) if (a[i] != b[i]) changed++
        return changed.toDouble() / a.size
    }
}
