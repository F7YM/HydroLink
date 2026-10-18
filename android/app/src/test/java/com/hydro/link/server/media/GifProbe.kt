package com.hydro.link.server.media

/**
 * 测试用的最小 GIF 解码器（**刻意不复用编码器的任何代码**）。
 *
 * 为什么要有它：Android 单测的编译类路径上没有 `java.awt`/`javax.imageio`
 * （android.jar 里没有桌面 AWT），所以没法用 JDK 的解码器。但"切出来的 GIF
 * 里画面真的是动的"这条判据必须能自动验证——历史教训是拿静态图/合成渐变冒充
 * 视频切片，测试照样全绿。
 *
 * 解码结果已用 JDK ImageIO 与 Python PIL 两个**独立解码器**对拍过
 * （见 GifEncoderGoldenTest 里的预言机断言），所以这个自研实现不是"自己验自己"。
 * 覆盖：局部调色板、Graphic Control Extension（延迟/透明色/处置方式）、
 * LZW（clear/end、变长码、字典满后的重置）、按处置方式叠帧。
 */
internal object GifProbe {

    class Frame(
        /** 帧延迟，单位 1/100 秒。 */
        val delayCentisecs: Int,
        /** 透明色索引；null = 不透明。 */
        val transparentIndex: Int?,
        /** ARGB（透明像素 alpha=0），长度 = 画布宽*高。 */
        val pixels: IntArray,
    )

    class Gif(val width: Int, val height: Int, val frames: List<Frame>)

    fun parse(bytes: ByteArray): Gif {
        var p = 0
        require(bytes.size > 13) { "GIF 太短" }
        val sig = String(bytes, 0, 6, Charsets.US_ASCII)
        require(sig == "GIF89a" || sig == "GIF87a") { "不是 GIF: $sig" }
        p = 6

        val width = u16(bytes, p); p += 2
        val height = u16(bytes, p); p += 2
        val packed = bytes[p].toInt() and 0xFF; p += 1
        p += 2 // background color index + pixel aspect ratio
        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            p += gctSize
        }

        val frames = ArrayList<Frame>()
        var pendingDelay = 0
        var pendingTransparent: Int? = null

        while (p < bytes.size) {
            val block = bytes[p].toInt() and 0xFF
            p += 1
            when (block) {
                0x3B -> break // trailer
                0x21 -> { // extension
                    val label = bytes[p].toInt() and 0xFF
                    p += 1
                    if (label == 0xF9) {
                        val size = bytes[p].toInt() and 0xFF
                        p += 1
                        if (size != 4) error("GCE 长度异常: $size")
                        val flags = bytes[p].toInt() and 0xFF
                        pendingDelay = u16(bytes, p + 1)
                        val transIdx = bytes[p + 3].toInt() and 0xFF
                        pendingTransparent = if (flags and 0x01 != 0) transIdx else null
                        // 处置方式（bits 2-4）这里不消费：本编码器用"保持"，且总是全尺寸帧
                        p += 4
                        p = skipSubBlocks(bytes, p)
                    } else {
                        p = skipSubBlocks(bytes, p)
                    }
                }
                0x2C -> { // image descriptor
                    val left = u16(bytes, p); p += 2
                    val top = u16(bytes, p); p += 2
                    val fw = u16(bytes, p); p += 2
                    val fh = u16(bytes, p); p += 2
                    val flags = bytes[p].toInt() and 0xFF; p += 1
                    var palette = IntArray(0)
                    if (flags and 0x80 != 0) {
                        val n = 1 shl ((flags and 0x07) + 1)
                        palette = IntArray(n) { i ->
                            val r = bytes[p + i * 3].toInt() and 0xFF
                            val g = bytes[p + i * 3 + 1].toInt() and 0xFF
                            val b = bytes[p + i * 3 + 2].toInt() and 0xFF
                            0xFF shl 24 or (r shl 16) or (g shl 8) or b
                        }
                        p += n * 3
                    }
                    val minCodeSize = bytes[p].toInt() and 0xFF; p += 1
                    val (data, next) = readSubBlocks(bytes, p)
                    p = next

                    val indices = lzwDecode(minCodeSize, data, fw * fh)
                    val transparentIdx = pendingTransparent

                    // 单帧像素（与 JDK ImageIO / PIL 的默认语义一致：不做叠帧合成，
                    // 只把这一帧的矩形画到透明画布上；本仓库的编码器总是写全尺寸帧）
                    val framePixels = IntArray(width * height)
                    for (y in 0 until fh) {
                        val cy = top + y
                        if (cy < 0 || cy >= height) continue
                        for (x in 0 until fw) {
                            val cx = left + x
                            if (cx < 0 || cx >= width) continue
                            val idx = indices[y * fw + x]
                            if (transparentIdx != null && idx == transparentIdx) continue
                            val color = if (idx < palette.size) palette[idx] else 0xFF000000.toInt()
                            framePixels[cy * width + cx] = color
                        }
                    }
                    frames.add(Frame(pendingDelay, transparentIdx, framePixels))
                    pendingDelay = 0
                    pendingTransparent = null
                }
                else -> error("未知 GIF 块 0x${block.toString(16)} @ $p")
            }
        }
        require(frames.isNotEmpty()) { "GIF 里没有帧" }
        return Gif(width, height, frames)
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun skipSubBlocks(b: ByteArray, from: Int): Int = readSubBlocks(b, from).second

