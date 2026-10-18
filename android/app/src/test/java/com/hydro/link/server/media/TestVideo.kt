package com.hydro.link.server.media

import java.io.IOException

/**
 * 单测用的假「视频」：按**时刻**算出画面（纯整数，可复现）。
 *
 * 画面随时间是**真的在变**（白条平移 + 底色随 t 变化）——这是刻意的：如果假数据
 * 每一帧都一样，「切片里有没有真画面」这件事永远测不出来。历史上旧实现拿静态封面
 * 冒充视频切片，测试全绿而真机上一片死板，就是这么栽的。
 */
internal class TestVideo(
    private val durationMs: Long,
    val srcWidth: Int = 320,
    val srcHeight: Int = 180,
    private val failProbe: Boolean = false,
    private val failFrames: Boolean = false,
) : VideoFrameSource {

    var probeCalls = 0
    var framesCalls = 0

    /** 每一次取帧被要求采样的时刻（钉住分段规划，两端算法必须一致）。 */
    val timesCalls = mutableListOf<LongArray>()

    override fun probe(spec: VideoSliceSpec): VideoInfo {
        probeCalls++
        if (failProbe) throw IOException("假解码器：探测失败")
        return VideoInfo(durationMs, srcWidth, srcHeight)
    }

    override fun frames(spec: VideoSliceSpec, index: Int, info: VideoInfo): FrameBatch? {
        if (failFrames) throw IOException("假解码器：取帧失败")
        val times = SegmentPlan.sampleTimesMs(spec, index, info.durationMs)
        if (times.isEmpty()) return null
        framesCalls++
        timesCalls.add(times)
        val frameBytes = srcWidth * srcHeight * 4
        val out = ByteArray(frameBytes * times.size)
        for (j in times.indices) {
            System.arraycopy(renderAt(times[j]), 0, out, j * frameBytes, frameBytes)
        }
        return FrameBatch(out, srcWidth, srcHeight, times.size)
    }

    /** 白色竖条按时间平移；底色三通道都随 t 变化。 */
    fun renderAt(t: Long): ByteArray {
        val buf = ByteArray(srcWidth * srcHeight * 4)
        val barLeft = ((t / 40) % srcWidth).toInt()
        val tint = ((t / 20) % 200).toInt()
        for (y in 0 until srcHeight) {
            for (x in 0 until srcWidth) {
                val i = (y * srcWidth + x) * 4
                val inBar = x >= barLeft && x < barLeft + 40
                buf[i] = (if (inBar) 250 else 20 + x * 180 / srcWidth).toByte()
                buf[i + 1] = (if (inBar) 250 else 30 + y * 160 / srcHeight).toByte()
                buf[i + 2] = (if (inBar) 250 else 40 + tint).toByte()
                buf[i + 3] = 255.toByte()
            }
        }
        return buf
    }
}
