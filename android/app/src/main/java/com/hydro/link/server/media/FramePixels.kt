package com.hydro.link.server.media

/**
 * 解码后的像素变换（纯函数，可 JVM 单测）。
 *
 * MediaCodec 给的是 `android.media.Image`（YUV 平面 + 各种 stride），不能直接进
 * 单测；所以把"平面 → RGBA"这段算法抽出来：输入只是字节数组和步长，在 JVM 里
 * 可以用手算好的 YUV 值钉住（见 FramePixelsTest）。
 *
 * 颜色变换用整数近似（BT.601 limited range，系数 298/409/100/208/516 是同一套
 * 定点近似，桌面端 FFmpeg 默认也是 limited range）：
 *
 *   R = 1.164(Y-16) + 1.596(V-128)
 *   G = 1.164(Y-16) - 0.391(U-128) - 0.813(V-128)
 *   B = 1.164(Y-16) + 2.018(U-128)
 */
object Yuv420 {

    /**
     * 一个平面视图：字节数组 + 步长。
     *
     * MediaCodec 输出的行步长经常大于宽度（硬件按 16/32 对齐），半平面 NV12 的
     * U/V 还交织在同一块内存里（pixelStride=2、offset 差 1）——所以必须按实际
     * 步长取值，不能假设紧凑排列。
     */
    class Plane(
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int,
        val offset: Int = 0,
    )

    fun toRgba(
        width: Int,
        height: Int,
        y: Plane,
        u: Plane,
        v: Plane,
        hasAlpha: Boolean = false,
        alpha: Plane? = null,
    ): ByteArray {
        if (width <= 0 || height <= 0) return ByteArray(0)
        val out = ByteArray(width * height * 4)
        for (row in 0 until height) {
            val yBase = row * y.rowStride
            val cBase = (row / 2)
            for (col in 0 until width) {
                val yv = sample(y, yBase + col * y.pixelStride)
                val uv = sample(u, cBase * u.rowStride + (col / 2) * u.pixelStride)
                val vv = sample(v, cBase * v.rowStride + (col / 2) * v.pixelStride)

                val yy = (yv - 16).coerceAtLeast(0) * 298
                val uu = uv - 128
                val vv2 = vv - 128

                val idx = (row * width + col) * 4
                out[idx] = clamp8((yy + 409 * vv2 + 128) shr 8)
                out[idx + 1] = clamp8((yy - 100 * uu - 208 * vv2 + 128) shr 8)
                out[idx + 2] = clamp8((yy + 516 * uu + 128) shr 8)
                out[idx + 3] = if (hasAlpha && alpha != null) {
                    sample(alpha, yBase + col * alpha.pixelStride).toByte()
                } else {
                    255.toByte()
                }
            }
        }
        return out
    }

    private fun sample(p: Plane, index: Int): Int {
        val at = p.offset + index
        if (at < 0 || at >= p.bytes.size) return 128 // 越界按灰色处理，别让一帧脏数据把整段搞崩
        return p.bytes[at].toInt() and 0xFF
    }

    private fun clamp8(v: Int): Byte = when {
        v < 0 -> 0.toByte()
        v > 255 -> 255.toByte()
        else -> v.toByte()
    }
}

/** RGBA 缓冲旋转（手机拍的竖屏视频带 rotation 元数据，解码出来还是横的）。 */
object RgbaRotate {

    /** 顺时针 90°（宽高互换）。 */
    fun clockwise90(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(rgba.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 目标坐标 (height-1-y, x)
                val dx = height - 1 - y
                val dy = x
                val src = (y * width + x) * 4
                val dst = (dy * height + dx) * 4
                for (c in 0..3) out[dst + c] = rgba[src + c]
            }
        }
        return out
    }

    /** 顺时针 180°。 */
    fun rotate180(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(rgba.size)
        val pixels = width * height
        for (i in 0 until pixels) {
            val src = i * 4
            val dst = (pixels - 1 - i) * 4
            for (c in 0..3) out[dst + c] = rgba[src + c]
        }
        return out
    }

    /** 顺时针 270°（= 逆时针 90°，宽高互换）。 */
    fun clockwise270(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(rgba.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = y
                val dy = width - 1 - x
                val src = (y * width + x) * 4
                val dst = (dy * height + dx) * 4
                for (c in 0..3) out[dst + c] = rgba[src + c]
            }
        }
        return out
    }

    /** 按 MediaFormat 的 rotation-degrees（0/90/180/270）旋转；其它值原样返回。 */
    fun byDegrees(rgba: ByteArray, width: Int, height: Int, degrees: Int): ByteArray = when (degrees) {
        90 -> clockwise90(rgba, width, height)
        180 -> rotate180(rgba, width, height)
        270 -> clockwise270(rgba, width, height)
        else -> rgba
    }
}
