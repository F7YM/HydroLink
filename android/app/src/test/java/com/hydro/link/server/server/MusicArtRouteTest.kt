package com.hydro.link.server.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/music/art` 的路由语义：无参数回原图、带参数走处理、没封面 404 JSON、
 * 同样的参数第二次命中缓存。编解码是注入的假实现，所以不需要 Android Bitmap。
 */
class MusicArtRouteTest {

    private fun pixels(w: Int, h: Int): MusicArtProcessor.Pixels =
        MusicArtProcessor.Pixels(IntArray(w * h) { 0xFF102030.toInt() }, w, h)

    private class Harness(source: MusicArtSource?) {
        var current = source
        var pngCalls = 0
        var jpegCalls = 0
        val route = MusicArtRoute(
            provider = { current },
            encodePng = { pngCalls++; byteArrayOf(1, 2, 3) },
            encodeJpeg = { pngCalls = pngCalls; jpegCalls++; byteArrayOf(4, 5, 6) },
        )
    }

    @Test
    fun returnsOriginalPngWithoutParams() {
        val h = Harness(MusicArtSource("song-a", pixels(600, 600)))
        val res = h.route.handle(emptyMap())
        assertEquals(200, res.status)
        assertEquals("image/png", res.mime)
        assertEquals(1, h.pngCalls)
        assertEquals(0, h.jpegCalls)
    }

    @Test
    fun blurredRequestGoesThroughJpegPath() {
        val h = Harness(MusicArtSource("song-a", pixels(1200, 1200)))
        val res = h.route.handle(mapOf("size" to "466", "blur" to "26", "dim" to "0.3"))
        assertEquals(200, res.status)
        assertEquals("image/jpeg", res.mime)
        assertEquals(0, h.pngCalls)
        assertEquals(1, h.jpegCalls)
    }

    @Test
    fun sameParamsHitCacheButNewSongMisses() {
        val h = Harness(MusicArtSource("song-a", pixels(800, 800)))
        val q = mapOf("size" to "466", "blur" to "26", "dim" to "0.3")
        h.route.handle(q)
        h.route.handle(q)
        assertEquals("同样参数应命中缓存", 1, h.jpegCalls)
        // 换歌 → 缓存 key 变了，必须重新处理
        h.current = MusicArtSource("song-b", pixels(800, 800))
        h.route.handle(q)
        assertEquals(2, h.jpegCalls)
    }

    @Test
    fun noCoverReturnsJson404() {
        val h = Harness(null)
        val res = h.route.handle(mapOf("size" to "466"))
        assertEquals(404, res.status)
        assertEquals("application/json", res.mime)
        assertTrue(String(res.bytes), String(res.bytes).contains("\"status\":\"error\""))
    }

    @Test
    fun garbageParamsBehaveLikeNoParams() {
        // 手表/插件给错参数不该 500：解析失败按 0 处理 → 回原图
        val h = Harness(MusicArtSource("song-a", pixels(64, 64)))
        val res = h.route.handle(mapOf("size" to "abc", "blur" to "", "dim" to "x", "w" to "0", "h" to "0"))
        assertEquals(200, res.status)
        assertEquals("image/png", res.mime)
    }

    @Test
    fun encoderFailureBecomesJsonError() {
        val route = MusicArtRoute(
            provider = { MusicArtSource("song", pixels(600, 600)) },
            encodePng = { byteArrayOf() },
            encodeJpeg = { throw IllegalStateException("oom") },
        )
        val res = route.handle(mapOf("size" to "466"))
        assertEquals(500, res.status)
        assertEquals("application/json", res.mime)
        assertTrue(String(res.bytes), String(res.bytes).contains("封面编码失败"))
    }
}
