package com.hydro.link.server.media

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 双线性缩放（RGBA，原地降采样到手表尺寸）。
 *
 * 与 `crates/hydro-media-wasm/src/resize.rs` **逐行同源**：采样点是"角对齐"
 * （`x_ratio = (srcW-1)/(dstW-1)`，而不是按像素中心），系数用 **Float** 累加。
 * 这两点都是为了让 Kotlin 与 Rust 算出的字节完全一致（golden 对拍会红）——
 * 用 Double 或改成中心对齐，肉眼看不出来，但 golden 会立刻对不上。
 */
object FrameResizer {

    fun resizeRgba(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (dstW <= 0 || dstH <= 0) return ByteArray(0)
        if (srcW == dstW && srcH == dstH) {
            val len = minOf(srcW * srcH * 4, src.size)
            return src.copyOf(len)
        }

        val dst = ByteArray(dstW * dstH * 4)
        // Rust: if dstW > 1 { (srcW-1) as f32 / (dstW-1) as f32 } else { 0.0 }
        val xRatio = if (dstW > 1) (srcW - 1).toFloat() / (dstW - 1).toFloat() else 0f
        val yRatio = if (dstH > 1) (srcH - 1).toFloat() / (dstH - 1).toFloat() else 0f

        for (y in 0 until dstH) {
            val srcY = y.toFloat() * yRatio
            val yLowF = floor(srcY.toDouble()).toInt()
            val yLow = if (yLowF < 0) 0 else yLowF
            val yHigh = minOf(yLow + 1, srcH - 1)
            val yWeight = srcY - yLow.toFloat()

            for (x in 0 until dstW) {
                val srcX = x.toFloat() * xRatio
                val xLowF = floor(srcX.toDouble()).toInt()
                val xLow = if (xLowF < 0) 0 else xLowF
                val xHigh = minOf(xLow + 1, srcW - 1)
                val xWeight = srcX - xLow.toFloat()

                val idxTl = (yLow * srcW + xLow) * 4
                val idxTr = (yLow * srcW + xHigh) * 4
                val idxBl = (yHigh * srcW + xLow) * 4
                val idxBr = (yHigh * srcW + xHigh) * 4
                val dstIdx = (y * dstW + x) * 4

                for (c in 0..3) {
                    val tl = byteAt(src, idxTl + c).toFloat()
                    val tr = byteAt(src, idxTr + c).toFloat()
                    val bl = byteAt(src, idxBl + c).toFloat()
                    val br = byteAt(src, idxBr + c).toFloat()

                    val top = tl * (1f - xWeight) + tr * xWeight
                    val bottom = bl * (1f - xWeight) + br * xWeight
                    val pixel = top * (1f - yWeight) + bottom * yWeight

                    // Rust: pixel.round().clamp(0.0, 255.0) as u8
                    dst[dstIdx + c] = pixel.roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return dst
    }

    /** Rust 的 `src.get(i).copied().unwrap_or(0)`：越界读 0。 */
    private fun byteAt(src: ByteArray, index: Int): Int =
        if (index in src.indices) src[index].toInt() and 0xFF else 0
}
