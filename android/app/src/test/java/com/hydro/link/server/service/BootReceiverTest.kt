package com.hydro.link.server.service

import com.hydro.link.server.model.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun bootCompletedWithSwitchOnStartsService() {
        assertTrue(BootReceiver.shouldStart("android.intent.action.BOOT_COMPLETED", true))
    }

    @Test
    fun switchDefaultsToOffIncludingLegacyConfig() {
        // 默认关：新配置如此；老配置缺 boot_start 字段，反序列化同样按 false 处理
        assertFalse(AppConfig().bootStart)
        assertFalse(BootReceiver.shouldStart("android.intent.action.BOOT_COMPLETED", false))
    }

    @Test
    fun otherBroadcastsNeverStartService() {
        assertFalse(BootReceiver.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", true))
        assertFalse(BootReceiver.shouldStart(null, true))
    }
}
