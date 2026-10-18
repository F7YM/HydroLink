package com.hydro.link.server.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YUV → RGBA 与旋转：用手算好的值钉住。
 *
 * 这些变换错了不会抛异常，只会让手表上出现一片偏色或转不过来的画面——所以每个
 * 分支都要有确定的断言（三原色、步长、半平面交织、alpha、旋转）。
 */
class FramePixelsTest {

    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int, offset: Int = 0) =
        Yuv420.Plane(bytes, rowStride, pixelStride, offset)

    /** 紧凑排列（rowStride = 宽、pixelStride = 1）的平面图。 */
    private fun planar(w: Int, h: Int, y: IntArray, u: IntArray, v: IntArray): ByteArray =
        Yuv420.toRgba(
            w, h,
            plane(y.map { it.toByte() }.toByteArray(), w, 1),
            plane(u.map { it.toByte() }.toByteArray(), w / 2, 1),
            plane(v.map { it.toByte() }.toByteArray(), w / 2, 1),
        )

    private fun rgb(bytes: ByteArray, px: Int): Triple<Int, Int, Int> {
        val at = px * 4
        return Triple(
            bytes[at].toInt() and 0xFF,
            bytes[at + 1].toInt() and 0xFF,
            bytes[at + 2].toInt() and 0xFF,
        )
    }

    @Test
    fun bt601LimitedRangeBasics() {
        // 黑（Y=16）/ 白（Y=235）：中性色不该偏色
        val blackWhite = planar(2, 1, intArrayOf(16, 235), intArrayOf(128), intArrayOf(128))
        assertEquals(Triple(0, 0, 0), rgb(blackWhite, 0))
        assertEquals(Triple(255, 255, 255), rgb(blackWhite, 1))
        assertEquals("alpha 默认全不透明", 255, blackWhite[3].toInt() and 0xFF)

        // 标准三原色（BT.601 limited 的常见取值）
        val red = planar(2, 2, intArrayOf(81, 81, 81, 81), intArrayOf(90, 90), intArrayOf(240, 240))
        assertEquals(Triple(255, 0, 0), rgb(red, 0))
        val green = planar(2, 2, intArrayOf(145, 145, 145, 145), intArrayOf(54, 54), intArrayOf(34, 34))
        val g = rgb(green, 0)
        assertTrue("绿应当接近 (0,255,0)，实得 $g", g.first < 8 && g.second > 246 && g.third < 8)
        val blue = planar(2, 2, intArrayOf(41, 41, 41, 41), intArrayOf(240, 240), intArrayOf(110, 110))
        val b = rgb(blue, 0)
        assertTrue("蓝应当接近 (0,0,255)，实得 $b", b.first < 8 && b.second < 8 && b.third > 246)
    }

    @Test
    fun honoursRowStride() {
        // 3×2 图：Y 行步长 8（硬件常按 16 对齐），第二行整行是黑
        val y = ByteArray(8 * 2)
        for (col in 0 until 3) y[0 * 8 + col] = 235.toByte()
        val uv = ByteArray(8 + 2) { 128.toByte() } // 一个色度行，步长 8

        val out = Yuv420.toRgba(3, 2, plane(y, 8, 1), plane(uv, 8, 1), plane(uv, 8, 1))
        assertEquals("第一行应当是白", Triple(255, 255, 255), rgb(out, 0))
        assertEquals("第二行应当是黑（说明行步长被用上了）", Triple(0, 0, 0), rgb(out, 3))
    }

    @Test
    fun honourSemiPlanarInterleaving() {
        // NV12：U/V 交织在同一块内存（pixelStride=2，offset 差 1）。
        // 用 U=128/V=200 验证：交织解析出来的结果必须与"紧凑平面"逐字节相同。
        val w = 2
        val h = 2
        val y = ByteArray(4) { 180.toByte() }
        val uv = ByteArray(2 * 2) // 行步长 4
        for (i in 0 until uv.size step 2) {
            uv[i] = 128.toByte()      // U
            uv[i + 1] = 200.toByte()  // V
        }
        val semi = Yuv420.toRgba(
            w, h,
            plane(y, w, 1),
            plane(uv, 4, 2, offset = 0),
            plane(uv, 4, 2, offset = 1),
        )
        val flat = planar(w, h, IntArray(4) { 180 }, IntArray(1) { 128 }, IntArray(1) { 200 })
        assertTrue("半平面与紧凑平面必须解出同一画面", semi.contentEquals(flat))
        // 顺带确认这确实是个偏红的颜色（不是灰色），否则上面的一致性毫无意义
        val (r, g, b) = rgb(semi, 0)
        assertTrue("V=200 应当偏红：($r,$g,$b)", r > g + 40 && r > b + 40)
    }

    @Test
    fun alphaPlaneIsUsedWhenPresent() {
        val y = ByteArray(4) { 235.toByte() }
        val uv = ByteArray(2) { 128.toByte() }
        val a = byteArrayOf(0, 64, 128.toByte(), 255.toByte())
        val out = Yuv420.toRgba(
            2, 2,
            plane(y, 2, 1), plane(uv, 1, 1), plane(uv, 1, 1),
            hasAlpha = true, alpha = plane(a, 2, 1),
        )
        assertEquals(listOf(0, 64, 128, 255), (0 until 4).map { out[it * 4 + 3].toInt() and 0xFF })
    }

    @Test
    fun missingPlaneFallsBackToGreyInsteadOfCrashing() {
        // 空平面（某些设备的边角情况）：不该抛异常，按中性灰出来（Y 也缺 → 128）
        val out = Yuv420.toRgba(
            2, 2,
            Yuv420.Plane(ByteArray(0), 2, 1),
            Yuv420.Plane(ByteArray(0), 1, 1),
            Yuv420.Plane(ByteArray(0), 1, 1),
        )
        assertEquals(16, out.size)
        val luma = out[0].toInt() and 0xFF
        assertTrue("缺平面时应当是中灰附近，实得 $luma", luma in 128..132)
    }

    @Test
    fun rotationMovesPixelsAndSwapsDimensions() {
        // 2×1 图（左红右绿）顺时针 90° → 1×2（上红下绿）
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
        )
        val r90 = RgbaRotate.clockwise90(rgba, 2, 1)
        assertEquals(1 * 2 * 4, r90.size)
        assertTrue(redish(r90, 0))
        assertTrue(greenish(r90, 4))

        val r270 = RgbaRotate.clockwise270(rgba, 2, 1)
        assertTrue(greenish(r270, 0))
        assertTrue(redish(r270, 4))

        val r180 = RgbaRotate.rotate180(rgba, 2, 1)
        assertTrue(greenish(r180, 0))
        assertTrue(redish(r180, 4))

        // 90° 两次 = 180°
        assertTrue(RgbaRotate.clockwise90(r90, 1, 2).contentEquals(r180))
    }

    @Test
    fun rotationByDegreesUsesMetadataValue() {
        val rgba = byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte())
        assertTrue(rgba.contentEquals(RgbaRotate.byDegrees(rgba, 2, 1, 0)))
        assertTrue(RgbaRotate.clockwise90(rgba, 2, 1).contentEquals(RgbaRotate.byDegrees(rgba, 2, 1, 90)))
        assertTrue(RgbaRotate.rotate180(rgba, 2, 1).contentEquals(RgbaRotate.byDegrees(rgba, 2, 1, 180)))
        assertTrue(RgbaRotate.clockwise270(rgba, 2, 1).contentEquals(RgbaRotate.byDegrees(rgba, 2, 1, 270)))
        // 未知角度原样返回（不猜）
        assertTrue(rgba.contentEquals(RgbaRotate.byDegrees(rgba, 2, 1, 42)))
    }

    private fun redish(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) > 250 && (b[at + 1].toInt() and 0xFF) < 5
    private fun greenish(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) < 5 && (b[at + 1].toInt() and 0xFF) > 250
}
