package com.hydro.link.compose.pair

import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import com.hydro.link.shared.AppModel
import com.hydro.link.shared.data.HttpTransport
import com.hydro.link.shared.data.HydroApi
import com.hydro.link.shared.model.PairRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun main() = runBlocking {
    println("[Test] 正在验证 Compose Desktop 配对弹窗全流程与状态闭环...")

    val tmpDir = File(System.getProperty("java.io.tmpdir"), "hydrolink-pair-test-" + System.currentTimeMillis())
    tmpDir.mkdirs()
    try {
        val store = Store.open(tmpDir)
        val server = HydroServer(store)
        server.start(0)
        val port = server.port
        println("  1. 测试服务端就绪 · 端口 $port")

        val api = HydroApi(HttpTransport("http://127.0.0.1:$port"))
        val model = AppModel()
        val promptedPairs = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // 模拟 Main.kt 中的服务端回调链路
        var callbackFiredCount = 0
        server.onPairRequested = { deviceId, deviceName ->
            callbackFiredCount++
            val pair = PairRequest(deviceId, deviceName)
            promptedPairs.add(deviceId)
            model.pairConfirmTarget = pair
        }

        val http = HttpClient.newHttpClient()
        fun sendPair(id: String, name: String): HttpResponse<String> {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/api/pair"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"device_id":"$id","device_name":"$name"}"""))
                .build()
            return http.send(req, HttpResponse.BodyHandlers.ofString())
        }

        // 2. 发起新配对请求 -> 验证服务端回调直接命中 UI 弹窗
        println("  2. 发起首个配对请求 (dev-watch-1)...")
        val resp1 = sendPair("dev-watch-1", "Xiaomi Watch 1")
        check(resp1.statusCode() == 200) { "发起配对请求 HTTP 失败: ${resp1.body()}" }
        check(callbackFiredCount == 1) { "回调未触发或触发次数异常: $callbackFiredCount" }
        check(model.pairConfirmTarget?.deviceId == "dev-watch-1") { "model.pairConfirmTarget 未设置" }
        check(model.pairConfirmTarget?.deviceName == "Xiaomi Watch 1") { "deviceName 不匹配" }
        check(promptedPairs.contains("dev-watch-1")) { "promptedPairs 未记录" }
        println("     ✓ 回调触发成功，弹窗目标 model.pairConfirmTarget 赋值正常")

        // 3. 用户点击取消/关闭弹窗（例如 Esc 或点击遮罩），暂不决定
        println("  3. 模拟用户取消/关闭弹窗...")
        model.pairConfirmTarget = null
        check(model.pairConfirmTarget == null)

        // 4. 模拟轮询获取 snapshot，验证后台静默轮询不会骚扰重复弹窗
        println("  4. 模拟后台轮询 snapshot，验证静默轮询不骚扰...")
        val snapshot1 = api.snapshot()
        model.pairs = snapshot1.pairs
        val currentIds = model.pairs.map { it.deviceId }.toSet()
        promptedPairs.retainAll(currentIds)
        val unprompted = model.pairs.firstOrNull { it.deviceId !in promptedPairs }
        check(unprompted == null) { "已提示过的设备在静默轮询时不应被重复作为弹窗目标" }
        check(model.pairConfirmTarget == null)
        println("     ✓ 轮询防重复弹窗机制正常")

        // 5. 手表端点击重试 / 重新发起配对 (POST /api/pair) -> 服务端必须能重新唤醒弹窗
        println("  5. 手表端重新发起配对请求 (dev-watch-1)...")
        val resp2 = sendPair("dev-watch-1", "Xiaomi Watch 1 Pro")
        check(resp2.statusCode() == 200)
        check(callbackFiredCount == 2) { "手表重新发起配对时必须能再次唤醒弹窗回调 (实际 $callbackFiredCount)" }
        check(model.pairConfirmTarget?.deviceId == "dev-watch-1") { "重新配对请求后弹窗目标未重新赋值" }
        check(model.pairConfirmTarget?.deviceName == "Xiaomi Watch 1 Pro") { "重新配对时设备名未同步刷新" }
        println("     ✓ 手表重新发起配对成功重新拉起授权弹窗并刷新设备名")

        // 6. 新设备 dev-watch-2 到达 -> 触发新弹窗
        println("  6. 新设备 dev-watch-2 发起配对...")
        val resp3 = sendPair("dev-watch-2", "Vela Watch 2")
        check(resp3.statusCode() == 200)
        check(callbackFiredCount == 3) { "新设备配对回调次数异常: $callbackFiredCount" }
        check(model.pairConfirmTarget?.deviceId == "dev-watch-2")
        println("     ✓ 新设备成功触发弹窗")

        // 7. 用户点击「允许」-> 响应配对并清理弹窗
        println("  7. 模拟用户点击「允许」dev-watch-2...")
        val target2 = model.pairConfirmTarget!!
        api.respondPair(target2.deviceId, accept = true)
        if (model.pairConfirmTarget?.deviceId == target2.deviceId) {
            model.pairConfirmTarget = null
        }
        check(model.pairConfirmTarget == null)
        val devicesAfterAccept = api.snapshot().devices
        val dev2 = devicesAfterAccept.firstOrNull { it.deviceId == "dev-watch-2" }
        check(dev2 != null) { "已接受设备未加入已配对列表" }
        check(dev2.deviceName == "Vela Watch 2") { "已接受设备名称丢失或为 Unknown (实际: '${dev2?.deviceName}')" }
        println("     ✓ 接受配对成功，已加入已配对设备，设备名称保持正确，弹窗正常关闭")

        // 8. 用户对 dev-watch-1 点击「拒绝」-> 拒绝并清理
        println("  8. 模拟拒绝 dev-watch-1...")
        api.respondPair("dev-watch-1", accept = false)
        val snapshot2 = api.snapshot()
        check(snapshot2.pairs.none { it.deviceId == "dev-watch-1" }) { "已拒绝设备仍存在于待配对列表" }
        promptedPairs.retainAll(snapshot2.pairs.map { it.deviceId }.toSet())
        check(!promptedPairs.contains("dev-watch-1"))
        println("     ✓ 拒绝配对成功，从待配对列表移除")

        // 9. 空设备名容错测试：手表请求时 device_name 为空字符串 -> 服务端如实记录，UI展示安全回退为「未知设备」
        println("  9. 测试空设备名配对容错...")
        val respEmpty = sendPair("dev-watch-empty", "")
        check(respEmpty.statusCode() == 200)
        api.respondPair("dev-watch-empty", accept = true)
        val emptyDev = api.snapshot().devices.firstOrNull { it.deviceId == "dev-watch-empty" }
        check(emptyDev != null)
        check(emptyDev.deviceName == "") { "空设备名服务端应如实记录原始数据 (实际: '${emptyDev.deviceName}')" }
        val displayTitle = emptyDev.deviceName.ifEmpty { "未知设备" }
        check(displayTitle == "未知设备")
        println("     ✓ 空设备名配对流程正常，UI展示安全回退为「未知设备」")

        server.stop()
        println("[Test] Compose Desktop 配对弹窗全流程逻辑全部验证通过！✓")
    } finally {
        tmpDir.deleteRecursively()
    }
}
