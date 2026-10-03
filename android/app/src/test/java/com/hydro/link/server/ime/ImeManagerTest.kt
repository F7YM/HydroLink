package com.hydro.link.server.ime

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeManagerTest {

    @Test
    fun testFallbackSingleHorizontalStroke() {
        // 单笔横线：从 (10, 50) 到 (90, 50)
        val stroke = listOf(10f to 50f, 50f to 50f, 90f to 50f)
        val candidates = FallbackHandwritingEngine.recognize(listOf(stroke), 8)
        assertFalse(candidates.isEmpty())
        assertTrue("单横笔画应识别出 '一'", candidates.contains("一"))
    }

    @Test
    fun testFallbackCrossStrokes() {
        // 两笔横竖交叉：横 (10, 50) -> (90, 50)，竖 (50, 10) -> (50, 90)
        val stroke1 = listOf(10f to 50f, 90f to 50f)
        val stroke2 = listOf(50f to 10f, 50f to 90f)
        val candidates = FallbackHandwritingEngine.recognize(listOf(stroke1, stroke2), 8)
        assertFalse(candidates.isEmpty())
        assertTrue("十字笔画应识别出 '十'", candidates.contains("十"))
    }

    @Test
    fun testImeManagerJsonParsingAndRecognition() = runBlocking {
        val jsonStr = """
            {
              "strokes": [
                [[10, 50], [90, 50]],
                [[50, 10], [50, 90]]
              ],
              "width": 320,
              "height": 260,
              "max": 5
            }
        """.trimIndent()
        val bodyJson = JsonParser.parseString(jsonStr).asJsonObject
        val candidates = ImeManager.recognize(bodyJson)
        assertFalse(candidates.isEmpty())
        assertTrue(candidates.size <= 5)
        assertTrue(candidates.contains("十"))
    }

    @Test
    fun testFallbackSingleHookStroke() {
        // 折笔/乙：(10, 20) -> (80, 20) -> (20, 80) -> (80, 80)
        val stroke = listOf(10f to 20f, 80f to 20f, 20f to 80f, 80f to 80f)
        val candidates = FallbackHandwritingEngine.recognize(listOf(stroke), 5)
        assertFalse(candidates.isEmpty())
        assertTrue("折笔应识别出 '乙'", candidates.contains("乙"))
    }

    @Test
    fun testFallbackMultiStrokes() {
        // 三笔横线："三"
        val s1 = listOf(20f to 20f, 80f to 20f)
        val s2 = listOf(30f to 50f, 70f to 50f)
        val s3 = listOf(10f to 80f, 90f to 80f)
        val candidates = FallbackHandwritingEngine.recognize(listOf(s1, s2, s3), 5)
        assertFalse(candidates.isEmpty())
        assertTrue("三横笔画应识别出 '三'", candidates.contains("三"))
    }

    @Test
    fun testImeManagerEmptyStrokesGracefulHandling() = runBlocking {
        val jsonStr = """{"strokes": [], "max": 8}"""
        val bodyJson = JsonParser.parseString(jsonStr).asJsonObject
        val candidates = ImeManager.recognize(bodyJson)
        assertEquals(0, candidates.size)

        val nullJsonCandidates = ImeManager.recognize(null)
        assertEquals(0, nullJsonCandidates.size)
    }
}
