package com.hydro.link.server.media

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 切片作业的调度与缓存（**纯逻辑，无 Android 依赖**，可在 JVM 单测里跑）。
 *
 * 为什么要有作业概念：
 *  · 一段 GIF 需要真解码（MediaCodec）+ 真编码（[GifSlicer]），单段成本几百毫秒；
 *  · 手表播放时按段取 URL，取到哪段算哪段——所以**懒生成 + 滑窗缓存**，不预先切完整片
 *    （一部 10 分钟视频按 1.2 秒/段是 500 段、几十 MB，手机没必要为它占内存）；
 *  · 手表双击播放时至少已有前两段（[prepare] 阻塞到就绪），后续段由「谁在取，就顺手
 *    预热下一段」把播放位置推断出来，插件/手表都不需要额外上报进度。
 *
 * 缓存上限 [cacheSegments] 就是需求里的「常驻切片窗口 ≤2 段」：单段 20~40KB，
 * 两个作业 × 两段不过百来 KB，滚动播放时下一段总是热的。
 */
class MediaJobStore(
    private val source: VideoFrameSource,
    private val maxJobs: Int = 2,
    private val cacheSegments: Int = 2,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = defaultExecutor(),
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** 空闲多久算过期（真机上插件打开一次视频就会持续取段，10 分钟足够宽松）。 */
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** 释放/过期后仍回 410 的作业 id 记忆条数（超出后老 id 一律 404）。 */
        private const val GONE_MEMORY = 16

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "hydro-media-slice").apply { isDaemon = true }
        }
    }

    /** 一个切片作业（对应的视频/参数/分段规划）。 */
    class Job internal constructor(
        val id: String,
        /** 发起作业的插件包 id（诊断用）。 */
        val owner: String,
        val spec: VideoSliceSpec,
        val info: VideoInfo,
        /** 预计能切几段（时长未知时 = spec.maxSegments）。 */
        val segmentCount: Int,
    )

    private class JobState(val job: Job) {
        val lock = Any()

        /** 已生成的段（手写 LRU：超过上限丢最旧的）。 */
        val cache = LinkedHashMap<Int, ByteArray>()

        /** 后台正在生成的段（避免同一段重复解码）。 */
        val pending = HashSet<Int>()

        // 建好就算"刚用过"：否则作业加入 jobs 到首次生成之间，时钟差会让它被当成过期作业回收
        var lastAccess = System.currentTimeMillis()
        var released = false
    }

    private val jobs = LinkedHashMap<String, JobState>()
    private val goneIds = LinkedHashMap<String, Long>()
    private val lock = Any()

    // ------------------------------------------------------------- 对外操作

    /**
     * 新建作业并**同步切好前 [cacheSegments] 段**（手表打开插件就要播，不能让它空等）。
     * 第一段就切不出来时抛错并丢弃作业（插件据此回退静态封面）。
     */
    @Throws(MediaError::class)
    fun prepare(owner: String, specIn: VideoSliceSpec): Job {
        val spec = specIn.normalized()
        if (spec.url.isEmpty()) throw MediaError.badRequest("缺少视频地址")
        val scheme = spec.url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") {
            throw MediaError.badRequest("只支持 http/https 视频地址")
        }

        val info = try {
            source.probe(spec)
        } catch (e: MediaError) {
            throw e
        } catch (e: Exception) {
            throw MediaError.internal("探测视频失败: ${e.message ?: e.javaClass.simpleName}")
        }

        val segmentCount = SegmentPlan.segmentCount(spec, info.durationMs)
        if (segmentCount <= 0) throw MediaError.badRequest("起始时间超出视频长度")

        val id = newId()
        val state = JobState(Job(id, owner, spec, info, segmentCount))
        synchronized(lock) {
            expireLocked()
            while (jobs.size >= maxJobs) evictOldestLocked()
            jobs[id] = state
        }

        val warm = minOf(segmentCount, cacheSegments)
        for (i in 0 until warm) {
            try {
                getOrGenerate(state, i, prefetchAhead = false)
            } catch (e: MediaError) {
                if (i == 0) {
                    release(id)
                    throw e
                }
                log("hydrokit: media 作业 $id 预热第 $i 段失败: ${e.message}")
            }
        }
        log("hydrokit: media 作业 $id 就绪（${spec.width}x${spec.height} ${spec.fps}fps ${spec.segmentMs}ms × $segmentCount 段）")
        return state.job
    }

    /** 取第 [index] 段的 GIF；没生成过就**当场生成**（手表取图线程等这几百毫秒）。 */
    @Throws(MediaError::class)
    fun segment(jobId: String, index: Int): ByteArray {
        if (index < 0) throw MediaError.badRequest("分段序号非法")
        val state = findOrThrow(jobId)
        if (index >= state.job.segmentCount) throw MediaError.notFound("第 $index 段超出视频范围")
        return getOrGenerate(state, index, prefetchAhead = true)
    }

    /** 后台预热第 [index] 段起的一段窗口（插件返回树之后调用，不阻塞）。 */
    @Throws(MediaError::class)
    fun hint(jobId: String, index: Int): Map<String, Any?> {
        if (index < 0) throw MediaError.badRequest("分段序号非法")
        val state = findOrThrow(jobId)
        val queued = mutableListOf<Int>()
        val from = index.coerceAtMost(state.job.segmentCount)
        val to = minOf(index + cacheSegments, state.job.segmentCount)
        for (i in from until to) if (queuePrefetch(state, i)) queued.add(i)
        return mapOf(
            "ok" to true,
            "queued" to queued,
            "cached" to cachedIndices(state),
            "segmentCount" to state.job.segmentCount,
        )
    }

    /** 作业状态（插件 WebUI 排障用）。 */
    @Throws(MediaError::class)
    fun status(jobId: String): Map<String, Any?> {
        val state = findOrThrow(jobId)
        synchronized(state.lock) {
            return mapOf(
                "job" to state.job.id,
                "owner" to state.job.owner,
                "url" to state.job.spec.url,
                "durationMs" to state.job.info.durationMs,
                "srcWidth" to state.job.info.width,
                "srcHeight" to state.job.info.height,
                "segmentMs" to state.job.spec.segmentMs,
                "fps" to state.job.spec.fps,
                "width" to state.job.spec.width,
                "height" to state.job.spec.height,
                "segmentCount" to state.job.segmentCount,
                "cached" to cachedIndices(state),
                "pending" to state.pending.sorted(),
                "released" to state.released,
            )
        }
    }

    /** 释放作业（插件关闭界面/退出时调用）：内存立刻回收，之后取段回 410。 */
    fun release(jobId: String): Boolean {
        val state = synchronized(lock) { jobs.remove(jobId) } ?: return false
        synchronized(state.lock) {
            state.released = true
            state.cache.clear()
            state.pending.clear()
        }
        synchronized(lock) { markGoneLocked(state.job.id) }
        log("hydrokit: media 作业 $jobId 已释放")
        return true
    }

    /** 空闲作业过期回收（每次对外操作顺手做一次，不需要额外定时器）。 */
    fun expireIdle() {
        synchronized(lock) { expireLocked() }
    }

    /** 当前存活作业数（测试与排障用）。 */
    fun jobCount(): Int = synchronized(lock) { jobs.size }

    // ------------------------------------------------------------- 内部实现

    private fun newId(): String {
        val raw = java.util.UUID.randomUUID().toString().replace("-", "")
        return raw.substring(0, 12)
    }

    private fun findOrThrow(jobId: String): JobState {
        val state = synchronized(lock) {
            expireLocked()
            jobs[jobId]
        }
        if (state != null) return state
        val gone = synchronized(lock) { goneIds.containsKey(jobId) }
        if (gone) throw MediaError.gone("作业已释放或过期")
        throw MediaError.notFound("未知切片作业")
    }

    private fun getOrGenerate(state: JobState, index: Int, prefetchAhead: Boolean): ByteArray {
        synchronized(state.lock) {
            if (state.released) throw MediaError.gone("作业已释放或过期")
            state.cache[index]?.let {
                touchLocked(state)
                if (prefetchAhead) queuePrefetch(state, index + 1)
                return it
            }
            val gif = generate(state, index)
            state.cache[index] = gif
            trimCacheLocked(state)
            touchLocked(state)
            // 谁在取这一小段，谁就大概率在播它 → 顺手把下一段备好
            if (prefetchAhead) queuePrefetch(state, index + 1)
            return gif
        }
    }

    private fun generate(state: JobState, index: Int): ByteArray {
        val spec = state.job.spec
        val times = SegmentPlan.sampleTimesMs(spec, index, state.job.info.durationMs)
        if (times.isEmpty()) throw MediaError.notFound("第 $index 段超出视频范围")

        val batch = try {
            source.frames(spec, index, state.job.info)
        } catch (e: MediaError) {
            throw e
        } catch (e: Exception) {
            throw MediaError.internal("解码第 $index 段失败: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw MediaError.notFound("第 $index 段超出视频范围")

        if (batch.frameCount <= 0 || batch.rgba.isEmpty()) {
            throw MediaError.notFound("第 $index 段没有可用画面")
        }
        if (batch.frameCount != times.size) {
            // 末段/丢帧时正常，但偏差太大说明解码器有问题，留一条日志
            log("hydrokit: media 第 $index 段期望 ${times.size} 帧、实得 ${batch.frameCount} 帧")
        }

        val gif = GifSlicer.encodeFramesToGif(
            batch.rgba,
            batch.srcWidth,
            batch.srcHeight,
            spec.width,
            spec.height,
            batch.frameCount,
            spec.fps,
        )
        if (gif.isEmpty()) throw MediaError.internal("切片编码失败（第 $index 段）")
        return gif
    }

    /** 提交一段后台预热；已在缓存/已在生成中的段返回 false。 */
    private fun queuePrefetch(state: JobState, index: Int): Boolean {
        synchronized(state.lock) {
            if (state.released) return false
            if (index < 0 || index >= state.job.segmentCount) return false
            if (state.cache.containsKey(index)) return false
            if (!state.pending.add(index)) return false
        }
        executor.execute {
            try {
                getOrGenerate(state, index, prefetchAhead = false)
            } catch (e: Exception) {
                log("hydrokit: media 预热第 $index 段失败: ${e.message}")
            } finally {
                synchronized(state.lock) { state.pending.remove(index) }
            }
        }
        return true
    }

    private fun cachedIndices(state: JobState): List<Int> = synchronized(state.lock) {
        state.cache.keys.sorted()
    }

    private fun trimCacheLocked(state: JobState) {
        while (state.cache.size > cacheSegments) {
            val oldest = state.cache.keys.first()
            state.cache.remove(oldest)
        }
    }

    private fun touchLocked(state: JobState) {
        state.lastAccess = clock()
    }

    private fun expireLocked() {
        val now = clock()
        val dead = jobs.values.filter { now - it.lastAccess > idleTimeoutMs }
        for (state in dead) {
            jobs.remove(state.job.id)
            synchronized(state.lock) {
                state.released = true
                state.cache.clear()
            }
            markGoneLocked(state.job.id)
            log("hydrokit: media 作业 ${state.job.id} 空闲过期")
        }
    }

    private fun evictOldestLocked() {
        val oldest = jobs.values.minByOrNull { it.lastAccess } ?: return
        jobs.remove(oldest.job.id)
        synchronized(oldest.lock) {
            oldest.released = true
            oldest.cache.clear()
        }
        markGoneLocked(oldest.job.id)
        log("hydrokit: media 作业 ${oldest.job.id} 被新作业挤出（上限 $maxJobs）")
    }

    private fun markGoneLocked(id: String) {
        goneIds[id] = clock()
        while (goneIds.size > GONE_MEMORY) {
            val it = goneIds.entries.iterator()
            it.next()
            it.remove()
        }
    }
}
