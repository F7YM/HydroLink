package com.hydro.link.server.media

import java.io.ByteArrayOutputStream

/**
 * GIF89a 动图编码器（多帧 + 局部调色板 + LZW）。
 *
 * 与 `crates/hydro-media-wasm/src/gif.rs` **逐行同源**：帧结构（Graphic Control
 * Extension 的 disposal/透明位、局部调色板 `0x87`、256 色补零）、Netscape 2.0
 * 无限循环头、以及那段"看起来有点糙但对得上解码器"的 LZW（哈希表 5003、
 * 码长增长判据 `nextCode > (1 shl codeSize)`、字典满 4096 后发 Clear 重置）。
 *
 * 这些细节不是审美问题：golden 对拍要求两端字节完全一致，而真机上 GIF 播不动
 * 时你只能看到黑屏。
 */
class GifFrame(
    val width: Int,
    val height: Int,
    /** 帧延迟，单位 1/100 秒（GIF 的时间单位）。 */
    val delayCentisecs: Int,
    /** 256*3 字节 RGB 调色板。 */
    val palette: ByteArray,
    /** 每个像素的调色板索引，长度 = width*height。 */
    val indices: ByteArray,
    /** 透明色索引；null = 不透明。 */
    val transparentIndex: Int?,
)

class GifEncoder(private val width: Int, private val height: Int, private val loopCount: Int) {

    private val buffer = ByteArrayOutputStream(32768)

    init {
        writeHeader()
    }

    private fun writeHeader() {
        buffer.writeBytes("GIF89a".toByteArray(Charsets.US_ASCII))
        writeU16(width)
        writeU16(height)
        // Packed: 无全局调色板，每帧带自己的局部调色板（各帧画质独立）
        buffer.write(0x70)
        buffer.write(0) // Background color index
        buffer.write(0) // Pixel aspect ratio

        // Netscape 2.0 循环扩展头
        buffer.writeBytes(
            byteArrayOf(
                0x21, 0xFF.toByte(), 0x0B,
                'N'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(),
                'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(),
                '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
                0x03, 0x01,
            ),
        )
        writeU16(loopCount)
        buffer.write(0x00) // Block terminator
    }

    fun addFrame(frame: GifFrame) {
        val hasTrans = frame.transparentIndex != null
        val packedGce = if (hasTrans) 0x09 else 0x04 // 0x04: 保持不销毁；0x01: 透明位
        val transByte = frame.transparentIndex ?: 0

        buffer.writeBytes(byteArrayOf(0x21, 0xF9.toByte(), 0x04, packedGce.toByte()))
        writeU16(frame.delayCentisecs)
        buffer.write(transByte)
        buffer.write(0x00) // Block terminator

        // Image Descriptor
        buffer.write(0x2C)
        writeU16(0) // Left
        writeU16(0) // Top
        writeU16(frame.width)
        writeU16(frame.height)
        buffer.write(0x87) // 局部调色板存在，256 色

        // Local Color Table（不足 768 字节补 0）
        val palLen = minOf(frame.palette.size, 768)
        buffer.write(frame.palette, 0, palLen)
        repeat(768 - palLen) { buffer.write(0) }

        // LZW Image Data
        val lzwMinCodeSize = 8
        buffer.write(lzwMinCodeSize)
        buffer.writeBytes(encodeLzw(lzwMinCodeSize, frame.indices))
        buffer.write(0x00) // Block terminator
    }

    fun finish(): ByteArray {
        buffer.write(0x3B) // Trailer
        return buffer.toByteArray()
    }

