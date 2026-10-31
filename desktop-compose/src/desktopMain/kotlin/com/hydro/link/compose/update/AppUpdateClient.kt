package com.hydro.link.compose.update

import com.hydro.link.shared.market.marketJoin
import com.hydro.link.shared.update.AppUpdateHelper
import com.hydro.link.shared.update.AppUpdateInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌面端应用版本更新检查客户端（顺序尝试镜像源基址拉取 app.json）。
 */
class AppUpdateClient(
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 8_000,
) {

    /**
     * 顺序尝试镜像源基址，成功解析即刻返回，全部失败返回 null。
     */
    fun fetchUpdate(bases: List<String>): AppUpdateInfo? {
        for (base in bases) {
            val urlStr = marketJoin(base, "app.json")
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HydroLink-Desktop")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    if (body.isNotBlank()) {
                        return AppUpdateHelper.parse(body)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: IOException) {
                // 尝试下一个镜像源
            } catch (_: IllegalArgumentException) {
                // JSON 结构非法，尝试下一个镜像源
            }
        }
        return null
    }
}
