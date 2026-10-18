package com.hydro.link.server.media

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Kotlin 切片器（宿主内置能力）与 Rust WASM 参考实现（`crates/hydro-media-wasm`）
 * 的**逐字节**对拍。
 *
 * golden 由 `crates/hydro-media-wasm/test/make-golden.mjs` 生成（用仓库里的 wasm
 * 跑同一组确定性帧），落在 `app/src/test/resources/media/`。改了任何一边的
 * 缩放/量化/LZW 都会被这里按字节抓住——两端算出的 GIF 不同，就说明"移植成功"
 * 只是嘴上说说。
 *
 * 还有两条独立判据：
 *  1. [producedGifsCarryRealPerFrameMotion]：用 [GifProbe]（独立最小解码器）解出
 *     像素，确认相邻帧**真的不同**（旧实现拿静态画面/合成渐变冒充视频切片就是
 *     栽在这里），并有反向对照：同一张画面重复 N 帧必须被判成"无运动"。
 *  2. [probeMatchesRealDecoders]：把探针解出的像素和 **JDK ImageIO / Python PIL
 *     两个真实解码器**的取值对拍（值见下面断言里的注释，来自离线核对），证明
 *     探针不是"自己验自己"。
 */
class GifEncoderGoldenTest {

    private val dir = File("src/test/resources/media")

    private data class Case(
        val name: String,
        val srcWidth: Int,
        val srcHeight: Int,
        val dstWidth: Int,
        val dstHeight: Int,
        val frames: Int,
        val fps: Int,
        val pattern: String,
        val transparentMod: Int,
        val bytes: Int,
    )

    private fun loadCases(): List<Case> {
        val specFile = File(dir, "golden-spec.json")
        assertTrue(
            "缺少 golden（先在 crates/hydro-media-wasm 跑 node test/make-golden.mjs）: ${specFile.absolutePath}",
            specFile.isFile,
        )
        val arr = JsonParser.parseString(specFile.readText()).asJsonArray
        return arr.map { el ->
            val o = el.asJsonObject
            Case(
                name = o.get("name").asString,
                srcWidth = o.get("srcWidth").asInt,
                srcHeight = o.get("srcHeight").asInt,
                dstWidth = o.get("dstWidth").asInt,
                dstHeight = o.get("dstHeight").asInt,
                frames = o.get("frames").asInt,
                fps = o.get("fps").asInt,
                pattern = o.get("pattern").asString,
                transparentMod = if (o.has("transparentMod")) o.get("transparentMod").asInt else 0,
                bytes = o.get("bytes").asInt,
            )
        }.also {
            assertTrue("golden-spec.json 里应当有用例", it.isNotEmpty())
        }
    }

