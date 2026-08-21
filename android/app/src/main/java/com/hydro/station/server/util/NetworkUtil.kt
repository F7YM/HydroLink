package com.hydro.station.server.util

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.NetworkInterface

object NetworkUtil {

    fun getLocalIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                @Suppress("DEPRECATION")
                val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
                if (ip != "0.0.0.0") return ip
            }
        } catch (e: Exception) {
            // fallback
        }

        // Try network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "0.0.0.0"
    }
}
