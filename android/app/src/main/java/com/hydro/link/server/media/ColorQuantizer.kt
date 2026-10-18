package com.hydro.link.server.media

/**
 * 中位切分（Median Cut）调色板量化：RGBA 帧 → 256 色索引图。
 *
 * 与 `crates/hydro-media-wasm/src/quantize.rs` **逐行同源**，包括几个容易抄错、
 * 但直接决定字节是否一致的点：
 *  - 像素数 > 16384 时**按步长 4 采样**建调色板（映射时仍是全量像素）；
 *  - 选盒子切分时用**严格大于**比较，所以多个盒子同长时选**最靠前**那个；
 *  - 按通道排序必须用**稳定排序**（`sortedBy`）；一半按 `len/2` 切；
 *  - 调色板取平均时整数除法**截断**；
 *  - 最近色搜索遇到距离 0 立刻 break，且跳过透明色槽位。
 *
 * 颜色在内部打包成 `0xRRGGBB` 的 Int：稳定排序的键是单个通道，打包不影响
 * 排序结果，但省掉了每个样本一个 IntArray 的开销。
 */
object ColorQuantizer {

    /** 采样建调色板时的步长阈值（与 Rust 相同）。 */
    private const val SAMPLE_THRESHOLD = 16384

    /** 一个像素在打包颜色里的通道偏移。 */
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val SHIFT_B = 0

    class QuantizedFrame(
        /** 256*3 字节 RGB 调色板（不足部分补 0）。 */
        val palette: ByteArray,
        /** 每个像素的调色板索引（长度 = width*height）。 */
        val indices: ByteArray,
        /** 透明色槽位；null 表示不透明。 */
        val transparentIndex: Int?,
    )

    fun quantizeRgba(rgba: ByteArray, width: Int, height: Int): QuantizedFrame {
        val pixelCount = width * height
        val sampled = ArrayList<Int>(minOf(pixelCount, 8192))
        var hasTransparency = false

        val step = if (pixelCount > SAMPLE_THRESHOLD) 4 else 1
        var i = 0
        while (i < pixelCount) {
            val idx = i * 4
            if (idx + 3 < rgba.size) {
                val a = rgba[idx + 3].toInt() and 0xFF
                if (a < 128) {
                    hasTransparency = true
                } else {
                    sampled.add(pack(rgba, idx))
                }
            }
            i += step
        }

        val maxColors = if (hasTransparency) 255 else 256
        val boxes = ArrayList<Box>()
        boxes.add(Box(sampled))

        while (boxes.size < maxColors) {
            var maxIdx = -1
            var maxLen = 0
            for (bi in boxes.indices) {
                val len = boxes[bi].colors.size
                if (len > 1 && len > maxLen) {
                    maxLen = len
                    maxIdx = bi
                }
            }
            if (maxIdx < 0) break
            val target = boxes.removeAt(maxIdx)
            val (b1, b2) = target.split()
            boxes.add(b1)
            boxes.add(b2)
        }

        val paletteColors = ArrayList<Int>(boxes.size)
        for (b in boxes) paletteColors.add(b.averageColor())
        if (paletteColors.isEmpty()) paletteColors.add(0)

        val transparentIdx = if (hasTransparency) {
            val t = paletteColors.size
            paletteColors.add(0) // 透明色占位
            t
        } else {
            null
        }

        val palette = ByteArray(256 * 3)
        for ((pi, c) in paletteColors.withIndex()) {
            if (pi * 3 + 2 >= palette.size) break
            palette[pi * 3] = ((c ushr SHIFT_R) and 0xFF).toByte()
            palette[pi * 3 + 1] = ((c ushr SHIFT_G) and 0xFF).toByte()
            palette[pi * 3 + 2] = ((c ushr SHIFT_B) and 0xFF).toByte()
        }

        val indices = ByteArray(pixelCount)
        for (p in 0 until pixelCount) {
            val idx = p * 4
            if (idx + 3 < rgba.size) {
                val a = rgba[idx + 3].toInt() and 0xFF
                if (a < 128 && transparentIdx != null) {
                    indices[p] = transparentIdx.toByte()
                    continue
                }
                val r = rgba[idx].toInt() and 0xFF
                val g = rgba[idx + 1].toInt() and 0xFF
                val b = rgba[idx + 2].toInt() and 0xFF

                var bestIdx = 0
                var minDist = -1 // Rust 的 u32::MAX：用 -1 表示"还没找到"
                for (pIdx in paletteColors.indices) {
                    if (transparentIdx != null && pIdx == transparentIdx) continue
                    val dist = distSq(paletteColors[pIdx], r, g, b)
                    if (minDist < 0 || dist < minDist) {
                        minDist = dist
                        bestIdx = pIdx
                        if (dist == 0) break
                    }
                }
                indices[p] = bestIdx.toByte()
            } else {
                indices[p] = 0
            }
        }

        return QuantizedFrame(palette, indices, transparentIdx)
    }

    private fun pack(rgba: ByteArray, idx: Int): Int =
        ((rgba[idx].toInt() and 0xFF) shl SHIFT_R) or
            ((rgba[idx + 1].toInt() and 0xFF) shl SHIFT_G) or
            (rgba[idx + 2].toInt() and 0xFF)

    /** Rust `Color::dist_sq`（u32）。 */
    private fun distSq(color: Int, r: Int, g: Int, b: Int): Int {
        val dr = ((color ushr SHIFT_R) and 0xFF) - r
        val dg = ((color ushr SHIFT_G) and 0xFF) - g
        val db = ((color ushr SHIFT_B) and 0xFF) - b
        return dr * dr + dg * dg + db * db
    }

    /** 中位切分用的盒子：一组（打包的）颜色。 */
    private class Box(val colors: MutableList<Int>) {

        /** Rust `Box3D::split`：按最宽通道**稳定排序**后从 len/2 处切开。 */
        fun split(): Pair<Box, Box> {
            if (colors.size <= 1) {
                return Box(colors.toMutableList()) to Box(colors.toMutableList())
            }
            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0
            for (c in colors) {
                val r = (c ushr SHIFT_R) and 0xFF
                val g = (c ushr SHIFT_G) and 0xFF
                val b = (c ushr SHIFT_B) and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
            val rangeR = maxR - minR
            val rangeG = maxG - minG
            val rangeB = maxB - minB
            val channel = when {
                rangeR >= rangeG && rangeR >= rangeB -> SHIFT_R
                rangeG >= rangeR && rangeG >= rangeB -> SHIFT_G
                else -> SHIFT_B
            }
            // sortedBy 是稳定排序：与 Rust 的 sort_by_key 一致（同值保持原顺序）
            colors.sortBy { (it ushr channel) and 0xFF }
            val mid = colors.size / 2
            return Box(colors.subList(0, mid).toMutableList()) to
                Box(colors.subList(mid, colors.size).toMutableList())
        }

        /** Rust `average_color`：u64 求和后整数除法截断。 */
        fun averageColor(): Int {
            if (colors.isEmpty()) return 0
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (c in colors) {
                sumR += ((c ushr SHIFT_R) and 0xFF).toLong()
                sumG += ((c ushr SHIFT_G) and 0xFF).toLong()
                sumB += ((c ushr SHIFT_B) and 0xFF).toLong()
            }
            val n = colors.size.toLong()
            return (((sumR / n).toInt()) shl SHIFT_R) or
                (((sumG / n).toInt()) shl SHIFT_G) or
                ((sumB / n).toInt())
        }
    }
}
