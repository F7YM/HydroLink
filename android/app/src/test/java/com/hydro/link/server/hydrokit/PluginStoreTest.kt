package com.hydro.link.server.hydrokit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插件包仓库的用例：安装（含顶层目录包）、重装/升级/降级、卸载保留存储、
 * 入口与 runtime 判定、路径逃逸防护。语义对齐 desktop/internal/store/plugins.go。
 */
class PluginStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PluginStore(tmp.newFolder("data"))

    /** 造一个 .hpk：entries 为 (路径 -> 内容)，可按需套一层顶层目录。 */
    private fun makeHpk(dir: File, name: String, entries: Map<String, String>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return f
    }

    /** 造一个只含 manifest + server.js 的 .hpk（清单新字段的用例用它）。 */
    private fun buildPackage(manifest: String): File =
        makeHpk(tmp.root, "p-${System.nanoTime()}.hpk", mapOf(
            "manifest.json" to manifest,
            "server.js" to "module.exports = {}",
        ))

    // 注意：安装包内的 manifest.json 是**扁平**结构（id 在顶层），
    // 与协议里 /api/hydro/list 返回的嵌套 {package:{...}} 不同 —— 见
    // desktop/internal/store/plugins.go 的 PluginManifest。
    private val jsManifest = """
        {"id":"com.demo.weather","name":"示例天气", "author": "测试作者","version":"0.2.4",
         "apps":[{"id":"main","name":"示例天气"}],
         "widgets":[{"id":"card","name":"天气组件","refreshMs":30000}],
         "permissions":["fetch","storage"],"entry":"server.js"}
    """.trimIndent()

    @Test
    fun installsJsPlugin() {
        val s = store()
        val hpk = makeHpk(tmp.root, "w.hpk", mapOf(
            "manifest.json" to jsManifest,
            "server.js" to "module.exports = {}",
            "web/index.html" to "<html></html>",
        ))
        val info = s.install(hpk)
        assertEquals("com.demo.weather", info.id)
        assertEquals("示例天气", info.name)
        assertEquals("0.2.4", info.version)
        assertEquals("js", info.runtime)
        assertEquals("server.js", info.entry)
        assertTrue(info.hasWeb)
        assertEquals(listOf("fetch", "storage"), info.permissions)
        assertEquals(1, info.apps.size)
        assertEquals(30000, info.widgets[0].refreshMs)
        assertTrue(File(info.dir, "server.js").isFile)
        assertEquals("module.exports = {}", s.entryCode("com.demo.weather"))
    }

    @Test
    fun installsZipWithTopLevelDirectory() {
        val s = store()
        val hpk = makeHpk(tmp.root, "w.hpk", mapOf(
            "com.demo.weather/manifest.json" to jsManifest,
            "com.demo.weather/server.js" to "code",
        ))
        val info = s.install(hpk)
        assertEquals("com.demo.weather", info.id)
        assertTrue(File(info.dir, "server.js").isFile)
        // 顶层目录不应残留在插件目录里
        assertFalse(File(info.dir, "com.demo.weather").exists())
    }

    @Test
    fun installsWasmPlugin() {
        val s = store()
        val wasmManifest = """
            {"id":"com.hydro.deskbuddy","name":"桌搭", "author": "测试作者","version":"0.7.1",
             "apps":[{"id":"main","name":"桌搭"}],"runtime":"wasm","permissions":["music","storage"]}
        """.trimIndent()
        val hpk = makeHpk(tmp.root, "w.hpk", mapOf(
            "manifest.json" to wasmManifest,
            "plugin.wasm" to "\u0000asm",
        ))
        val info = s.install(hpk)
        assertEquals("wasm", info.runtime)
        assertEquals("plugin.wasm", info.entry)
    }

    @Test(expected = HydroException::class)
    fun rejectsWasmEntryThatIsNotWasm() {
        val s = store()
        val bad = """
            {"id":"com.a.b","name":"x", "author": "测试作者","version":"1.0.0",
             "apps":[{"id":"main"}],"runtime":"wasm","entry":"server.js"}
        """.trimIndent()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("manifest.json" to bad, "server.js" to "x")))
    }

    @Test(expected = HydroException::class)
    fun rejectsMissingEntryFile() {
        val s = store()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("manifest.json" to jsManifest)))
    }

    @Test(expected = HydroException::class)
    fun rejectsMissingManifest() {
        val s = store()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("server.js" to "x")))
    }

    @Test(expected = HydroException::class)
    fun rejectsInvalidPackageId() {
        val s = store()
        val bad = """{"id":"../evil","name":"x", "author": "测试作者","version":"1.0.0","apps":[{"id":"main"}]}"""
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("manifest.json" to bad, "server.js" to "x")))
    }

    @Test(expected = HydroException::class)
    fun rejectsPathTraversalEntry() {
        val s = store()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf(
            "manifest.json" to jsManifest,
            "server.js" to "x",
            "../escaped.txt" to "boom",
        )))
    }

    @Test(expected = HydroException::class)
    fun rejectsUnsupportedExtension() {
        val s = store()
        val f = File(tmp.root, "pkg.txt")
        f.writeText("not a zip")
        s.install(f)
    }

    @Test
    fun reinstallAndUpgradeReplaceInPlace() {
        val s = store()
        s.install(makeHpk(tmp.root, "v1.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "v1")))
        assertEquals("v1", s.entryCode("com.demo.weather"))

        // 同版本重装
        s.install(makeHpk(tmp.root, "same.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "v1b")))
        assertEquals("v1b", s.entryCode("com.demo.weather"))

        // 升级
        val upgraded = jsManifest.replace("0.2.4", "0.3.0")
        val info = s.install(makeHpk(tmp.root, "v2.hpk", mapOf("manifest.json" to upgraded, "server.js" to "v2")))
        assertEquals("0.3.0", info.version)
        assertEquals("v2", s.entryCode("com.demo.weather"))
        assertEquals(1, s.list().size)
    }

    @Test(expected = HydroException::class)
    fun rejectsDowngrade() {
        val s = store()
        val upgraded = jsManifest.replace("0.2.4", "0.3.0")
        s.install(makeHpk(tmp.root, "v2.hpk", mapOf("manifest.json" to upgraded, "server.js" to "v2")))
        s.install(makeHpk(tmp.root, "v1.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "v1")))
    }

    @Test
    fun uninstallKeepsPluginStorage() {
        val s = store()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "x")))
        s.storeSet("com.demo.weather", "city", "北京")
        assertEquals(true to "北京", s.storeGet("com.demo.weather", "city"))

        assertTrue(s.uninstall("com.demo.weather"))
        assertNull(s.info("com.demo.weather"))
        assertTrue(s.list().isEmpty())
        // 存储保留
        assertEquals(true to "北京", s.storeGet("com.demo.weather", "city"))
    }

    @Test(expected = HydroException::class)
    fun uninstallUnknownPluginFails() {
        store().uninstall("com.nope.nope")
    }

    @Test
    fun storageCrud() {
        val s = store()
        val (found, value) = s.storeGet("com.a.b", "missing")
        assertFalse(found)
        assertNull(value)

        s.storeSet("com.a.b", "n", 42)
        s.storeSet("com.a.b", "s", "文本")
        assertEquals(true to 42.0, s.storeGet("com.a.b", "n")) // JSON 数字读回是 Double
        assertEquals(true to "文本", s.storeGet("com.a.b", "s"))

        s.storeRemove("com.a.b", "n")
        assertFalse(s.storeGet("com.a.b", "n").first)
        assertEquals(1, s.storeAll("com.a.b").size)
    }

    @Test
    fun readFileRejectsEscape() {
        val s = store()
        s.install(makeHpk(tmp.root, "w.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "x")))
        assertNotNull(s.readFile("com.demo.weather", "server.js"))
        assertNull(s.readFile("com.demo.weather", "../../manifest.json"))
        assertNull(s.readFile("com.demo.weather", "/etc/passwd"))
    }

    @Test
    fun listReportsBrokenPluginWithoutFailingOthers() {
        val s = store()
        s.install(makeHpk(tmp.root, "ok.hpk", mapOf("manifest.json" to jsManifest, "server.js" to "x")))
        // 手工造一个损坏的插件目录
        val broken = File(s.pluginsDir, "com.broken.plugin").apply { mkdirs() }
        File(broken, "manifest.json").writeText("{ not json")

        val all = s.list()
        assertEquals(2, all.size)
        val good = all.first { it.id == "com.demo.weather" }
        assertNull(good.error)
        val bad = all.first { it.id == "com.broken.plugin" }
        assertNotNull(bad.error)
    }

    // 真实发布的插件包（仓库 desktop/examples 下的 .hpk）必须能装进 Android 端。
// 注意：Kotlin 的块注释可嵌套，注释里不要写 "斜杠+星号" 的组合，
// 否则会把后面的代码整段注释掉（刚踩过）。
    private fun realHpk(name: String): File? {
        val candidates = listOf(
            File("../../desktop/examples/$name"),
            File("../desktop/examples/$name"),
            File("desktop/examples/$name"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun installsRealWeatherPackage() {
        val hpk = realHpk("com.demo.weather.hpk")
        org.junit.Assume.assumeTrue("仓库里没有 weather .hpk，跳过", hpk != null)
        val info = store().install(hpk!!)
        assertEquals("com.demo.weather", info.id)
        assertEquals("js", info.runtime)
        assertEquals("server.js", info.entry)
        assertTrue("安装包带 web/index.html", info.hasWeb)
        assertTrue(info.apps.isNotEmpty())
        assertNotNull(info.permissions)
    }

    @Test
    fun installsRealDeskBuddyPackage() {
        // 桌搭是 JS 插件（它自己用 kit.ws 连 SPlayer 拿歌词）
        val hpk = realHpk("com.hydro.deskbuddy.hpk")
        org.junit.Assume.assumeTrue("仓库里没有 deskbuddy .hpk，跳过", hpk != null)
        val s = store()
        val info = s.install(hpk!!)
        assertEquals("com.hydro.deskbuddy", info.id)
        assertEquals("js", info.runtime)
        assertEquals("server.js", info.entry)
        assertTrue("server.js 应当解压出来", File(info.dir, "server.js").length() > 0)
        assertTrue("安装包带 web/index.html", info.hasWeb)
        assertTrue(info.permissions.contains("music"))
    }

    @Test
    fun installsRealWasmFixturePackage() {
        val hpk = realHpk("com.hydro.wasmfixture.hpk")
        org.junit.Assume.assumeTrue("仓库里没有 wasm 夹具 .hpk，跳过", hpk != null)
        val s = store()
        val info = s.install(hpk!!)
        assertEquals("com.hydro.wasmfixture", info.id)
        assertEquals("wasm", info.runtime)
        assertEquals("plugin.wasm", info.entry)
        assertTrue("plugin.wasm 应当解压出来", File(info.dir, "plugin.wasm").length() > 0)
    }

    @Test
    fun installsRealHydroBiliPackage() {
        val hpk = realHpk("com.hydro.bili.hpk")
        org.junit.Assume.assumeTrue("仓库里没有 bili .hpk，跳过", hpk != null)
        val s = store()
        val info = s.install(hpk!!)
        assertEquals("com.hydro.bili", info.id)
        assertEquals("HydroBili", info.name)
        assertEquals("js", info.runtime)
        assertEquals("server.js", info.entry)
        assertTrue("server.js 应当解压出来", File(info.dir, "server.js").length() > 0)
        assertTrue("assets/slice.wasm 切片模块应当解压出来", File(info.dir, "assets/slice.wasm").length() > 0)
        assertTrue("安装包带 web/index.html", info.hasWeb)
        assertTrue(info.permissions.contains("storage"))
        assertTrue(info.permissions.contains("fetch"))
    }

    @Test
    fun comparesVersionsLikeDesktop() {
        assertEquals(0, PluginStore.compareVersions("1.0", "1.0.0"))
        assertEquals(-1, PluginStore.compareVersions("1.9.0", "1.10.0"))
        assertEquals(1, PluginStore.compareVersions("2.0.0", "1.99.99"))
        assertEquals(-1, PluginStore.compareVersions("1.0.0-beta", "1.0.0"))
        assertEquals(0, PluginStore.compareVersions(" 1.2.3 ", "1.2.3"))
    }

    // ---- 清单新字段：author 必填、minHydroLink 卡宿主版本 ----

    @Test
    fun installRequiresAuthor() {
        val hpk = buildPackage("""{"id":"com.t.noauthor","name":"没作者","version":"0.1.0",
            "entry":"server.js","apps":[{"id":"main","name":"主页"}]}""")
        val e = runCatching { store().install(hpk) }.exceptionOrNull()
        assertNotNull("缺 author 应当拒绝安装", e)
        assertTrue("报错要说清是 author: ${e?.message}", (e?.message ?: "").contains("author"))
    }

    @Test
    fun installRejectsNewerMinHydroLink() {
        val old = PluginStore.hostVersion
        PluginStore.hostVersion = "1.1.0"
        val s = store()
        try {
            val tooNew = buildPackage("""{"id":"com.t.newer","name":"要新宿主","author":"A","version":"0.1.0",
                "minHydroLink":"1.2.0","entry":"server.js","apps":[{"id":"main","name":"主页"}]}""")
            val e = runCatching { s.install(tooNew) }.exceptionOrNull()
            assertNotNull("minHydroLink 高于宿主应当拒绝", e)
            assertTrue("报错要说明版本要求: ${e?.message}", (e?.message ?: "").contains("1.2.0"))

            val ok = buildPackage("""{"id":"com.t.samever","name":"同版本","author":"A","version":"0.1.0",
                "minHydroLink":"1.1.0","entry":"server.js","apps":[{"id":"main","name":"主页"}]}""")
            val info = s.install(ok)
            assertEquals("com.t.samever", info.id)
            assertEquals("A", info.author)
        } finally {
            PluginStore.hostVersion = old
        }
    }

}