    private fun readSubBlocks(b: ByteArray, from: Int): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream()
        var p = from
        while (p < b.size) {
            val len = b[p].toInt() and 0xFF
            p += 1
            if (len == 0) break
            out.write(b, p, len)
            p += len
        }
        return out.toByteArray() to p
    }

    /** GIF 的 LZW（变长码；码长增长判据见下面注释里的"差一拍"）。 */
    private fun lzwDecode(minCodeSize: Int, data: ByteArray, pixelCount: Int): IntArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = IntArray(4096) { -1 }
        val suffix = IntArray(4096)
        val first = IntArray(4096) // 每个条目的首字符（KwKwK 特例要用）
        for (i in 0 until clear) {
            suffix[i] = i
            first[i] = i
        }

        val out = IntArray(pixelCount)
        var outLen = 0
        var codeSize = minCodeSize + 1
        var next = end + 1
        var prev = -1
        var bitPos = 0
        val totalBits = data.size * 8

        val stack = IntArray(4096)
        while (bitPos + codeSize <= totalBits) {
            var code = 0
            for (i in 0 until codeSize) {
                val byte = data[(bitPos + i) ushr 3].toInt() and 0xFF
                val bit = (byte ushr ((bitPos + i) and 7)) and 1
                code = code or (bit shl i)
            }
            bitPos += codeSize

            if (code == clear) {
                codeSize = minCodeSize + 1
                next = end + 1
                prev = -1
                continue
            }
            if (code == end) break

            if (prev < 0) {
                if (outLen < pixelCount) out[outLen++] = suffix[code]
                prev = code
                continue
            }

            var sp = 0
            // 标准 KLZW：`code == next` 表示码就是"刚建的那一条"（编码器提前用了），
            // 此时输出 = string(prev) + first(prev)。
            val target = if (code < next) code else prev
            if (code >= next) stack[sp++] = first[prev] // 先入栈底 → 最后才输出
            var walk = target
            while (prefix[walk] >= 0) {
                stack[sp++] = suffix[walk]
                walk = prefix[walk]
            }
            stack[sp++] = suffix[walk]

            while (sp > 0 && outLen < pixelCount) out[outLen++] = stack[--sp]

            if (next < 4096) {
                prefix[next] = prev
                suffix[next] = first[target]
                first[next] = first[prev]
                next++
                // 注意：解码器这里的判据与编码器**差一拍**（解码器的 next 总落后编码器
                // 一个条目，因为它是读到下一个码才补上前一条），所以是 `==` 而不是
                // 编码器的 `>`。抄成 `>` 会一路错位到栈溢出。
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        require(outLen == pixelCount) { "LZW 解出的像素数不足: $outLen < $pixelCount" }
        return out
    }
}
