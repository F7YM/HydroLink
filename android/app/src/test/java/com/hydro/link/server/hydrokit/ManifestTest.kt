package com.hydro.link.server.hydrokit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单解析/校验的用例，与桌面端桌面端实现的规则对齐：
 * package.id 必填、至少注册一个 app 或 widget、同 surface 内 item id 唯一。
 */
class ManifestTest {

    @Test
    fun parsesValidManifest() {
        val m = Manifests.parse(
            """
            {
              "package": {"id": "com.demo.weather", "name": "示例天气", "author": "测试作者", "version": "0.2.4"}, "author": "测试作者",
              "apps": [{"id": "main", "name": "示例天气"}],
              "widgets": [{"id": "card", "name": "天气组件", "refreshMs": 30000}],
              "permissions": ["fetch", "storage"],
              "entry": "server.js"
            }
            """.trimIndent()
        )
        assertEquals("com.demo.weather", m.pkg.id)
        assertEquals("0.2.4", m.pkg.version)
        assertEquals(1, m.apps.size)
        assertEquals(30000, m.widgets[0].refreshMs)
        assertEquals(listOf("fetch", "storage"), m.permissions)
        assertTrue(Manifests.hasApp(m, "main"))
        assertFalse(Manifests.hasApp(m, "card"))
        assertTrue(Manifests.hasWidget(m, "card"))
    }

    @Test(expected = HydroException::class)
    fun rejectsMissingPackageId() {
        Manifests.parse("""{"apps":[{"id":"main"}]}""")
    }

    @Test(expected = HydroException::class)
    fun rejectsNoItems() {
        Manifests.parse("""{"package":{"id":"com.a.b"}, "author": "测试作者"}""")
    }

    @Test(expected = HydroException::class)
    fun rejectsDuplicateItemIds() {
        Manifests.parse(
            """{"package":{"id":"com.a.b"}, "author": "测试作者","apps":[{"id":"main"},{"id":"main"}]}"""
        )
    }

    @Test(expected = HydroException::class)
    fun rejectsEmptyItemId() {
        Manifests.parse("""{"package":{"id":"com.a.b"}, "author": "测试作者","apps":[{"name":"没有 id"}]}""")
    }

    @Test(expected = HydroException::class)
    fun rejectsBrokenJson() {
        Manifests.parse("{ this is not json")
    }

    @Test
    fun sameIdIsAllowedAcrossSurfaces() {
        // app 与 widget 各自独立命名空间：两边都叫 main 是合法的
        val m = Manifests.parse(
            """{"package":{"id":"com.a.b"}, "author": "测试作者","apps":[{"id":"main"}],"widgets":[{"id":"main"}]}"""
        )
        assertTrue(Manifests.hasApp(m, "main"))
        assertTrue(Manifests.hasWidget(m, "main"))
    }
}
