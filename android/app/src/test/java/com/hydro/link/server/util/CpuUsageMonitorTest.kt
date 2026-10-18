package com.hydro.link.server.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuUsageMonitorTest {

    @Test
    fun getCpuUsageReturnsValueInValidRange() {
        val usage = CpuUsageMonitor.getCpuUsage()
        assertTrue("CPU usage should be >= 0.0, was $usage", usage >= 0.0f)
        assertTrue("CPU usage should be <= 100.0, was $usage", usage <= 100.0f)
    }

    @Test
    fun getCpuUsageIsConsistentAcrossRapidCallsDueToCache() {
        val first = CpuUsageMonitor.getCpuUsage()
        val second = CpuUsageMonitor.getCpuUsage()
        if (first > 0f) {
            assertEquals("Rapid subsequent call should hit cache", first, second, 0.001f)
        }
    }
}
