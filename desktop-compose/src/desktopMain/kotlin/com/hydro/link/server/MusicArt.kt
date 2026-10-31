package com.hydro.link.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 封面图（`/api/music/art`）的处理。
 *
 * 桌面端 `desktop/internal/api/media.go` 是同一条契约的另一半：
 *
 *   GET /api/music/art                          → 原图
 *   GET /api/music/art?size=466&blur=26&dim=0.3 → 缩到 466、方框模糊、压暗到 30%
 *   GET /api/music/art?w=466&h=466              → cover 裁剪到 466×466
 *
 * 插件通过 ABI 上下文里的 `music.artUrl` / `music.artBlur` 拿到这两个地址
 * （示例插件 deskbuddy 就是拿 artBlur 当手表桌搭背景），所以三端（Go / 安卓 /
 * 这里）语义必须一致。
 *
 * 这里刻意只做**纯像素运算**（ARGB IntArray，不依赖 ImageIO）：编解码留在
 * [MusicArtCodec]，子缩/模糊/压暗的算法就能在 JVM 单测里钉住（真机上一张封面
 * 出问题很难调）。这份实现与安卓
 * `app/src/main/java/com/hydro/link/server/server/MusicArt.kt` 逐行同源。
 */
object MusicArtProcessor {

    /** ARGB_8888 像素缓冲。 */
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
     * **截断**（不是四舍五入）。同一个插件在 1200×900 的封面上必须得到
     * 466×349，否则两端差 1px 会让人怀疑是哪边算错了。
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
     * 封面模糊只做背景，视觉上等价，但不需要逐像素核卷积。
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

/** 编解码：ImageIO（JVM 自带）。解码失败返回 null，调用方回 415（与 Go 一致）。 */
object MusicArtCodec {

    /** 任意常见格式（PNG/JPEG/GIF/BMP）→ ARGB 像素。 */
    fun decode(bytes: ByteArray): MusicArtProcessor.Pixels? = try {
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        if (img == null || img.width <= 0 || img.height <= 0) {
            null
        } else {
            // 统一转成 ARGB 再取像素：PNG 可能带 alpha，JPEG 是 TYPE_3BYTE_BGR
            val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            val g = rgb.createGraphics()
            g.drawImage(img, 0, 0, null)
            g.dispose()
            val buf = IntArray(img.width * img.height)
            rgb.getRGB(0, 0, img.width, img.height, buf, 0, img.width)
            MusicArtProcessor.Pixels(buf, img.width, img.height)
        }
    } catch (e: Exception) {
        null
    }

