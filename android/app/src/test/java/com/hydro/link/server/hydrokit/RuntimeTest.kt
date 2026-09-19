package com.hydro.link.server.hydrokit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注册表与路由（对应 desktop/internal/hydrokit/runtime.go 的行为）。 */
class RuntimeTest {

    private class FakePlugin(
        private val m: Manifest,
        private val onView: ((String, String, Request) -> View?)? = null,
    ) : Runtime.Plugin {
        var lastRequest: Request? = null
        override fun manifest(): Manifest = m
        override fun view(surface: String, item: String, req: Request): View? {
            lastRequest = req
            // 注意：不能写成 onView?.invoke(...) ?: 默认值 —— 那样 onView 返回
            // null（"无需更新"）会被 elvis 兜成默认树，测不出真实语义
            if (onView != null) return onView.invoke(surface, item, req)
            return View(root = Node.text("$surface/$item"))
        }
    }

    private val manifest = Manifests.parse(
        """
        {"package":{"id":"com.demo.weather","name":"天气", "author": "测试作者","version":"1.0.0"}, "author": "测试作者",
         "apps":[{"id":"main","name":"天气"}],
         "widgets":[{"id":"card","name":"卡片","refreshMs":30000}]}
        """.trimIndent()
    )

    @Test
    fun registersListsAndLooksUp() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        assertEquals(listOf("com.demo.weather"), rt.pluginIds())
        assertNotNull(rt.lookup("com.demo.weather"))
        val summaries = rt.list()
        assertEquals(1, summaries.size)
        assertEquals("天气", summaries[0].pkg.name)
        assertEquals(1, summaries[0].widgets.size)
    }

    @Test(expected = HydroException::class)
    fun rejectsDuplicateRegistration() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        rt.register(FakePlugin(manifest))
    }

    @Test(expected = HydroException::class)
    fun rejectsInvalidManifest() {
        val bad = Manifest(pkg = Package(id = ""))
        Runtime().register(FakePlugin(bad))
    }

    @Test
    fun unregisterRemovesPlugin() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        rt.unregister("com.demo.weather")
        assertNull(rt.lookup("com.demo.weather"))
        assertEquals(0, rt.list().size)
    }

    @Test
    fun dispatchRoutesToRegisteredItem() {
        val rt = Runtime()
        val p = FakePlugin(manifest)
        rt.register(p)
        val view = rt.dispatch("com.demo.weather", Surface.APP, "main", Request(trigger = Trigger.OPEN))
        assertEquals("app/main", view!!.root.props!!["text"])
    }

    @Test
    fun dispatchPassesScreenAndStore() {
        val rt = Runtime()
        val p = FakePlugin(manifest)
        rt.register(p)
        val req = Request(
            trigger = Trigger.EVENT,
            event = Event(handler = "refresh", source = "tap"),
            screen = Screen(shape = "circle", width = 466, height = 466, dpr = 2.0, deviceType = "watch"),
            store = mutableMapOf("city" to "北京"),
        )
        rt.dispatch("com.demo.weather", Surface.WIDGET, "card", req)
        val seen = p.lastRequest!!
        assertEquals("circle", seen.screen.shape)
        assertEquals(466, seen.screen.width)
        assertEquals("北京", seen.store!!["city"])
        assertEquals("refresh", seen.event!!.handler)
    }

    @Test(expected = HydroException::class)
    fun dispatchRejectsUnknownPackage() {
        Runtime().dispatch("com.nope", Surface.APP, "main", Request(trigger = Trigger.OPEN))
    }

    @Test(expected = HydroException::class)
    fun dispatchRejectsUnknownItem() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        rt.dispatch("com.demo.weather", Surface.APP, "nope", Request(trigger = Trigger.OPEN))
    }

    @Test(expected = HydroException::class)
    fun dispatchRejectsWrongSurfaceForItem() {
        // "card" 是 widget，用 app 表面请求应当失败
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        rt.dispatch("com.demo.weather", Surface.APP, "card", Request(trigger = Trigger.OPEN))
    }

    @Test(expected = HydroException::class)
    fun dispatchRejectsUnknownSurface() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest))
        rt.dispatch("com.demo.weather", "desktop", "main", Request(trigger = Trigger.OPEN))
    }

    @Test
    fun dispatchAllowsNullViewForNoChange() {
        val rt = Runtime()
        rt.register(FakePlugin(manifest) { _, _, _ -> null })
        assertNull(rt.dispatch("com.demo.weather", Surface.APP, "main", Request(trigger = Trigger.TIMER)))
    }

    @Test
    fun nodeHelpersBuildExpectedJson() {
        val node = Node.column(
            Node.text("hi").style("fontSize", 28).media("circle", "fontSize" to 26),
            Node.button("刷新").on("tap", "refresh"),
        )
        val json = com.google.gson.Gson().toJson(node)
        assertTrue(json.contains("\"type\":\"column\""))
        assertTrue(json.contains("\"text\":\"hi\""))
        assertTrue(json.contains("\"fontSize\":28"))
        assertTrue(json.contains("\"circle\":{\"fontSize\":26}"))
        assertTrue(json.contains("\"tap\":\"refresh\""))
    }

    @Test
    fun deepCopyDoesNotShareMutableState() {
        val original = Node.text("a").style("color", "#fff")
        val copy = original.deepCopy()
        copy.style("color", "#000")
        assertEquals("#fff", original.style!!["color"])
    }
}
