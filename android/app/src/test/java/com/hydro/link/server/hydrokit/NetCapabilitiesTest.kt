package com.hydro.link.server.hydrokit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hydro.link.server.hydrokit.net.SshManager
import com.hydro.link.server.hydrokit.net.TcpManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetCapabilitiesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.demo.terminal"
    private lateinit var store: PluginStore
    private lateinit var routes: KitRoutes
    private val executor = Executors.newSingleThreadExecutor()

    private class DummyCaps : KitCapabilities {
        override fun perf() = emptyMap<String, Any?>()
        override fun music() = emptyMap<String, Any?>()
        override fun musicAction(action: String) = true
        override fun notify(title: String, text: String) = true
        override fun lock() = true
        override fun commandList() = emptyList<Map<String, Any?>>()
        override fun runCommand(index: Int) = emptyMap<String, Any?>()
        override fun wolWake(mac: String, host: String?, port: Int?) = mapOf("ok" to true)
        override fun wolProbe(host: String, port: Int?, timeout: Int?) = mapOf("online" to true)
        override fun udpSend(host: String, port: Int, data: ByteArray, broadcast: Boolean) = mapOf("ok" to true)
        override fun inputScreen() = mapOf<String, Any?>("width" to 1080, "height" to 2400, "dpr" to 2.75)
        override fun inputCursor() = mapOf<String, Any?>("x" to 540.0, "y" to 1200.0)
        override fun inputMove(x: Float, y: Float) = mapOf("ok" to true)
        override fun inputMoveBy(dx: Float, dy: Float) = mapOf("ok" to true)
        override fun inputClick(x: Float?, y: Float?, button: String, count: Int) = mapOf("ok" to true)
        override fun inputDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = mapOf("ok" to true)
        override fun inputScroll(dx: Float, dy: Float) = mapOf("ok" to true)
        override fun inputKey(key: String) = mapOf("ok" to true)
        override fun inputShortcut(keys: List<String>) = mapOf("ok" to true)
        override fun inputText(text: String) = mapOf("ok" to true)
        override fun serverBase() = "http://127.0.0.1:9000"
    }

    @Before
    fun setup() {
        val root = tmp.newFolder("store")
        store = PluginStore(root)
        val hpk = File(tmp.root, "terminal.hpk")
        val manifest = """
            {
              "id": "$pkg",
              "name": "Terminal",
              "author": "tester",
              "version": "1.0.0",
              "runtime": "js",
              "entry": "server.js",
              "apps": [{"id": "main"}],
              "permissions": ["tcp", "ssh"]
            }
        """.trimIndent()
        ZipOutputStream(hpk.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("server.js"))
            z.write("// code".toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        store.install(hpk)
        routes = KitRoutes(store, WorkerBridge(), DummyCaps())
    }

    @After
    fun tearDown() {
        TcpManager.close(pkg, null)
        SshManager.close(pkg, null)
        executor.shutdownNow()
    }

    private fun post(path: String, body: String): HydroHttpRequest =
        HydroHttpRequest("POST", path, emptyMap(), emptyMap(), body)

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    @Test
    fun tcpLoopbackCommunication() {
        val server = ServerSocket(0)
        val port = server.localPort

        executor.submit {
            try {
                val client = server.accept()
                val buf = ByteArray(1024)
                val n = client.getInputStream().read(buf)
                if (n > 0) {
                    val echo = "ECHO:" + String(buf, 0, n, Charsets.UTF_8)
                    client.getOutputStream().write(echo.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                }
                client.close()
            } catch (_: Exception) {}
            finally {
                server.close()
            }
        }

        val conn = TcpManager.connect(pkg, "127.0.0.1", port, 5000)
        assertTrue(conn.id.startsWith("tcp_"))
        assertEquals("127.0.0.1:$port", conn.remote)

        val written = TcpManager.write(pkg, conn.id, "HELLO", "utf-8")
        assertEquals(5, written)

        val readRes = TcpManager.read(pkg, conn.id, 3000, "utf-8")
        assertEquals("ECHO:HELLO", readRes.data)
        assertFalse(readRes.eof)

        val list = TcpManager.list(pkg)
        assertEquals(1, list.size)
        assertEquals(conn.id, list[0]["id"])

        TcpManager.close(pkg, conn.id)
        assertEquals(0, TcpManager.list(pkg).size)
    }

    @Test
    fun tcpHttpRouteOperations() {
        val server = ServerSocket(0)
        val port = server.localPort

        val connectReq = post(
            "/api/hydro/worker/tcp",
            """{"package":"$pkg","op":"connect","host":"127.0.0.1","port":$port}"""
        )
        val connectResp = routes.handle(connectReq)!!
        assertEquals(200, connectResp.status)
        val connJson = json(connectResp.body)
        assertTrue(connJson.get("ok").asBoolean)
        val id = connJson.get("id").asString

        val closeReq = post(
            "/api/hydro/worker/tcp/close",
            """{"package":"$pkg","id":"$id"}"""
        )
        val closeResp = routes.handle(closeReq)!!
        assertEquals(200, closeResp.status)
        server.close()
    }

    @Test
    fun sshPermissionGateAndValidation() {
        // 1. 未安装插件拒绝
        val uninstalledReq = post(
            "/api/hydro/worker/ssh",
            """{"package":"com.uninstalled.app","op":"open","host":"127.0.0.1","user":"root"}"""
        )
        val uninstalledResp = routes.handle(uninstalledReq)!!
        assertEquals(403, uninstalledResp.status)
        assertTrue(uninstalledResp.body.contains("plugin 不存在或清单不可读"))

        // 2. 已安装但未声明 ssh 权限的插件
        val noPermPkg = "com.demo.noperm"
        val hpkNoPerm = File(tmp.root, "noperm.hpk")
        val manifestNoPerm = """
            {
              "id": "$noPermPkg",
              "name": "NoPerm",
              "author": "tester",
              "version": "1.0.0",
              "runtime": "js",
              "entry": "server.js",
              "apps": [{"id": "main"}],
              "permissions": ["tcp"]
            }
        """.trimIndent()
        ZipOutputStream(hpkNoPerm.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifestNoPerm.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("server.js"))
            z.write("// code".toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        store.install(hpkNoPerm)

        val unauthReq = post(
            "/api/hydro/worker/ssh",
            """{"package":"$noPermPkg","op":"open","host":"127.0.0.1","user":"root"}"""
        )
        val unauthResp = routes.handle(unauthReq)!!
        assertEquals(403, unauthResp.status)
        assertTrue(unauthResp.body.contains("权限不足: ssh"))

        // 缺少参数
        val missingHostReq = post(
            "/api/hydro/worker/ssh",
            """{"package":"$pkg","op":"open","host":"","user":"root"}"""
        )
        val missingHostResp = routes.handle(missingHostReq)!!
        assertEquals(400, missingHostResp.status)
        assertTrue(missingHostResp.body.contains("缺少 host"))

        // 连接不可达主机捕获错误（状态码 500）
        val connFailReq = post(
            "/api/hydro/worker/ssh/open",
            """{"package":"$pkg","host":"127.0.0.1","port":1,"user":"root","password":"123","timeout":500}"""
        )
        val connFailResp = routes.handle(connFailReq)!!
        assertEquals(500, connFailResp.status)
        assertTrue(connFailResp.body.contains("error"))
    }
}
