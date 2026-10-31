package com.hydro.link.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 跨端共享的数据结构。
 *
 * 字段名与 Go 侧 `api.Server` 的 JSON 对齐（snake_case）；序列化注解等接上真实数据层
 * （S2）再补，这里先保持纯数据结构。
 */

/** 已信任设备（配对过的手表/手机）。字段名对齐 Go 侧 `store.TrustedDevice` 的 JSON。 */
@Serializable
data class TrustedDevice(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("trusted_at") val trustedAt: String = "",
)

/** 待确认的配对请求（`store.PairRequest`）。 */
@Serializable
data class PairRequest(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
)

/** 快捷命令（`store.Command`）。 */
@Serializable
data class Command(
    @SerialName("name") val name: String = "",
    @SerialName("command") val command: String = "",
)

/** 日志条目（与 Go 侧 `api.LogEntry` 一致：level 取 info/success/warning/error）。 */
@Serializable
data class LogEntry(
    @SerialName("text") val text: String = "",
    @SerialName("level") val level: String = "info",
)

/** 插件列表的一行（已装）。字段与 Android 侧 `PluginRow` 一致。 */
data class PluginRow(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val runtime: String,
    val items: String,
    val permissions: String,
    val hasWeb: Boolean,
    val workerOnline: Boolean,
    val error: String?,
    /** 图标地址：协议里就是 worker asset 的绝对 URL（相对路径已被服务端转好）。 */
    val iconUrl: String = "",
) {
    /** 副标题：作者 · 包名 · 版本。 */
    val pkgLine: String get() = buildString {
        if (author.isNotBlank()) append(author).append(" · ")
        append(id)
        if (version.isNotBlank()) append(" · v").append(version)
    }
}

/** 服务端状态（`/api/status` 的形状）。 */
data class ServerStatus(
    val running: Boolean = false,
    val ip: String = "",
    val port: String = "",
    val version: String = "",
)

/** 语义色（成功/警告/错误；日志页与语气化文案共用）。 */
enum class Tone { Outline, Warning, Success, Error }
