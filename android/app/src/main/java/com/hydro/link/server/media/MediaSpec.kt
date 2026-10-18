package com.hydro.link.server.media

/**
 * 「视频 → GIF 切片」的宿主侧规格与抽象。
 *
 * 这是**平台内置能力**：插件只传参数（哪段视频、怎么切），宿主负责真解码、真编码、
 * 分段下发。插件端不允许自带切片实现（既避免每个插件重造轮子，也避免把 100KB 级
 * 的推流塞进手表那条 16KB 上限的互联通道——这正是之前"点击缓冲播放，显示服务器
 * 无响应"的根因）。
 *
 * 数据流：
 *   kit.media.prepareVideo(url, …)      → 作业 + 分段 URL 列表
 *   手表 GET /api/hydro/media/<job>/<i>.gif → 该段 GIF（懒生成、按需可取）
 *   kit.media.hint(job, i)              → 后台预热第 i 段（不阻塞）
 */

/**
 * 一次切片作业的参数（全部来自插件，宿主会夹到安全范围）。
 *
 * @param url 视频地址（只允许 http/https；本机文件路径不支持，插件需要自己先起服务）
 * @param headers 取流时附带的请求头（Referer / User-Agent 等，B 站这类防盗链必需）
 * @param startMs 从视频的哪一刻开始切
 * @param segmentMs 每段多长（毫秒）
 * @param fps 采样帧率（GIF 就是每帧 100/fps 厘秒）
 * @param width/height 输出尺寸（手表直出尺寸，宿主会等比降采样）
 * @param maxSegments 最多切多少段（防止长视频把 URL 列表撑爆）
 */
data class VideoSliceSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val startMs: Long = 0,
    val segmentMs: Int = DEFAULT_SEGMENT_MS,
    val fps: Int = DEFAULT_FPS,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) {
    companion object {
        /**
         * 保守默认档：240×135、5 fps、每段 1.2 秒。
         * 手表那块小屏 + 蓝牙/WiFi 混合链路上，这个档位一段约 20~40KB，
         * 手表双缓冲播放器刚好来得及在播完当前段前取到下一段。
         */
        const val DEFAULT_SEGMENT_MS = 1200
        const val DEFAULT_FPS = 5
        const val DEFAULT_WIDTH = 240
        const val DEFAULT_HEIGHT = 135
        const val DEFAULT_MAX_SEGMENTS = 30

        const val MIN_SEGMENT_MS = 200
        const val MAX_SEGMENT_MS = 5000
        const val MIN_FPS = 1
        const val MAX_FPS = 20
        const val MIN_SIZE = 32
        const val MAX_SIZE = 480
        const val MAX_SEGMENTS_LIMIT = 120
        const val MAX_HEADERS = 8
        const val MAX_HEADER_CHARS = 2048
    }

    /** 夹到安全范围；参数不合理时**不报错只收敛**（插件传 1000fps 也不会把宿主拖死）。 */
    fun normalized(): VideoSliceSpec {
        val capped = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (capped.size >= MAX_HEADERS) break
            if (k.isBlank()) continue
            val key = k.trim()
            val value = v.trim()
            if (value.length > MAX_HEADER_CHARS) continue
            capped[key] = value
        }
        return copy(
            url = url.trim(),
            headers = capped,
            startMs = startMs.coerceAtLeast(0),
            segmentMs = segmentMs.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS),
            fps = fps.coerceIn(MIN_FPS, MAX_FPS),
            width = width.coerceIn(MIN_SIZE, MAX_SIZE),
            height = height.coerceIn(MIN_SIZE, MAX_SIZE),
            maxSegments = maxSegments.coerceIn(1, MAX_SEGMENTS_LIMIT),
        )
    }
}

/** 视频探测结果（时长未知时 [durationMs] ≤ 0）。 */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

/** 一段采样帧：拼接的多帧 RGBA（`srcWidth * srcHeight * 4 * frameCount` 字节）。 */
class FrameBatch(
    val rgba: ByteArray,
    val srcWidth: Int,
    val srcHeight: Int,
    val frameCount: Int,
)

/**
 * 解码器抽象：由平台实现（安卓是 MediaExtractor + MediaCodec）。
 * 抽出来是为了让作业调度/路由/缓存能在 JVM 单测里跑（用假实现出真帧）。
 */
interface VideoFrameSource {
    /** 探测视频（时长/尺寸）。失败抛异常。 */
    fun probe(spec: VideoSliceSpec): VideoInfo

    /**
     * 取第 [index] 段的采样帧；越出视频末尾返回 null。
     * 采样时刻见 [SegmentPlan.sampleTimesMs]（上下层必须用同一套算法）。
     */
    fun frames(spec: VideoSliceSpec, index: Int, info: VideoInfo): FrameBatch?
}

/** 分段规划：哪一段对应视频里哪些时刻。纯函数，两端（解码器/路由）共用。 */
object SegmentPlan {

    /** 一段里要采多少帧（= 每段时长 × fps）。 */
    fun frameCount(spec: VideoSliceSpec): Int = maxOf(1, spec.segmentMs * spec.fps / 1000)

    /**
     * 这段视频能切几段。时长未知（≤0）时按 [VideoSliceSpec.maxSegments] 处理：
     * 播放器取到越界的分段会拿到 404 自己停住。
     */
    fun segmentCount(spec: VideoSliceSpec, durationMs: Long): Int {
        if (durationMs <= 0) return spec.maxSegments
        val remaining = durationMs - spec.startMs
        if (remaining <= 0) return 0
        val count = ((remaining + spec.segmentMs - 1) / spec.segmentMs).toInt()
        return count.coerceIn(1, spec.maxSegments)
    }

    /** 这一段应当采样的时刻（毫秒）。 */
    fun sampleTimesMs(spec: VideoSliceSpec, index: Int, durationMs: Long): LongArray {
        val segStart = spec.startMs + index.toLong() * spec.segmentMs
        val frames = frameCount(spec)
        val out = LongArray(frames)
        var n = 0
        for (j in 0 until frames) {
            val t = segStart + j.toLong() * 1000L / spec.fps
            if (durationMs > 0 && t >= durationMs) break
            out[n++] = t
        }
        return out.copyOf(n)
    }

    /** 第 [index] 段实际时长（末段可能短于 [VideoSliceSpec.segmentMs]）。 */
    fun segmentDurationMs(spec: VideoSliceSpec, index: Int, durationMs: Long): Int {
        if (durationMs <= 0) return spec.segmentMs
        val segStart = spec.startMs + index.toLong() * spec.segmentMs
        val remaining = durationMs - segStart
        if (remaining <= 0) return 0
        return minOf(spec.segmentMs.toLong(), remaining).toInt()
    }
}

/** 切片作业的错误：带 HTTP 语义，路由层直接映射（400/404/410/500/501）。 */
class MediaError(
    val status: Int,
    message: String,
) : Exception(message) {

    companion object {
        fun badRequest(message: String) = MediaError(400, message)
        fun notFound(message: String) = MediaError(404, message)
        fun gone(message: String) = MediaError(410, message)
        fun internal(message: String) = MediaError(500, message)
        fun unsupported(message: String) = MediaError(501, message)
    }
}
