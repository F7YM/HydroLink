package com.hydro.link.server.hydrokit

/**
 * worker 宿主页（worker.html + worker.js）的提供者。
 *
 * 安卓端把这两个文件放在 `assets/hydrokit/`；为了让宿主页与 API **同源**
 * （避免 file:// 页面跨源 fetch 需要 `allowUniversalAccessFromFileURLs` 之类的
 * 危险开关），由服务端在 `GET /api/hydro/worker/host` 上把它们吐出来，
 * WebView 直接加载 `http://127.0.0.1:<port>/api/hydro/worker/host?file=worker.html&…`。
 */
fun interface HostAssetProvider {
    /** 返回 assets/hydrokit/<name> 的字节；不存在返回 null。 */
    fun read(name: String): ByteArray?

    companion object {
        val NONE = HostAssetProvider { null }
    }
}

/**
 * 宿主页路由：`GET /api/hydro/worker/host?file=worker.html|worker.js`
 * 仅允许这两个文件名（白名单，避免把任意 asset 暴露出去）。
 */
class HostPageRoutes(private val provider: HostAssetProvider) {

    companion object {
        private val ALLOWED = setOf("worker.html", "worker.js")
    }

    fun handle(req: HydroHttpRequest): HydroHttpResponse? {
        val path = req.path.split("?")[0]
        val prefix = HydroKitRoutes.PREFIX + "/worker/host"
        if (path != prefix) return null
        if (req.method != "GET") return HydroHttpResponse.text("method not allowed", 405)

        val file = req.query["file"]?.ifBlank { null } ?: "worker.html"
        if (file !in ALLOWED) {
            return HydroHttpResponse.json("""{"error":"不允许的宿主文件"}""", 400)
        }
        val data = provider.read(file)
            ?: return HydroHttpResponse.json("""{"error":"宿主文件缺失: $file"}""", 404)
        val ct = when {
            file.endsWith(".html") -> "text/html; charset=utf-8"
            file.endsWith(".js") -> "application/javascript; charset=utf-8"
            file.endsWith(".wasm") -> "application/wasm"
            else -> "application/octet-stream"
        }
        return if (file.endsWith(".wasm")) {
            HydroHttpResponse.binary(data, ct, 200)
        } else {
            HydroHttpResponse(200, ct, String(data, Charsets.UTF_8), null)
        }
    }
}
