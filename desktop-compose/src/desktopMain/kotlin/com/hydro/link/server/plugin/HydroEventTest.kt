package com.hydro.link.server.plugin

import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 真 HTTP → worker 队列 → respond；避免直接调用插件而漏测事件体丢失。 */
fun main() {
    val directory = Files.createTempDirectory("hydro-event-test").toFile()
    val store = Store.open(directory)
    val pkg = "com.test.events"
    val pluginDir = File(store.pluginsDir, pkg).apply { mkdirs() }
    File(pluginDir, "manifest.json").writeText(
        """{"id":"com.test.events","name":"Events","version":"1.0.0","runtime":"js","entry":"server.js","apps":[{"id":"main","name":"Events"}]}"""
    )
    File(pluginDir, "server.js").writeText("module.exports = {};")
    val server = HydroServer(store)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    try {
        server.start(0)
        check(server.runtime.lookup(pkg) != null) { "测试插件未注册" }
        val base = "http://127.0.0.1:${server.port}"
        val bridge = server.runtime.bridge
        bridge.poll(pkg, 1)
        check(bridge.isConnected(pkg))

        fun roundTrip(body: String, query: String = "", header: String? = null): JsonObject {
            val builder = HttpRequest.newBuilder(URI.create("$base/api/hydro/$pkg/app/main/event$query"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (header != null) builder.header("X-Hydro-Store", header)
            val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            val message = Json.parseToJsonElement(bridge.poll(pkg, 3000)).jsonObject
            val envelope = message["req"]?.jsonObject ?: error("事件没有进入 worker: $message")
            check(envelope["surface"]?.jsonPrimitive?.content == "app")
            check(envelope["item"]?.jsonPrimitive?.content == "main")
            val context = envelope.getValue("req").jsonObject
            val result = """{"root":{"type":"text","props":{"text":"下一批"}}}"""
            check(bridge.respond(pkg, envelope.getValue("id").jsonPrimitive.content.toLong(), result, null) == WorkerBridge.RespondResult.Ok)
            val received = response.get(5, TimeUnit.SECONDS)
            check(received.statusCode() == 200) { received.body() }
            check(Json.parseToJsonElement(received.body()) == Json.parseToJsonElement(result))
            return context
        }

        val body = """{"event":{"handler":"refresh","source":"tap","id":"换一批","value":{"text":"中文"}},"params":{"page":"2"},"store":{"origin":"body"}}"""
        val expected = Json.parseToJsonElement(body).jsonObject
        val first = roundTrip(body, "?shape=circle&width=466&height=466&dpr=2")
        check(first["trigger"]?.jsonPrimitive?.content == "event")
        check(first["event"] == expected["event"]) { "handler/source/id/value 未原样转发" }
        check(first["params"] == expected["params"])
        check(first["store"] == expected["store"])
        check(first["screen"]?.jsonObject?.get("width")?.jsonPrimitive?.content == "466")

        val queryStore = """{"origin":"query"}"""
        val headerStore = """{"origin":"header"}"""
        val query = "?store=" + URLEncoder.encode(queryStore, "UTF-8")
        check(roundTrip(body, query)["store"] == Json.parseToJsonElement(queryStore))
        check(roundTrip(body, query, headerStore)["store"] == Json.parseToJsonElement(headerStore))
        check(roundTrip(body, query, "invalid")["store"] == Json.parseToJsonElement(queryStore))
        check(roundTrip(body, "?store=invalid", "invalid")["store"] == expected["store"])

        for (invalid in listOf("{", "[]", "", """{"event":"refresh"}""", """{"params":[]}""", """{"store":42}""")) {
            val request = HttpRequest.newBuilder(URI.create("$base/api/hydro/$pkg/app/main/event"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(invalid)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 400) { "非法请求应返回 400: $invalid → ${response.statusCode()}" }
        }
        check(bridge.poll(pkg, 1) == "{}") { "非法事件不应进入 worker 队列" }
        println("PASS: HTTP 事件字段、中文、存储优先级、非法请求、worker 回包")
    } finally {
        server.stop()
        directory.deleteRecursively()
    }
}
