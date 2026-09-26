package com.hydro.link.server.model

import com.google.gson.annotations.SerializedName

data class TrustedDevice(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("trusted_at") val trustedAt: String = ""
)

data class PairRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String
)

data class Command(
    val name: String = "",
    val command: String = ""
)

data class LogEntry(
    val text: String,
    val level: String = "info" // info, success, warning, error
)

data class SystemInfo(
    val hostname: String,
    val version: String
)

data class BatteryInfo(
    @SerializedName("has_battery") val hasBattery: Boolean,
    val percent: Int,
    val charging: Boolean
)

data class PerformanceInfo(
    val cpu: Float,
    @SerializedName("mem_percent") val memPercent: Float,
    @SerializedName("mem_used") val memUsed: Long, // MB
    @SerializedName("mem_total") val memTotal: Long, // MB
    val temp: Float? = null
)

data class AppConfig(
    @SerializedName("encryption_key") var encryptionKey: String = "",
    @SerializedName("last_port") var lastPort: Int = 9000,
    @SerializedName("accept_all") var acceptAll: Boolean = false,
    @SerializedName("use_root") var useRoot: Boolean = false,
    /** 命令授权器：none / root / shizuku（默认 none）。 */
    @SerializedName("authorizer") var authorizer: String = "none",
    @SerializedName("boot_start") var bootStart: Boolean = false,
    @SerializedName("liquid_glass") var liquidGlass: Boolean = false, // Liquid Glass 底栏（默认关）
    @SerializedName("miuix") var miuix: Boolean = false // miuix 风格底栏（默认关）
)