    /** 逐行复刻 make-golden.mjs 的帧生成（整数算法，两端必须一模一样）。 */
    private fun buildFrame(w: Int, h: Int, f: Int, pattern: String, transparentMod: Int): ByteArray {
        val buf = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                when (pattern) {
                    "gradient" -> {
                        buf[i] = ((x * 255) / (w - 1)).toByte()
                        buf[i + 1] = ((y * 255) / (h - 1)).toByte()
                        buf[i + 2] = ((((x shr 2) + (y shr 2)) * 2 + f * 13) and 0xFF).toByte()
                        buf[i + 3] = 255.toByte()
                    }
                    "noise" -> {
                        buf[i] = ((x * 7 + f * 41) and 0xFF).toByte()
                        buf[i + 1] = ((y * 13 + f * 29) and 0xFF).toByte()
                        buf[i + 2] = ((x * y + f * 97) and 0xFF).toByte()
                        buf[i + 3] = 255.toByte()
                    }
                    else -> fail("未知 pattern: $pattern（生成器加了新用例，测试要同步实现）")
                }
            }
        }
        if (transparentMod > 0) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if ((x * 3 + y * 5 + f) % transparentMod == 0) buf[(y * w + x) * 4 + 3] = 0
                }
            }
        }
        return buf
    }

    private fun joined(c: Case): ByteArray {
        val frameBytes = c.srcWidth * c.srcHeight * 4
        val out = ByteArray(frameBytes * c.frames)
        for (f in 0 until c.frames) {
            System.arraycopy(
                buildFrame(c.srcWidth, c.srcHeight, f, c.pattern, c.transparentMod),
                0, out, f * frameBytes, frameBytes,
            )
        }
        return out
    }

    private fun encode(c: Case): ByteArray =
        GifSlicer.encodeFramesToGif(
            joined(c), c.srcWidth, c.srcHeight, c.dstWidth, c.dstHeight, c.frames, c.fps,
        )

    // ------------------------------------------------------------------ 对拍

    @Test
    fun kotlinEncoderMatchesRustGoldenByteForByte() {
        for (c in loadCases()) {
            val gif = encode(c)
            val golden = File(dir, "${c.name}.gif")
            assertTrue("缺少 golden 文件: ${golden.absolutePath}", golden.isFile)
            val expected = golden.readBytes()

            if (!gif.contentEquals(expected)) {
                val at = firstDiff(gif, expected)
                fail(
                    "${c.name}: 与 Rust golden 不一致 —— " +
                        "实得 ${gif.size}B / 期望 ${expected.size}B，首个不同字节 @$at " +
                        "(实得 ${gif.getOrNull(at)?.toInt()?.and(0xFF)} / " +
                        "期望 ${expected.getOrNull(at)?.toInt()?.and(0xFF)})；" +
                        "缩放/量化/LZW 任一处抄错都会在这里红",
                )
            }
            assertEquals("${c.name}: 记录的大小应当与产物一致", c.bytes, gif.size)
        }
    }

    private fun firstDiff(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return n
    }

    // ------------------------------------------------------------------ 结构与"真画面"

    @Test
    fun producedGifsCarryRealPerFrameMotion() {
        for (c in loadCases()) {
            val gif = encode(c)
            assertTrue("${c.name}: 应以 GIF89a 开头", String(gif, 0, 6, Charsets.US_ASCII) == "GIF89a")
            assertEquals("${c.name}: 应以 0x3B 结尾", 0x3B, gif.last().toInt().and(0xFF))

            val probe = GifProbe.parse(gif)
            assertEquals("${c.name}: 画布尺寸", c.dstWidth, probe.width)
            assertEquals("${c.name}: 画布尺寸", c.dstHeight, probe.height)
            assertEquals("${c.name}: 帧数", c.frames, probe.frames.size)

            val expectedDelay = maxOf(Math.round(100f / c.fps.coerceIn(1, 60)), 1)
            for ((i, frame) in probe.frames.withIndex()) {
                assertEquals("${c.name}: 第 $i 帧延迟（1/100 秒）", expectedDelay, frame.delayCentisecs)
                if (i == 0) continue
                val diff = diffRatio(probe.frames[i - 1].pixels, frame.pixels)
                assertTrue(
                    "${c.name}: 第 ${i + 1} 帧与前一帧几乎一样（diff=$diff）—— " +
                        "说明切片里是静态画面冒充的动图",
                    diff > 0.01,
                )
            }

            // golden 自身也要动，否则"对拍通过"毫无意义
            val goldenProbe = GifProbe.parse(File(dir, "${c.name}.gif").readBytes())
            assertEquals("${c.name}: golden 帧数", c.frames, goldenProbe.frames.size)
            assertTrue(
                "${c.name}: golden 自身相邻帧应当不同（生成器用例退化了）",
                diffRatio(goldenProbe.frames[0].pixels, goldenProbe.frames[1].pixels) > 0.01,
            )
        }
    }

    /**
     * 把探针解出的像素与真实解码器（JDK `ImageIO` / Python `PIL`，两个独立实现）
     * 对拍。值来自离线核对（两个解码器给的主像素一致）：
     *
     *   golden-small 32x18：帧 0 (0,1)=#040700、(16,9)=#878718、(31,17)=#FCFA2E、
     *                       (7,3)=#362708，透明像素 11 个（帧 1 = 7 个，帧 2 = 9 个）
     *   golden-video 240x135：帧 0 (0,1)=#030707、(120,67)=#7D87BC、(239,134)=#F7F569
     *                       帧 1 (120,67)=#7E88CA，帧 2 (120,67)=#8775D6
     */
    @Test
    fun probeMatchesRealDecoders() {
        val small = GifProbe.parse(File(dir, "golden-small.gif").readBytes())
        assertEquals(3, small.frames.size)
        assertEquals(0xFF040700.toInt(), small.frames[0].pixels[1 * 32 + 0])
        assertEquals(0xFF878718.toInt(), small.frames[0].pixels[9 * 32 + 16])
        assertEquals(0xFFFCFA2E.toInt(), small.frames[0].pixels[17 * 32 + 31])
        assertEquals(0xFF362708.toInt(), small.frames[0].pixels[3 * 32 + 7])
        assertEquals("透明像素数（帧 0）", 11, transparentCount(small.frames[0].pixels))
        assertEquals("透明像素数（帧 1）", 7, transparentCount(small.frames[1].pixels))
        assertEquals("透明像素数（帧 2）", 9, transparentCount(small.frames[2].pixels))
        assertEquals("透明色槽位应当被 GCE 登记", true, small.frames[0].transparentIndex != null)

        val video = GifProbe.parse(File(dir, "golden-video.gif").readBytes())
        assertEquals(0xFF030707.toInt(), video.frames[0].pixels[1 * 240 + 0])
        assertEquals(0xFF7D87BC.toInt(), video.frames[0].pixels[67 * 240 + 120])
        assertEquals(0xFFF7F569.toInt(), video.frames[0].pixels[134 * 240 + 239])
        assertEquals(0xFF7E88CA.toInt(), video.frames[1].pixels[67 * 240 + 120])
        assertEquals(0xFF8775D6.toInt(), video.frames[2].pixels[67 * 240 + 120])
        assertEquals(0, transparentCount(video.frames[0].pixels))
    }

    /**
     * 负向对照：**同一张画面重复 N 帧**压出来的 GIF，"相邻帧不同"的判据必须能识别。
     *
     * 这条测试是给 [producedGifsCarryRealPerFrameMotion] 做证伪的——历史教训是
     * "测试全绿"不等于真能用：旧实现拿合成渐变的静态图冒充视频动图，测试照样全绿。
     */
    @Test
    fun repeatedStaticFrameIsDetectedAsFake() {
        val w = 32
        val h = 18
        val one = buildFrame(w, h, 0, "gradient", 0)
        val joined = ByteArray(one.size * 3)
        for (f in 0 until 3) System.arraycopy(one, 0, joined, f * one.size, one.size)

        val probe = GifProbe.parse(GifSlicer.encodeFramesToGif(joined, w, h, w, h, 3, 5))
        assertEquals(3, probe.frames.size)
        assertEquals(
            "同一张画面重复 3 帧时，相邻帧应当完全相同（判据必须报 0）",
            0.0,
            diffRatio(probe.frames[0].pixels, probe.frames[1].pixels),
            0.0,
        )
        assertFalse(
            "同一张画面重复时不该被判成'有运动'",
            diffRatio(probe.frames[0].pixels, probe.frames[1].pixels) > 0.01,
        )
    }

    private fun transparentCount(pixels: IntArray): Int =
        pixels.count { (it ushr 24) == 0 }

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 1.0
        var changed = 0
        for (i in a.indices) if (a[i] != b[i]) changed++
        return changed.toDouble() / a.size
    }
}
