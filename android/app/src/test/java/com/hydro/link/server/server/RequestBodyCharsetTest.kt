package com.hydro.link.server.server

import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体字符集的回归（真机上的中文乱码根因）。
 *
 * NanoHTTPD 的 `ContentType.getEncoding()` 对非 text 系（`text/…`）的类型默认返回 **US-ASCII**：
 * `Content-Type: application/json` 的 POST 体会被逐字节按 ASCII 解码，中文全变
 * U+FFFD。手表容器的 fetch 和插件胶水发的都是这种头，所以真机上表现为"插件里的
 * 歌名是方块、事件里的中文存进去也是方块"。
 *
 * 这里跑一个**真的 NanoHTTPD**（不是 mock）：既钉住"服务端补 charset 后就正常"，
 * 也钉住"不补就会坏"，后者是这条修复存在的理由——如果哪天 NanoHTTPD 改了默认值，
 * 这条断言会失败并提醒可以删掉归一化逻辑，而不是让人怀疑是不是测试写错了。
 */
class RequestBodyCharsetTest {

    private val chinese = "中文标题·测试🎵"

    /** 起一个真 NanoHTTPD，返回 POST 体（可选择是否做 charset 归一化）。 */
    private fun echoServer(normalize: Boolean): Pair<NanoHTTPD, Int> {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (normalize) {
                    @Suppress("UNCHECKED_CAST")
                    HydroHttpServer.normalizeRequestBodyCharset(session.headers as MutableMap<String, String>)
                }
                val files = HashMap<String, String>()
                session.parseBody(files)
                return newFixedLengthResponse(Response.Status.OK, "text/plain", files["postData"] ?: "")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        return server to server.listeningPort
    }

    private fun post(port: Int, contentType: String, body: String): String {
        val conn = URL("http://127.0.0.1:$port/echo").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        conn.disconnect()
        return text
    }

    private fun run(normalize: Boolean, contentType: String): String {
        val (server, port) = echoServer(normalize)
        try {
            return post(port, contentType, """{"title":"$chinese"}""")
        } finally {
            server.stop()
        }
    }

    @Test
    fun withoutNormalizationNanoHttpdManglesUtf8() {
        // 现状（根因）：application/json 不带 charset → US-ASCII 解码 → U+FFFD
        val received = run(normalize = false, contentType = "application/json")
        assertTrue("预期出现替换字符（这就是 bug 本身）: $received", received.contains('\uFFFD'))
        assertTrue("ASCII 部分不受影响", received.contains("title"))
    }

    @Test
    fun normalizationFixesRequestBodyDecoding() {
        val received = run(normalize = true, contentType = "application/json")
        assertTrue("中文应原样到达: $received", received.contains(chinese))
        assertTrue("不应出现替换字符: $received", !received.contains('\uFFFD'))
    }

    @Test
    fun explicitCharsetIsLeftAlone() {
        // 客户端自己写了 charset 时不动它（大小写、带空格都要认）
        val received = run(normalize = true, contentType = "application/json; charset=UTF-8")
        assertTrue(received.contains(chinese))
    }

    @Test
    fun normalizationOnlyTouchesMissingCharset() {
        val headers = mutableMapOf("Content-Type" to "application/json")
        HydroHttpServer.normalizeRequestBodyCharset(headers)
        assertEquals("application/json; charset=utf-8", headers["Content-Type"])

        val withCharset = mutableMapOf("content-type" to "application/json; charset=gbk")
        HydroHttpServer.normalizeRequestBodyCharset(withCharset)
        assertEquals("客户端指定的 charset 不该被覆盖", "application/json; charset=gbk", withCharset["content-type"])

        val noBody = mutableMapOf("accept" to "*/*")
        HydroHttpServer.normalizeRequestBodyCharset(noBody)
        assertEquals("没有 content-type 就不加键", setOf("accept"), noBody.keys)
    }
}
