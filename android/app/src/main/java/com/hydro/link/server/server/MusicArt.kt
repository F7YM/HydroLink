package com.hydro.link.server.server

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 封面图（`/api/music/art`）的处理与输出。
 *
 * 桌面端 `desktop/internal/api/media.go` 是同一条契约的另一半：
 *
 *   GET /api/music/art                        → 原图
 *   GET /api/music/art?size=466&blur=26&dim=0.3 → 缩到 466、方框模糊、压暗到 30%
 *   GET /api/music/art?w=466&h=466            → cover 裁剪到 466×466
 *
 * 插件通过 ABI 上下文里的 `music.artUrl` / `music.artBlur` 拿到这两个地址
 * （示例插件 deskbuddy 就是拿 artBlur 当手表桌搭背景），所以两端语义必须一致。
 *
 * 这里刻意只做**纯像素运算**（ARGB IntArray，不依赖 Android）：
 * Bitmap ↔ Pixels 的转换留在 [HydroHttpServer]，这样子缩/模糊/压暗的算法
 * 能在 JVM 单测里钉住（真机上一张封面出问题很难调）。
 */
object MusicArtProcessor {

    /** ARGB_8888 像素缓冲（Bitmap 的可测替身）。 */
    class Pixels(val argb: IntArray, val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0) { "尺寸必须为正: ${width}x$height" }
            require(argb.size >= width * height) { "像素数不足: ${argb.size} < ${width * height}" }
        }
    }

    /** 与桌面 `processArt` 同语义：先缩放/裁剪，再模糊，最后压暗。 */
    fun process(src: Pixels, blur: Double, dim: Double, size: Int, w: Int, h: Int): Pixels {
        var out = src
        if (w > 0 && h > 0) {
            out = fill(out, w, h)
        } else if (size > 0 && (src.width > size || src.height > size)) {
            out = fit(out, size)
        }
        if (blur > 0) out = boxBlur(out, blur.roundToInt())
        // 与桌面一致：dim 只在 (0,1) 时生效，表示"保留多少亮度"
        if (dim > 0 && dim < 1) out = dim(out, dim)
        return out
    }

    /**
     * 等比缩放到不超过 max×max（只缩不放）。
     *
     * 取整方式刻意与桌面 `imaging.Fit` 对齐：主导边恰好等于 max，另一边
     * **截断**（不是四舍五入）。同一个插件在两端的背景图尺寸必须一模一样，
     * 否则 466×349 和 466×350 这种 1px 差异会让人怀疑是哪边算错了。
     */
    fun fit(src: Pixels, max: Int): Pixels {
        if (max <= 0) return src
        if (src.width <= max && src.height <= max) return src
        val dw: Int
        val dh: Int
        if (src.width.toDouble() / src.height > 1.0) {
            dw = max
            dh = (max.toDouble() * src.height / src.width).toInt().coerceAtLeast(1)
        } else {
            dh = max
            dw = (max.toDouble() * src.width / src.height).toInt().coerceAtLeast(1)
        }
        return resize(src, dw, dh)
    }

    /** cover 语义：等比放大到铺满 w×h，再从中心裁掉多余部分（同 imaging.Fill）。 */
    fun fill(src: Pixels, w: Int, h: Int): Pixels {
        if (w <= 0 || h <= 0) return src
        val scale = maxOf(w.toDouble() / src.width, h.toDouble() / src.height)
        val sw = (src.width * scale).roundToInt().coerceAtLeast(1)
        val sh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = resize(src, sw, sh)
        if (sw == w && sh == h) return scaled

        val ox = ((sw - w) / 2).coerceAtLeast(0)
        val oy = ((sh - h) / 2).coerceAtLeast(0)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + oy).coerceAtMost(sh - 1)
            System.arraycopy(scaled.argb, sy * sw + ox, out, y * w, w)
        }
        return Pixels(out, w, h)
    }

    /** 双线性缩放（桌面用 Lanczos；这里用双线性，肉眼看不出差别但快得多）。 */
    fun resize(src: Pixels, dw: Int, dh: Int): Pixels {
        if (dw <= 0 || dh <= 0) return src
        if (dw == src.width && dh == src.height) return src
        val out = IntArray(dw * dh)
        val sx = src.width.toDouble() / dw
        val sy = src.height.toDouble() / dh
        for (y in 0 until dh) {
            val fy = (y + 0.5) * sy - 0.5
            val y0f = floor(fy)
            val wy = fy - y0f
            val y0 = y0f.toInt().coerceIn(0, src.height - 1)
            val y1 = (y0 + 1).coerceAtMost(src.height - 1)
            for (x in 0 until dw) {
                val fx = (x + 0.5) * sx - 0.5
                val x0f = floor(fx)
                val wx = fx - x0f
                val x0 = x0f.toInt().coerceIn(0, src.width - 1)
                val x1 = (x0 + 1).coerceAtMost(src.width - 1)

                val p00 = src.argb[y0 * src.width + x0]
                val p01 = src.argb[y0 * src.width + x1]
                val p10 = src.argb[y1 * src.width + x0]
                val p11 = src.argb[y1 * src.width + x1]
                out[y * dw + x] = bilinear(p00, p01, p10, p11, wx, wy)
            }
        }
        return Pixels(out, dw, dh)
    }

    private fun bilinear(p00: Int, p01: Int, p10: Int, p11: Int, wx: Double, wy: Double): Int {
        val w00 = (1 - wx) * (1 - wy)
        val w01 = wx * (1 - wy)
        val w10 = (1 - wx) * wy
        val w11 = wx * wy
        // 逐通道展开而不是建 Pair 列表：466×466 的封面有 21 万个像素，
        // 每次插值少分配 4 个对象，手机上差别明显。
        val a = (((p00 ushr 24) and 0xFF) * w00 + ((p01 ushr 24) and 0xFF) * w01 +
            ((p10 ushr 24) and 0xFF) * w10 + ((p11 ushr 24) and 0xFF) * w11).roundToInt()
        val r = (((p00 ushr 16) and 0xFF) * w00 + ((p01 ushr 16) and 0xFF) * w01 +
            ((p10 ushr 16) and 0xFF) * w10 + ((p11 ushr 16) and 0xFF) * w11).roundToInt()
        val g = (((p00 ushr 8) and 0xFF) * w00 + ((p01 ushr 8) and 0xFF) * w01 +
            ((p10 ushr 8) and 0xFF) * w10 + ((p11 ushr 8) and 0xFF) * w11).roundToInt()
        val b = ((p00 and 0xFF) * w00 + (p01 and 0xFF) * w01 +
            (p10 and 0xFF) * w10 + (p11 and 0xFF) * w11).roundToInt()
        return (clamp255(a) shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
    }

    /**
     * 可分离方框模糊（先横后竖，滑动窗口求和，O(n)）。
     * 桌面用高斯（imaging.Blur，sigma=blur）；这里用同半径的方框模糊近似——
     * 封面模糊只做背景，视觉上等价，但不需要逐像素核卷积（手表那张 466×466
     * 的背景图在手机上要跑得动）。
     */
    fun boxBlur(src: Pixels, radius: Int): Pixels {
        if (radius <= 0) return src
        val r = radius.coerceAtMost(maxOf(src.width, src.height))
        val tmp = IntArray(src.argb.size)
        blurPass(src.argb, tmp, src.width, src.height, r, horizontal = true)
        val out = IntArray(src.argb.size)
        blurPass(tmp, out, src.width, src.height, r, horizontal = false)
        return Pixels(out, src.width, src.height)
    }

    private fun blurPass(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height
        val window = radius * 2 + 1
        for (o in 0 until outer) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            // 预填充窗口（越界按边缘像素处理）
            for (i in -radius..radius) {
                val p = pixelAt(src, width, o, i.coerceIn(0, inner - 1), horizontal)
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until inner) {
                val idx = if (horizontal) o * width + i else i * width + o
                dst[idx] = (clamp255(a / window) shl 24) or
                    (clamp255(r / window) shl 16) or
                    (clamp255(g / window) shl 8) or
                    clamp255(b / window)
                val outP = pixelAt(src, width, o, (i - radius).coerceIn(0, inner - 1), horizontal)
                val inP = pixelAt(src, width, o, (i + radius + 1).coerceIn(0, inner - 1), horizontal)
                a += ((inP ushr 24) and 0xFF) - ((outP ushr 24) and 0xFF)
                r += ((inP ushr 16) and 0xFF) - ((outP ushr 16) and 0xFF)
                g += ((inP ushr 8) and 0xFF) - ((outP ushr 8) and 0xFF)
                b += (inP and 0xFF) - (outP and 0xFF)
            }
        }
    }

    private fun pixelAt(src: IntArray, width: Int, outer: Int, inner: Int, horizontal: Boolean): Int =
        if (horizontal) src[outer * width + inner] else src[inner * width + outer]

    /** 压暗：每个通道乘以 factor（与桌面 blendBlack 一致，alpha 保持不变）。 */
    fun dim(src: Pixels, factor: Double): Pixels {
        val f = factor.coerceIn(0.0, 1.0)
        val out = IntArray(src.argb.size)
        for (i in src.argb.indices) {
            val p = src.argb[i]
            // 用截断而不是四舍五入：桌面是 Go 的 uint8(float64(v)*dim)（截断），
            // 两端同一张图应当算出同一组像素值。
            out[i] = (p and 0xFF000000.toInt()) or
                (clamp255((((p ushr 16) and 0xFF) * f).toInt()) shl 16) or
                (clamp255((((p ushr 8) and 0xFF) * f).toInt()) shl 8) or
                clamp255(((p and 0xFF) * f).toInt())
        }
        return Pixels(out, src.width, src.height)
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}

