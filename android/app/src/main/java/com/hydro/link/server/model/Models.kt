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
    /** 配对请求通知样式：live（实时活动，含流体云/超级岛，默认） / standard（标准通知）。 */
    @SerializedName("pair_notification_style") var pairNotificationStyle: String = "live",
    @SerializedName("liquid_glass") var liquidGlass: Boolean = false, // Liquid Glass 底栏（默认关）
    @SerializedName("miuix") var miuix: Boolean = false, // miuix 风格底栏（默认关）
    /** 插件市场来源：统一默认 Gh-Proxy。 */
    @SerializedName("market_source") var marketSource: String = "https://gh-proxy.com/https://raw.githubusercontent.com/F7YM/hydromarket-index/main",
    /** 检查更新记录：上次自动检查日期（每天最多 1 次，yyyy-MM-dd）。 */
    @SerializedName("last_update_check_date") var lastUpdateCheckDate: String = "",
    /** 稍后提醒记录：上次点击稍后提醒日期（今日不再提醒，yyyy-MM-dd）。 */
    @SerializedName("last_update_ignore_date") var lastUpdateIgnoreDate: String = ""
)
