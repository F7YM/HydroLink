package com.hydro.link.server.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/music/art` 的像素处理回归。
 *
 * 契约来自桌面端 `desktop/internal/api/media.go` 的 `processArt`：
 * w/h 都有 → cover 裁剪；否则 size>0 且图更大 → 等比缩进 size×size；
 * blur>0 → 模糊（半径按输出像素）；dim∈(0,1) → 压暗（保留多少亮度）。
 * 插件（示例 deskbuddy）拿 `music.artBlur` 当手表背景，两端语义必须一致。
 */
class MusicArtProcessorTest {

    /** 造一张横向渐变图，方便断言缩放/裁剪结果。 */
    private fun gradient(w: Int, h: Int): MusicArtProcessor.Pixels {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / maxOf(1, w - 1)
                val g = y * 255 / maxOf(1, h - 1)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x40
            }
        }
        return MusicArtProcessor.Pixels(px, w, h)
    }

    private fun solid(w: Int, h: Int, color: Int): MusicArtProcessor.Pixels =
        MusicArtProcessor.Pixels(IntArray(w * h) { color }, w, h)

    @Test
    fun noParamsKeepsOriginalSize() {
        val src = gradient(600, 600)
        val out = MusicArtProcessor.process(src, blur = 0.0, dim = 0.0, size = 0, w = 0, h = 0)
        assertEquals(600, out.width)
        assertEquals(600, out.height)
    }

    @Test
    fun sizeFitsInsideBoxPreservingAspect() {
        // 桌面 imaging.Fit：等比缩到不超过 size×size（只缩不放）
        val out = MusicArtProcessor.process(gradient(1000, 500), 0.0, 0.0, size = 466, w = 0, h = 0)
        assertEquals(466, out.width)
        assertEquals(233, out.height)
    }

    @Test
    fun smallImageIsNotUpscaledBySize() {
        val out = MusicArtProcessor.process(gradient(100, 80), 0.0, 0.0, size = 466, w = 0, h = 0)
        assertEquals(100, out.width)
        assertEquals(80, out.height)
    }

    @Test
    fun wAndHUseCoverCrop() {
        // 宽图 → 裁成正方形（cover），边长取给定的 w/h
        val out = MusicArtProcessor.process(gradient(800, 400), 0.0, 0.0, size = 0, w = 466, h = 466)
        assertEquals(466, out.width)
        assertEquals(466, out.height)
        // 横向渐变被从中间裁了一条：左右两边应当是渐变中段的不同红色
        // （若实现成拉伸，左边缘会是最暗的 0、右边缘是最亮的 255）
        val left = (out.argb[0] ushr 16) and 0xFF
        val right = (out.argb[465] ushr 16) and 0xFF
        assertNotEquals(left, right)
        assertTrue("左边应已被裁掉一段暗部: $left", left > 0)
        assertTrue("右边应已被裁掉一段亮部: $right", right < 255)
    }

    @Test
    fun whTakesEffectWhenCombinedWithSizeBlurDim() {
        // 插件实际发的组合：w/h/size/blur/dim 全给 → cover 裁剪优先于 fit
        val out = MusicArtProcessor.process(gradient(363, 363), blur = 26.0, dim = 0.3, size = 466, w = 800, h = 300)
        assertEquals(800, out.width)
        assertEquals(300, out.height)
    }

    @Test
    fun blurAveragesNeighbourhoods() {
        // 左右两半黑白分明，模糊后交界处应出现中间灰
        val w = 64
        val px = IntArray(w * w)
        for (y in 0 until w) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 0 else 255
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val src = MusicArtProcessor.Pixels(px, w, w)
        val out = MusicArtProcessor.boxBlur(src, 5)
        val at = { x: Int -> out.argb[(w / 2) * w + x] and 0xFF }
        // 交界处不再是纯黑或纯白
        assertTrue("交界像素仍是纯色: ${at(w / 2)}", at(w / 2) in 1..254)
        // 远离交界的地方保持原样
        assertEquals(0, at(2))
        assertEquals(255, at(w - 3))
    }

    @Test
    fun blurOnSolidColorKeepsColor() {
        val out = MusicArtProcessor.boxBlur(solid(32, 32, 0xFF3366CC.toInt()), 4)
        assertEquals(0xFF3366CC.toInt(), out.argb[0])
        assertEquals(0xFF3366CC.toInt(), out.argb[32 * 16 + 16])
    }

    @Test
    fun dimKeepsShareOfBrightnessAndAlpha() {
        val src = solid(8, 8, 0xFF804020.toInt()) // r=128 g=64 b=32
        val out = MusicArtProcessor.dim(src, 0.3)
        val p = out.argb[0]
        assertEquals(0xFF, (p ushr 24) and 0xFF)
        assertEquals(38, (p ushr 16) and 0xFF) // 128 * 0.3
        assertEquals(19, (p ushr 8) and 0xFF)  // 64 * 0.3
        assertEquals(9, p and 0xFF)            // 32 * 0.3
    }

    @Test
    fun dimIsIgnoredAtOrAboveOne() {
        // 与桌面一致：dim 只在 (0,1) 生效
        val src = solid(4, 4, 0xFF804020.toInt())
        assertEquals(0xFF804020.toInt(), MusicArtProcessor.dim(src, 1.0).argb[0])
        assertEquals(0xFF804020.toInt(), MusicArtProcessor.dim(src, 2.0).argb[0])
    }

    @Test
    fun watchBackgroundRecipeMatchesDesktopQuery() {
        // 插件拿到的 artBlur = ?size=466&blur=26&dim=0.3 —— 这条链必须走通
        val out = MusicArtProcessor.process(gradient(1200, 900), blur = 26.0, dim = 0.3, size = 466, w = 0, h = 0)
        assertEquals(466, out.width)
        // 1200x900 等比缩进 466：900 * (466/1200) = 349.5 → **截断** 349。
        // 与桌面 imaging.Fit 完全一致（desktop/internal/api/media_test.go 里
        // 有同一个断言），否则同一插件两端背景图差 1px。
        assertEquals(349, out.height)
        // 压暗后整体亮度明显低于原图
        val src = gradient(1200, 900)
        assertTrue(meanLuma(out) < meanLuma(MusicArtProcessor.fit(src, 466)))
    }

    private fun meanLuma(p: MusicArtProcessor.Pixels): Double {
        var sum = 0.0
        for (i in 0 until p.width * p.height) {
            val v = p.argb[i]
            sum += 0.299 * ((v ushr 16) and 0xFF) + 0.587 * ((v ushr 8) and 0xFF) + 0.114 * (v and 0xFF)
        }
        return sum / (p.width * p.height)
    }
}
