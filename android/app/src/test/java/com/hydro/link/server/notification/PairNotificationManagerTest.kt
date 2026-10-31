package com.hydro.link.server.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairNotificationManagerTest {

    @Test
    fun shortCriticalTextFitsCapsuleLimit() {
        assertEquals("配对请求", PairNotificationManager.SHORT_CRITICAL_TEXT)
        assertTrue(PairNotificationManager.SHORT_CRITICAL_TEXT.length <= 7)
    }

    @Test
    fun formatTitleIncludesDeviceNameAndFallsBackWhenEmptyOrUnknown() {
        assertEquals("配对请求 · Xiaomi Watch S3", PairNotificationManager.formatTitle("Xiaomi Watch S3"))
        assertEquals("收到配对请求", PairNotificationManager.formatTitle(""))
        assertEquals("收到配对请求", PairNotificationManager.formatTitle("   "))
        assertEquals("收到配对请求", PairNotificationManager.formatTitle("Unknown"))
        assertEquals("收到配对请求", PairNotificationManager.formatTitle("unknown"))
    }

    @Test
    fun formatContentDisplaysFullDeviceIdWithoutTruncation() {
        val fullId = "watch_vela_9f8e7d6c5b4a3210_full_identifier"
        assertEquals(fullId, PairNotificationManager.formatContent(fullId))
    }

    @Test
    fun notificationIdForIsDeterministicPerDeviceAndAtLeast1000() {
        val id1 = PairNotificationManager.notificationIdFor("dev-alpha-001")
        val id2 = PairNotificationManager.notificationIdFor("dev-alpha-001")
        val id3 = PairNotificationManager.notificationIdFor("dev-beta-002")
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertTrue(id1 >= 1000)
        assertTrue(id3 >= 1000)
    }
}
