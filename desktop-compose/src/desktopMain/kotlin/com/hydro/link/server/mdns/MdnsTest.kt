package com.hydro.link.server.mdns

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

fun main() {
    println("[Test] 正在验证 Compose 端 mDNS 广播与解析链路...")
    val testPort = 9200
    val testName = "TestHydroLinkCompose"
    val latch = CountDownLatch(1)

    // 1. 启动发布器广播
    MdnsPublisher.start(port = testPort, customName = testName)

    // 2. 模拟客户端/伴侣端监听广播并解析
    val clientDns = JmDNS.create()
    clientDns.addServiceListener("_hydrolink._tcp.local.", object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            println("[Test] 收到服务上线通知: ${event.name}")
            // 请求解析服务详情（获取端口和 IP）
            clientDns.requestServiceInfo(event.type, event.name, 1000)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            println("[Test] 服务下线通知: ${event.name}")
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            println("[Test] 成功解析服务: name=${info.name}, port=${info.port}, ip=${info.inet4Addresses.joinToString { it.hostAddress }}")
            if (info.name == testName && info.port == testPort) {
                latch.countDown()
            }
        }
    })

    val success = latch.await(6, TimeUnit.SECONDS)
    clientDns.close()
    MdnsPublisher.stop()

    if (success) {
        println("[PASS] Compose 端 mDNS 广播与自解析测试 100% 成功！")
        System.exit(0)
    } else {
        println("[FAIL] mDNS 解析超时或参数不匹配")
        System.exit(1)
    }
}
