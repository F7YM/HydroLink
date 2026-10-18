package com.hydro.link.server.media

import com.google.gson.Gson
import com.hydro.link.server.hydrokit.HydroHttpRequest
import com.hydro.link.server.hydrokit.HydroHttpResponse

/**
 * 分段下发路由：
 *
 *	GET /api/hydro/media/<job>/<index>.gif   → 该段 GIF（没生成过就当场生成）
 *
 * 和 `/api/music/art` 一样**不做设备信任校验**：手表渲染器是直接按 URL 取图的
 * （`image` 组件 / `gif-player` 的 `segments[].url`），带不了 `X-Device-Id`。
 * 插件下发的是分段 URL 而不是 base64 —— 一条手表互联消息只有 16KB 上限，
 * 之前把整段 GIF 塞进响应里正是「点击缓冲播放，显示服务器无响应」的原因。
 *
 * 分段内容是不可变的（作业 id 随机、同一段永远同一字节），所以允许缓存
 * （`Cache-Control: public, max-age=600`），手表/中间层重取时不用再生成一次。
 * 想强制重取（比如播放器失败重试）在 URL 上带个 `?r=N` 打散即可。
 */
class MediaRoutes(
    private val jobs: MediaJobStore,
    private val log: (String) -> Unit = {},
) {

    private val gson = Gson()

    companion object {
        const val PREFIX = "/api/hydro/media"

        /** 解析 `/api/hydro/media/<job>/<index>[.gif]`；不是本路由返回 null。 */
        fun parse(path: String): Pair<String, Int>? {
            val clean = path.split("?")[0].trimEnd('/')
            if (clean != PREFIX && !clean.startsWith("$PREFIX/")) return null
            val rest = clean.removePrefix(PREFIX).trim('/')
            if (rest.isEmpty()) return null
            val parts = rest.split("/")
            if (parts.size != 2) return null
            val job = parts[0]
            if (job.isEmpty()) return null
            val raw = parts[1].removeSuffix(".gif")
            if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return null
            val index = raw.toIntOrNull() ?: return null
            return job to index
        }
    }

    /** 处理请求；不是本路由返回 null。 */
    fun handle(req: HydroHttpRequest): HydroHttpResponse? {
        val clean = req.path.split("?")[0]
        if (clean != PREFIX && !clean.startsWith("$PREFIX/")) return null
        if (req.method != "GET") return HydroHttpResponse.text("method not allowed", 405)

        val parsed = parse(req.path)
            ?: return HydroHttpResponse.json(
                """{"error":"路径应为 /api/hydro/media/<job>/<index>.gif"}""", 400,
            )
        val (job, index) = parsed
        return try {
            val gif = jobs.segment(job, index)
            log("hydrokit: GET ${clean} -> 200 (${gif.size}B)")
            HydroHttpResponse(
                200,
                "image/gif",
                "",
                gif,
                mapOf("Cache-Control" to "public, max-age=600"),
            )
        } catch (e: MediaError) {
            log("hydrokit: GET ${clean} -> ${e.status} (${e.message})")
            HydroHttpResponse.json(
                gson.toJson(mapOf("error" to (e.message ?: "切片失败"))),
                e.status,
            )
        }
    }
}
