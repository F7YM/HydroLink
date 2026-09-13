package com.hydro.link.server.hydrokit

/**
 * 互联通道（XMS Wearable UDP）请求的解析工具。
 *
 * 手表经互联通道发来的报文只有 method / path / body，没有 HTTP 头，也没有
 * NanoHTTPD 的解析结果，所以 query 要自己拆、URL 解码要自己做。抽成纯函数
 * 是为了能在 JVM 单测里钉住行为（`HydroInterconnectTest`）——这段逻辑错了会
 * 直接表现为手表端插件页面 404 或者存储快照丢失，很难在真机上定位。
 */
object HydroInterconnect {

    /** 拆出路径与 query：`/api/hydro/x/app/main/view?a=1` → (`/api/hydro/x/app/main/view`, {a=1})。 */
    fun splitPath(rawPath: String): Pair<String, Map<String, String>> {
        val i = rawPath.indexOf('?')
        if (i < 0) return rawPath to emptyMap()
        return rawPath.substring(0, i) to parseQueryString(rawPath.substring(i + 1))
    }

    /** 把 `a=1&b=%E5%8C%97%E4%BA%AC` 解析成 map；无值/坏编码不抛异常。 */
    fun parseQueryString(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in q.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = pair.substring(0, i)
            val v = try {
                java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (e: Exception) {
                pair.substring(i + 1)
            }
            out[k] = v
        }
        return out
    }

    /** 组装一条 HydroKit 请求：互联通道没有请求头，存储快照只能在 query/body 里。 */
    fun toRequest(rawPath: String, method: String, body: String?): HydroHttpRequest {
        val (path, query) = splitPath(rawPath)
        return HydroHttpRequest(
            method = method.uppercase(),
            path = path,
            query = query,
            headers = emptyMap(),
            body = body,
        )
    }
}
