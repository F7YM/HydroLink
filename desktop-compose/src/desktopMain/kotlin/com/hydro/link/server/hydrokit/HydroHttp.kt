package com.hydro.link.server.hydrokit

/**
 * 平台无关的 HTTP 请求/响应描述（**与安卓端 HydroKitRoutes.kt 里的定义逐字一致**）。
 *
 * 为什么要这份替身：`server/media/MediaRoutes.kt` 是与安卓端**同一份文件**（同步纪律
 * 与两份 worker.js 胶水一样——改一边必须改另一边），它 import 的是这个包里的这两个类型。
 * 桌面端的 HTTP 层是 `com.sun.net.httpserver`，与 NanoHTTPD 不同，所以在这里补一份
 * 同形状的定义，让那份文件在两端保持逐字一致（唯一的偏差是 MediaRoutes 里的 JSON
 * 序列化：安卓用 Gson、桌面用 kotlinx.serialization）。
 */
data class HydroHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

class HydroHttpResponse(
    val status: Int,
    val contentType: String = "application/json",
    val body: String = "",
    val bytes: ByteArray? = null,
    val headers: Map<String, String> = mapOf("Cache-Control" to "no-store"),
) {
    companion object {
        fun json(body: String, status: Int = 200) = HydroHttpResponse(status, "application/json", body)
        fun text(body: String, status: Int) = HydroHttpResponse(status, "text/plain; charset=utf-8", body)
        fun binary(data: ByteArray, contentType: String, status: Int = 200) =
            HydroHttpResponse(status, contentType, "", data)

        /** 204：插件表示"无需更新"。 */
        fun noContent() = HydroHttpResponse(204, "application/json", "")
    }
}
