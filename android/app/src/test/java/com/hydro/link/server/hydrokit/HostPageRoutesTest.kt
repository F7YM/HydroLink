package com.hydro.link.server.hydrokit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主页（worker.html）的**加载契约**回归。
 *
 * 背景：宿主页里的胶水是靠 `<script src=...>` 引入的。宿主页本身的地址是
 * `/api/hydro/worker/host?file=worker.html&…`，浏览器会**按当前 URL 的目录**解析
 * 相对路径，于是 `<script src="worker.js">` 会被解析成
 * `/api/hydro/worker/worker.js` —— 那条路由不存在，脚本 404、胶水一行不执行、
 * worker 永远离线，用户看到的就是手表上那句"加载失败，请检查服务端"。
 *
 * 这一条曾经真的发生过（自动化测试全绿、真机上插件用不了），原因是 Node 端的
 * e2e 用 `globalThis.__HK_CONFIG` 直接跑胶水，从来不经过 HTML 的 script 标签。
 * 所以这里按**浏览器的解析规则**把 src 解析出来，再喂给真路由。
 */
class HostPageRoutesTest {

    private val assetsDir = File("src/main/assets/hydrokit")

    private fun html(): String {
        val f = File(assetsDir, "worker.html")
        // 单测的工作目录是模块目录（app/），所以这个相对路径是稳的；
        // 万一路径变了直接失败，比"测试静默跳过"好。
        assertTrue("找不到宿主页: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun scriptSrc(): String {
        val m = Regex("""<script[^>]*\bsrc\s*=\s*["']([^"']+)["']""").find(html())
        assertNotNull("worker.html 里没有 script src", m)
        return m!!.groupValues[1]
    }

    /** 宿主页在 WebView 里的真实地址（PluginHostManager 拼的那串）。 */
    private val hostUrl =
        "/api/hydro/worker/host?file=worker.html&package=com.demo.weather" +
            "&base=http%3A%2F%2F127.0.0.1%3A9000&entry=server.js&runtime=js"

    @Test
    fun scriptSrcResolvesToTheHostEndpoint() {
        val resolved = java.net.URI("http://127.0.0.1:9000$hostUrl").resolve(scriptSrc())
        assertEquals("/api/hydro/worker/host", resolved.path)
        assertEquals("worker.js", queryOf(resolved.query)["file"])
    }

    @Test
    fun resolvedScriptIsServedByTheRealRoute() {
        val resolved = java.net.URI("http://127.0.0.1:9000$hostUrl").resolve(scriptSrc())
        val routes = HostPageRoutes { name ->
            val f = File(assetsDir, name)
            if (f.isFile) f.readBytes() else null
        }
        val res = routes.handle(
            HydroHttpRequest("GET", resolved.path, query = queryOf(resolved.query))
        )
        assertNotNull("script src 没被宿主页路由接住：$resolved", res)
        assertEquals("script 取不到（浏览器这里会 404，胶水就跑不起来）", 200, res!!.status)
        assertTrue("content-type 应是 JS: ${res.contentType}", res.contentType.contains("javascript"))
        assertTrue("胶水内容异常", res.body.contains("hydrokit"))
    }

    @Test
    fun hostPageItselfIsServed() {
        val routes = HostPageRoutes { name ->
            val f = File(assetsDir, name)
            if (f.isFile) f.readBytes() else null
        }
        val res = routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/host", query = mapOf("file" to "worker.html")))
        assertEquals(200, res!!.status)
        assertTrue(res.contentType.contains("text/html"))
    }

    @Test
    fun hostSliceWasmIsNoLongerServed() {
        // 切片不再由插件侧/宿主页 WASM 提供：宿主自己真解码真编码，分段走
        // /api/hydro/media/<job>/<i>.gif。宿主页白名单里必须把它删掉——留着它
        // 就是留了一条"看起来能用、其实是假切片"的路。
        val routes = HostPageRoutes { name ->
            val f = File(assetsDir, name)
            if (f.isFile) f.readBytes() else null
        }
        val res = routes.handle(HydroHttpRequest("GET", "/api/hydro/worker/host", query = mapOf("file" to "slice.wasm")))
        assertEquals(400, res!!.status)
    }

    @Test
    fun allowlistStillRejectsOtherFiles() {
        val routes = HostPageRoutes { name -> ByteArray(0).takeIf { name == "worker.js" } }
        assertEquals(400, routes.handle(
            HydroHttpRequest("GET", "/api/hydro/worker/host", query = mapOf("file" to "evil.js"))
        )!!.status)
        assertEquals(400, routes.handle(
            HydroHttpRequest("GET", "/api/hydro/worker/host", query = mapOf("file" to "../worker.js"))
        )!!.status)
    }

    private fun queryOf(query: String?): Map<String, String> =
        HydroInterconnect.parseQueryString(query ?: "")
}
