package com.hydro.link.server.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.hydro.link.server.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val deviceType: String,
)

/**
 * 局域网设备自动发现（纯标准 mDNS / DNS-SD）。
 * 供 Interconnect 互联通道使用：手表通过手机伴侣在局域网内监听 _hydrolink._tcp. 广播，
 * 自动解析广播设备名称、IP 及任意自定义端口（天然支持 1..65535 任意端口）。
 */
object LanDiscovery {

    private const val TAG = "LanDiscovery"
    private const val SERVICE_TYPE = "_hydrolink._tcp."
    private const val DEFAULT_PORT = 9000

    /**
     * 发现局域网内广播了 _hydrolink._tcp 的 HydroLink 设备。
     * 超时控制在 timeoutMs（默认 3 秒）内。
     */
    suspend fun discover(context: Context, timeoutMs: Long = 3000L): List<DiscoveredDevice> =
        withContext(Dispatchers.IO) {
            val localIp = NetworkUtil.getLocalIpAddress(context)
            Log.d(TAG, "Starting mDNS LanDiscovery on local IP: $localIp")
            val foundDevices = ConcurrentHashMap<String, DiscoveredDevice>()

            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                Log.w(TAG, "NsdManager not available on this device")
                return@withContext emptyList()
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "mDNS discovery started for type: $regType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
                    if (serviceInfo.serviceType.contains("hydrolink")) {
                        try {
                            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "mDNS resolve failed for ${serviceInfo.serviceName}: code $errorCode")
                                }

                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val inetAddr = resolved.host ?: return
                                    // 彻底屏蔽 IPv6：仅允许 IPv4，排除任何 IPv6（含 Inet6Address 及含冒号地址）
                                    if (inetAddr !is java.net.Inet4Address) {
                                        Log.d(TAG, "Ignoring non-IPv4 mDNS host: $inetAddr")
                                        return
                                    }
                                    val host = inetAddr.hostAddress ?: return
                                    if (host.contains(":") || host == localIp || host == "127.0.0.1") return
                                    val port = resolved.port
                                    val actualPort = if (port in 1..65535) port else DEFAULT_PORT
                                    val devName = resolved.serviceName?.takeIf { it.isNotBlank() } ?: "HydroLink 设备"
                                    val dev = DiscoveredDevice(
                                        name = devName,
                                        host = host,
                                        port = actualPort,
                                        deviceType = "desktop"
                                    )
                                    val key = "$host:$actualPort"
                                    if (foundDevices.putIfAbsent(key, dev) == null) {
                                        Log.i(TAG, "mDNS resolved new IPv4 device: $dev")
                                    }
                                }
                            })
                        } catch (e: Exception) {
                            Log.w(TAG, "mDNS resolveService call failed", e)
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS onStartDiscoveryFailed: code $errorCode")
                    try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "mDNS onStopDiscoveryFailed: code $errorCode")
                }
            }

            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                // 监听指定时长
                delay(timeoutMs)
            } catch (e: Exception) {
                Log.w(TAG, "mDNS discoverServices threw exception", e)
            } finally {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (e: Exception) {
                    // ignore
                }
            }

            val list = foundDevices.values.toList()
            Log.i(TAG, "mDNS LanDiscovery completed, found ${list.size} devices: $list")
            list
        }
}
