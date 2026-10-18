package com.hydro.link.server.media

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.IOException
import java.util.concurrent.Semaphore

/**
 * 安卓平台的切片解码器：MediaExtractor（HTTP Range 随机读）+ MediaCodec（硬解）
 * → 真画面 RGBA。
 *
 * 这段代码**必须在真机上验证**：MediaCodec 只在设备上存在，JVM 单测跑不了。
 * 能测的部分已经抽成纯函数（[Yuv420] / [RgbaRotate] / [SegmentPlan] / [GifSlicer]），
 * 这里只负责"把设备解码出来的图搬到纯函数能吃的格式"。
 *
 * 每次取帧新建一个 extractor/codec：段与段之间是独立的 seek，复用解码器省不了多少
 * （重建 codec 约 100~300ms），但**共用解码器会引入状态串味**（前一段的帧、EOS 标记、
 * 格式变化）——切片这种"一次一个短段"的用法，正确性优先。[gate] 限制同时解码数，
 * 避免硬件解码器实例被打满（设备上通常只有 2~4 个）。
 */
class AndroidVideoFrames(
    private val maxConcurrentDecodes: Int = 2,
    private val log: (String) -> Unit = {},
) : VideoFrameSource {

    private val gate = Semaphore(maxConcurrentDecodes)

    override fun probe(spec: VideoSliceSpec): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(HttpMediaDataSource(spec.url, spec.headers))
            val track = selectVideoTrack(extractor)
                ?: throw MediaError.badRequest("视频里没有可解码的画面轨道")
            val format = extractor.getTrackFormat(track)
            var width = format.getIntOr(MediaFormat.KEY_WIDTH, 0)
            var height = format.getIntOr(MediaFormat.KEY_HEIGHT, 0)
            val rotation = format.getIntOr(MediaFormat.KEY_ROTATION, 0)
            if (rotation == 90 || rotation == 270) {
                val t = width
                width = height
                height = t
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (width <= 0 || height <= 0) throw MediaError.badRequest("读不出视频画面尺寸")
            return VideoInfo(
                durationMs = if (durationUs > 0) durationUs / 1000 else -1L,
                width = width,
                height = height,
            )
        } catch (e: MediaError) {
            throw e
        } catch (e: Exception) {
            throw MediaError.internal("读取视频信息失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    override fun frames(spec: VideoSliceSpec, index: Int, info: VideoInfo): FrameBatch? {
        val times = SegmentPlan.sampleTimesMs(spec, index, info.durationMs)
        if (times.isEmpty()) return null
        gate.acquire()
        try {
            return decodeSegment(spec, times)
        } finally {
            gate.release()
        }
    }

    private fun decodeSegment(spec: VideoSliceSpec, times: LongArray): FrameBatch? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val source = HttpMediaDataSource(spec.url, spec.headers)
        try {
            extractor.setDataSource(source)
            val track = selectVideoTrack(extractor)
                ?: throw MediaError.badRequest("视频里没有可解码的画面轨道")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw MediaError.internal("视频轨缺少编码信息")
            val rotation = format.getIntOr(MediaFormat.KEY_ROTATION, 0)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 目标呈现时刻（µs）；先 seek 到第一个目标之前的同步帧，再一路解到最后一个目标
            val targets = LongArray(times.size) { times[it] * 1000L }
            extractor.seekTo(targets[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val frames = ArrayList<ByteArray>(times.size)
            val bufferInfo = MediaCodec.BufferInfo()
            var nextTarget = 0
            var lastFrame: ByteArray? = null
            var frameWidth = 0
            var frameHeight = 0
            var eos = false

            while (!eos && nextTarget < targets.size) {
                val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex)
                    val sampleSize = if (input == null) -1 else extractor.readSampleData(input, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, INPUT_TIMEOUT_US)
                if (outIndex < 0) continue

                if (bufferInfo.size > 0) {
                    val image = codec.getOutputImage(outIndex)
                    if (image != null) {
                        try {
                            val rgba = imageToRgba(image)
                            val w = image.width
                            val h = image.height
                            val rotated = if (rotation == 90 || rotation == 270) {
                                RgbaRotate.byDegrees(rgba, w, h, rotation)
                            } else {
                                rgba
                            }
                            val outW = if (rotation == 90 || rotation == 270) h else w
                            val outH = if (rotation == 90 || rotation == 270) w else h

                            if (frameWidth == 0) {
                                frameWidth = outW
                                frameHeight = outH
                            }
                            if (outW == frameWidth && outH == frameHeight) {
                                lastFrame = rotated
                                // 呈现时刻已到/已过的目标全部用这一帧填上
                                while (nextTarget < targets.size && bufferInfo.presentationTimeUs >= targets[nextTarget]) {
                                    frames.add(rotated)
                                    nextTarget++
                                }
                            } else {
                                log("media: 第 $nextTarget 帧尺寸变化（${outW}x$outH ≠ ${frameWidth}x$frameHeight），跳过")
                            }
                        } finally {
                            image.close()
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            }

            // 视频比目标时刻短（末段常见）：剩下的目标用最后一帧兜底
            val fill = lastFrame
            if (fill != null) {
                while (frames.size < times.size) frames.add(fill)
            }
            if (frames.isEmpty() || frameWidth <= 0) return null

            log("media: 第 ${times.first()}ms 起的段解出 ${frames.size} 帧（${frameWidth}x$frameHeight）")
            val joined = ByteArray(frameWidth * frameHeight * 4 * frames.size)
            for (i in frames.indices) {
                System.arraycopy(frames[i], 0, joined, i * frameWidth * frameHeight * 4, frames[i].size)
            }
            return FrameBatch(joined, frameWidth, frameHeight, frames.size)
        } catch (e: MediaError) {
            throw e
        } catch (e: IOException) {
            throw MediaError.internal("取视频数据失败: ${e.message ?: "网络错误"}")
        } catch (e: Exception) {
            throw MediaError.internal("解码视频失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }

    /** 挑第一个视频轨（含 `video/` 前缀的 mime）。 */
    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    /** `android.media.Image` → RGBA（把平面拷到堆上，Image 关掉后还能用）。 */
    private fun imageToRgba(image: Image): ByteArray {
        val planes = image.planes
        val y = toPlane(planes[0])
        val u = toPlane(planes[1])
        val v = if (planes.size > 2) toPlane(planes[2]) else u
        val alpha = if (planes.size > 3) toPlane(planes[3]) else null
        return Yuv420.toRgba(image.width, image.height, y, u, v, alpha != null, alpha)
    }

    private fun toPlane(plane: Image.Plane): Yuv420.Plane {
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return Yuv420.Plane(bytes, plane.rowStride, plane.pixelStride, 0)
    }

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