    /** ARGB 像素 → JPEG，质量 86（与 Go 的 `jpeg.Options{Quality: 86}` 对齐）。 */
    fun encodeJpeg(pixels: MusicArtProcessor.Pixels): ByteArray {
        val img = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, pixels.width, pixels.height, pixels.argb, 0, pixels.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params: ImageWriteParam = writer.defaultWriteParam
        if (params.canWriteCompressed()) {
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = 0.86f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream: ImageOutputStream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}

/** 一张封面：`key`（artUrl）用于缓存失效，[MusicArtProcessor.Pixels] 是像素。 */
class MusicArtSource(
    val key: String,
    val bytes: ByteArray,
    val mime: String,
    val pixels: MusicArtProcessor.Pixels,
)

/** 取图结果：成功给像素与原始字节，失败给 HTTP 语义（404 取不到 / 415 解不开）。 */
sealed class MusicArtLoad {
    class Ok(val source: MusicArtSource) : MusicArtLoad()
    class Fail(val status: Int, val message: String) : MusicArtLoad()
}

/** `/api/music/art` 的结果（不依赖 HttpServer，便于单测）。 */
class MusicArtResult(val status: Int, val mime: String, val bytes: ByteArray)

/**
 * `kit.music.now()` / `ctx.music` 的 JSON 形状。
 *
 * 字段名是**插件契约**（deskbuddy 直接读 `ctx.music.artUrl` / `music.artBlur`，
 * 为空就退回纯色背景），所以单独拎成纯函数、由单测钉住：
 * 没有封面时必须给空串（而不是给一个必然 404 的地址，让插件反复重试）。
 */
internal fun musicJson(info: SystemCapabilities.Music, base: String, hasArt: Boolean): JsonObject =
    buildJsonObject {
        put("hasMedia", info.hasMedia)
        put("title", info.title)
        put("artist", info.artist)
        put("playing", info.playing)
        // 手表渲染器直接取这两个 URL，带不了设备头，所以 art 端点不做信任校验
        put("artUrl", if (hasArt) "$base/api/music/art" else "")
        put("artBlur", if (hasArt) "$base/api/music/art?size=466&blur=26&dim=0.3" else "")
    }

/**
 * `/api/music/art`：把当前封面按查询参数处理成图片字节。
 *
 * 参数与 Go/安卓完全一致：`size` / `blur` / `dim` / `w` / `h`；
 * **没有任何参数时返回原图**（Go 返回源文件字节，这里同样回原始字节 + 嗅探出的
 * 类型），处理过的图统一 JPEG q86。
 */
class MusicArtRoute(
    /** 当前曲目的封面地址（空串 = 没有封面）。 */
    private val currentKey: () -> String,
    /** 取图 + 解码（昂贵：读盘/HTTP + ImageIO，只在缓存未命中时调用）。 */
    private val loadSource: (String) -> MusicArtLoad,
    private val encodeJpeg: (MusicArtProcessor.Pixels) -> ByteArray,
    private val log: (String) -> Unit = {},
    private val cacheMax: Int = 8,
) {
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > cacheMax
    }

    // 同一首歌的两种参数（artUrl 原图 / artBlur 背景）不重复读盘解码。
    // key 用 artUrl：播放器换了封面就是新的 key，与 Go 的磁盘缓存同一个语义。
    private val memoLock = Any()
    private var memoKey = ""
    private var memoSource: MusicArtSource? = null

    fun handle(query: Map<String, String>): MusicArtResult {
        val blur = query["blur"]?.toDoubleOrNull() ?: 0.0
        val dim = query["dim"]?.toDoubleOrNull() ?: 0.0
        val size = query["size"]?.toIntOrNull() ?: 0
        val w = query["w"]?.toIntOrNull() ?: 0
        val h = query["h"]?.toIntOrNull() ?: 0

        val key = currentKey().trim()
        if (key.isEmpty()) return jsonError(404, "当前曲目没有封面")

        // 无参数 = 原图（与 Go 一致：直接回源字节）
        if (blur <= 0 && dim <= 0 && size <= 0) {
            return when (val load = source(key)) {
                is MusicArtLoad.Ok -> MusicArtResult(200, load.source.mime, load.source.bytes)
                is MusicArtLoad.Fail -> jsonError(load.status, load.message)
            }
        }

        val cacheKey = "$key|${fmt2(blur)}|${fmt2(dim)}|$size|$w|$h"
        synchronized(cache) { cache[cacheKey] }?.let {
            return MusicArtResult(200, "image/jpeg", it)
        }

        val load = source(key)
        if (load is MusicArtLoad.Fail) return jsonError(load.status, load.message)
        val src = (load as MusicArtLoad.Ok).source

        val processed = try {
            MusicArtProcessor.process(src.pixels, blur, dim, size, w, h)
        } catch (e: Exception) {
            log("hydrokit: 封面处理失败: ${e.message}")
            return jsonError(500, "封面处理失败: ${e.message}")
        }
        val bytes = try {
            encodeJpeg(processed)
        } catch (e: Exception) {
            return jsonError(500, "封面编码失败: ${e.message}")
        }
        synchronized(cache) { cache[cacheKey] = bytes }
        return MusicArtResult(200, "image/jpeg", bytes)
    }

    private fun source(key: String): MusicArtLoad = synchronized(memoLock) {
        val cached = memoSource
        if (cached != null && cached.key == key) return MusicArtLoad.Ok(cached)
        val load = loadSource(key)
        if (load is MusicArtLoad.Ok) {
            memoKey = key
            memoSource = load.source
        }
        load
    }

    private fun jsonError(status: Int, message: String): MusicArtResult = MusicArtResult(
        status,
        "application/json",
        (JSON.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("status", "error")
                put("message", message)
            },
        ) + "\n").toByteArray(Charsets.UTF_8),
    )

    /** 缓存 key 里的浮点格式化：固定 Locale，避免小数点在不同区域设置下变成逗号。 */
    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private companion object {
        private val JSON = Json
    }
}
