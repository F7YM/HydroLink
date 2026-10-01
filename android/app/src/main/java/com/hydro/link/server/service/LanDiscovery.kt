package com.hydro.link.server.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hydro.link.server.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val deviceType: String,
)

/**
 * 局域网设备自动发现（mDNS + 快速 probe 并行）。
 * 供 Interconnect 互联通道使用：手表通过手机伴侣在局域网内寻找桌面端或其它 HydroLink 实例。
 */
object LanDiscovery {

    private const val TAG = "LanDiscovery"
    private const val SERVICE_TYPE = "_hydrolink._tcp."
    private const val DEFAULT_PORT = 9000
    private val gson = Gson()

    /**
     * 发现局域网内的 HydroLink 设备。
     * 超时控制在 2.5 秒内，避免手表端等待过久。
     */
    suspend fun discover(context: Context, timeoutMs: Long = 2500L): List<DiscoveredDevice> =
        withContext(Dispatchers.IO) {
            val localIp = NetworkUtil.getLocalIpAddress(context)
            val foundDevices = ConcurrentHashMap<String, DiscoveredDevice>()

            coroutineScope {
                // 1. 并发启动 mDNS 发现
                val mdnsJob = async {
                    runMdnsDiscovery(context, localIp, timeoutMs) { dev ->
                        val key = "${dev.host}:${dev.port}"
                        foundDevices.putIfAbsent(key, dev)
                    }
                }

                // 2. 并发启动近邻 IP Probe（针对默认 9000 端口）
                val probeJob = async {
                    runSubnetProbe(localIp, DEFAULT_PORT) { dev ->
                        val key = "${dev.host}:${dev.port}"
                        foundDevices.putIfAbsent(key, dev)
                    }
                }

                // 等待两者完成或超时
                withTimeoutOrNull(timeoutMs) {
                    mdnsJob.await()
                    probeJob.await()
                }
            }

            foundDevices.values.toList()
        }

    private suspend fun runMdnsDiscovery(
        context: Context,
        localIp: String,
        timeoutMs: Long,
        onFound: (DiscoveredDevice) -> Unit
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("hydrolink")) {
                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress ?: return
                                if (host == localIp || host == "127.0.0.1") return
                                val port = resolved.port
                                onFound(
                                    DiscoveredDevice(
                                        name = resolved.serviceName ?: "HydroLink 设备",
                                        host = host,
                                        port = if (port > 0) port else DEFAULT_PORT,
                                        deviceType = "desktop"
                                    )
                                )
                            }
                        })
                    } catch (e: Exception) {
                        // resolve 频率过高时系统可能抛异常，忽略
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            delay(timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery failed: ${e.message}")
        } finally {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {}
        }
    }

    /**
     * 探测当前 IP 附近的子网 IP（±20 范围 + 关键网段 IP）。
     */
    private suspend fun runSubnetProbe(
        localIp: String,
        port: Int,
        onFound: (DiscoveredDevice) -> Unit
    ) = coroutineScope {
        if (localIp.isBlank() || localIp == "0.0.0.0" || !localIp.contains('.')) return@coroutineScope
        val parts = localIp.split('.')
        if (parts.size != 4) return@coroutineScope
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        val myLast = parts[3].toIntOrNull() ?: return@coroutineScope

        val candidateIps = mutableSetOf<String>()
        // 关键 IP
        candidateIps.add("${prefix}1")
        candidateIps.add("${prefix}2")
        candidateIps.add("${prefix}100")
        candidateIps.add("${prefix}101")
        // 本机前后 20 个 IP
        for (offset in -20..20) {
            val num = myLast + offset
            if (num in 1..254 && num != myLast) {
                candidateIps.add("$prefix$num")
            }
        }

        candidateIps.map { ip ->
            async(Dispatchers.IO) {
                probeSingle(ip, port)?.let { onFound(it) }
            }
        }.awaitAll()
    }

    private fun probeSingle(ip: String, port: Int): DiscoveredDevice? {
        // 先用快速 socket connect 排除未开放端口的 IP（50ms 超时）
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), 60)
            }
        } catch (e: Exception) {
            return null
        }

        // 端口开放，发起 /api/version 请求验证是否为 HydroLink
        return try {
            val url = URL("http://$ip:$port/api/version")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(text, JsonObject::class.java)
                if (json != null && json.get("app")?.asString == "hydrolink") {
                    val name = json.get("name")?.asString
                        ?: json.get("hostname")?.asString
                        ?: "Desktop ($ip)"
                    DiscoveredDevice(
                        name = name,
                        host = ip,
                        port = port,
                        deviceType = "desktop"
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