/**
 * 一张封面：`key` 用于缓存失效（歌曲换了就是新封面），[MusicArtProcessor.Pixels] 是像素。
 */
class MusicArtSource(val key: String, val pixels: MusicArtProcessor.Pixels)

/** `/api/music/art` 的结果（不依赖 NanoHTTPD，便于单测）。 */
class MusicArtResult(val status: Int, val mime: String, val bytes: ByteArray)

/**
 * `/api/music/art`：把当前封面按查询参数处理成图片字节。
 *
 * 参数与桌面完全一致：`size` / `blur` / `dim` / `w` / `h`；
 * **没有任何参数时返回原图**（桌面返回源文件字节，安卓这边是发布方给的位图，
 * 无损编成 PNG）。处理过的图统一 JPEG q86，与桌面一致。
 *
 * 编解码由调用方注入（安卓是 Bitmap.compress），因此本类可以在 JVM 单测里跑。
 */
class MusicArtRoute(
    private val provider: () -> MusicArtSource?,
    private val encodePng: (MusicArtProcessor.Pixels) -> ByteArray,
    private val encodeJpeg: (MusicArtProcessor.Pixels) -> ByteArray,
    private val log: (String) -> Unit = {},
    private val cacheMax: Int = 8,
) {
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > cacheMax
    }

    fun handle(query: Map<String, String>): MusicArtResult {
        val blur = query["blur"]?.toDoubleOrNull() ?: 0.0
        val dim = query["dim"]?.toDoubleOrNull() ?: 0.0
        val size = query["size"]?.toIntOrNull() ?: 0
        val w = query["w"]?.toIntOrNull() ?: 0
        val h = query["h"]?.toIntOrNull() ?: 0

        val source = provider()
            ?: return jsonError(404, "没有可用的封面（播放器没给封面，或未开启通知使用权）")

        // 无参数 = 原图（桌面同样直接回源字节）
        if (blur <= 0 && dim <= 0 && size <= 0) {
            return MusicArtResult(200, "image/png", encodePng(source.pixels))
        }

        val key = "${source.key}|${fmt2(blur)}|${fmt2(dim)}|$size|$w|$h"
        synchronized(cache) { cache[key] }?.let {
            return MusicArtResult(200, "image/jpeg", it)
        }

        val processed = try {
            MusicArtProcessor.process(source.pixels, blur, dim, size, w, h)
        } catch (e: Exception) {
            log("hydrokit: 封面处理失败: ${e.message}")
            return jsonError(500, "封面处理失败: ${e.message}")
        }
        val bytes = try {
            encodeJpeg(processed)
        } catch (e: Exception) {
            return jsonError(500, "封面编码失败: ${e.message}")
        }
        synchronized(cache) { cache[key] = bytes }
        return MusicArtResult(200, "image/jpeg", bytes)
    }

    private fun jsonError(status: Int, message: String): MusicArtResult =
        MusicArtResult(status, "application/json", """{"status":"error","message":"$message"}""".toByteArray())

    /** 缓存 key 里的浮点格式化：固定 Locale，避免小数点在不同区域设置下变成逗号。 */
    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