    private fun writeU16(value: Int) {
        buffer.write(value and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
    }

    // ---------------------------------------------------------------- LZW

    private fun encodeLzw(minCodeSize: Int, indices: ByteArray): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1

        val writer = BitWriter()
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1

        // 前缀哈希表：(prefix << 8) | nextChar -> code，容量 5003（素数，减少冲突）
        val hashSize = 5003
        val hashKeys = IntArray(hashSize) { -1 } // Rust 的 0xFFFF_FFFF 哨兵（键 ≤ 0xFFFFF）
        val hashValues = IntArray(hashSize)

        writer.writeBits(clearCode, codeSize)
        if (indices.isEmpty()) {
            writer.writeBits(endCode, codeSize)
            return writer.flush()
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var hash = (key xor (key ushr 12)) % hashSize

            var found = false
            while (hashKeys[hash] != -1) {
                if (hashKeys[hash] == key) {
                    prefix = hashValues[hash]
                    found = true
                    break
                }
                hash = (hash + 1) % hashSize
            }

            if (!found) {
                writer.writeBits(prefix, codeSize)
                if (nextCode < 4096) {
                    hashKeys[hash] = key
                    hashValues[hash] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    // 字典满：发 Clear 重置（解码器同步清表）
                    writer.writeBits(clearCode, codeSize)
                    codeSize = minCodeSize + 1
                    nextCode = endCode + 1
                    hashKeys.fill(-1)
                }
                prefix = c
            }
        }

        writer.writeBits(prefix, codeSize)
        writer.writeBits(endCode, codeSize)
        return writer.flush()
    }

    /** GIF 的位流按 255 字节一个子块输出。 */
    private class BitWriter {
        private val data = ByteArrayOutputStream()
        private val subBlock = ByteArray(255)
        private var subLen = 0
        private var currentByte = 0
        private var bitsInByte = 0

        fun writeBits(code: Int, codeLen: Int) {
            currentByte = currentByte or (code shl bitsInByte)
            bitsInByte += codeLen
            while (bitsInByte >= 8) {
                appendByte(currentByte and 0xFF)
                currentByte = currentByte ushr 8
                bitsInByte -= 8
            }
        }

        private fun appendByte(b: Int) {
            subBlock[subLen++] = b.toByte()
            if (subLen == 255) {
                data.write(255)
                data.write(subBlock, 0, subLen)
                subLen = 0
            }
        }

        fun flush(): ByteArray {
            if (bitsInByte > 0) appendByte(currentByte and 0xFF)
            if (subLen > 0) {
                data.write(subLen)
                data.write(subBlock, 0, subLen)
                subLen = 0
            }
            return data.toByteArray()
        }
    }
}

/**
 * 一组 RGBA 帧 → 单个 GIF（`crates/hydro-media-wasm` 的
 * `slice_video_frames_to_gif` 的 Kotlin 版）。
 *
 * 输入是**拼接**的多帧缓冲：`srcW * srcH * 4 * frameCount` 字节。
 * 宿主切片作业直接用它把"某一段视频的采样帧"压成一段 GIF。
 */
object GifSlicer {

    /** 与 Rust 相同：fps 夹到 1..60，延迟按 1/100 秒四舍五入且至少 1。 */
    fun delayCentisecs(fps: Int): Int {
        val safeFps = fps.coerceIn(1, 60)
        val raw = (100f / safeFps.toFloat()).toDouble()
        return maxOf(Math.round(raw).toInt(), 1)
    }

    fun encodeFramesToGif(
        rgba: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        frameCount: Int,
        fps: Int,
    ): ByteArray {
        if (rgba.isEmpty() || frameCount <= 0 || dstWidth <= 0 || dstHeight <= 0) return ByteArray(0)

        val srcFrameBytes = srcWidth * srcHeight * 4
        val delayCs = delayCentisecs(fps)
        val encoder = GifEncoder(dstWidth, dstHeight, 0)

        for (i in 0 until frameCount) {
            val offset = i * srcFrameBytes
            if (offset + srcFrameBytes > rgba.size) break
            val frameSlice = rgba.copyOfRange(offset, offset + srcFrameBytes)

            val downsampled = if (srcWidth == dstWidth && srcHeight == dstHeight) {
                frameSlice
            } else {
                FrameResizer.resizeRgba(frameSlice, srcWidth, srcHeight, dstWidth, dstHeight)
            }

            val quantized = ColorQuantizer.quantizeRgba(downsampled, dstWidth, dstHeight)
            encoder.addFrame(
                GifFrame(
                    width = dstWidth,
                    height = dstHeight,
                    delayCentisecs = delayCs,
                    palette = quantized.palette,
                    indices = quantized.indices,
                    transparentIndex = quantized.transparentIndex,
                ),
            )
        }
        return encoder.finish()
    }
}
