package com.hydro.link.server.util

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {

    /**
     * 获取手机在当前局域网（优先 Wi-Fi）中的 IPv4 地址。
     * 解决双卡/移动数据导致先拿到 rmnet 移动蜂窝内网 IP，从而无法探测 WiFi 局域网内桌面端的问题。
     */
    fun getLocalIpAddress(context: Context): String {
        // 1. 尝试从 WifiManager 获取（适用于授予了网络状态/旧版 API 的场景）
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && wifiManager.isWifiEnabled) {
                @Suppress("DEPRECATION")
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    @Suppress("DEPRECATION")
                    val ip = Formatter.formatIpAddress(ipInt)
                    if (ip.isNotBlank() && ip != "0.0.0.0") return ip
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        val allIps = getAllLocalIpv4Addresses()

        // 2. 优先匹配典型的局域网 Wi-Fi 地址（192.168.x.x，最常见家用路由段）
        val typicalLan = allIps.firstOrNull { it.second.startsWith("192.168.") }
        if (typicalLan != null) return typicalLan.second

        // 3. 优先匹配 wlan0 / wlan* 接口的 IP
        val wlanIp = allIps.firstOrNull { it.first.lowercase().startsWith("wlan") }
        if (wlanIp != null) return wlanIp.second

        // 4. 匹配常见的私网 A/B 类网段（172.16-31.x.x 或 10.x.x.x，排除移动蜂窝 rmnet/ccmni）
        val privateNonCellular = allIps.firstOrNull { (iface, ip) ->
            !iface.lowercase().contains("rmnet") &&
            !iface.lowercase().contains("ccmni") &&
            !iface.lowercase().contains("dummy") &&
            isPrivateIpv4(ip)
        }
        if (privateNonCellular != null) return privateNonCellular.second

        // 5. 兜底任意非回环私有 IPv4
        return allIps.firstOrNull { isPrivateIpv4(it.second) }?.second
            ?: allIps.firstOrNull()?.second
            ?: "0.0.0.0"
    }

    /**
     * 获取所有可用的非回环 IPv4 接口与地址对列表 [(interfaceName, ip)]
     */
    fun getAllLocalIpv4Addresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "0.0.0.0") {
                            result.add(Pair(iface.name, host))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return result
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val parts = ip.split('.')
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull() ?: 0
                if (second in 16..31) return true
            }
        }
        return false
    }
}
