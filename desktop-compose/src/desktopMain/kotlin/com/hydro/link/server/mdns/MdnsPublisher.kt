package com.hydro.link.server.mdns

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Compose 桌面端 mDNS / DNS-SD 服务广播发布器。
 * 向局域网广播 `_hydrolink._tcp.local.` 服务，携带本机名称与真实监听端口（支持任意自定义端口），
 * 供手机伴侣端与手表端在局域网内自动零配置发现。
 */
object MdnsPublisher {

    private const val SERVICE_TYPE = "_hydrolink._tcp.local."
    private var jmDNS: JmDNS? = null
    private var currentService: ServiceInfo? = null
    private var currentPort: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun start(port: Int, customName: String? = null) {
        if (port <= 0 || port > 65535) return
        if (currentPort == port && jmDNS != null) return

        stop()

        currentPort = port
        scope.launch {
            try {
                val bindAddr = resolveLanIpv4()
                val hostName = customName?.takeIf { it.isNotBlank() } ?: resolveHostName()
                println("[mDNS] 正在启动局域网服务广播: $hostName on $bindAddr:$port ($SERVICE_TYPE)")

                val mdns = JmDNS.create(bindAddr, hostName)
                jmDNS = mdns

                val props = mapOf(
                    "app" to "hydrolink",
                    "version" to "1.3.0",
                    "device_type" to "desktop"
                )
                val serviceInfo = ServiceInfo.create(
                    SERVICE_TYPE,
                    hostName,
                    port,
                    0,
                    0,
                    props
                )
                currentService = serviceInfo
                mdns.registerService(serviceInfo)
                println("[mDNS] 局域网服务广播已就绪: ${serviceInfo.name} (${serviceInfo.qualifiedName}) -> $bindAddr:$port")
            } catch (e: Exception) {
                println("[mDNS] 启动局域网广播失败: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        val mdns = jmDNS ?: return
        val s = currentService
        jmDNS = null
        currentService = null
        currentPort = 0
        scope.launch {
            try {
                if (s != null) {
                    mdns.unregisterService(s)
                }
                mdns.unregisterAllServices()
                mdns.close()
                println("[mDNS] 局域网服务广播已关闭")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun resolveHostName(): String {
        return try {
            val raw = InetAddress.getLocalHost().hostName
            if (raw.isNullOrBlank() || raw.equals("localhost", ignoreCase = true)) {
                "HydroLink 桌面端"
            } else {
                raw
            }
        } catch (e: Exception) {
            "HydroLink 桌面端"
        }
    }

    private fun resolveLanIpv4(): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidateAddrs = mutableListOf<Inet4Address>()

            // 1. 优先物理无线与以太网网卡 (wl*, en*, eth*)
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name.startsWith("flclash")) {
                    continue
                }
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidateAddrs.add(addr)
                        if (name.startsWith("wl") || name.startsWith("en") || name.startsWith("eth")) {
                            return addr
                        }
                    }
                }
            }

            // 2. 匹配典型私网 IPv4 (192.168.x.x 等)
            val typical = candidateAddrs.firstOrNull { it.hostAddress.startsWith("192.168.") }
            if (typical != null) return typical

            if (candidateAddrs.isNotEmpty()) {
                return candidateAddrs.first()
            }
        } catch (e: Exception) {
            // fallback
        }
        return try {
            val local = InetAddress.getLocalHost()
            if (local is Inet4Address) local else InetAddress.getByName("127.0.0.1")
        } catch (e: Exception) {
            InetAddress.getByName("127.0.0.1")
        }
    }
}
