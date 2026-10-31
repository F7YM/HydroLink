package com.hydro.link.server

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 一张封面的原始字节与类型（`/api/music/art` 的输入）。
 *
 * 与 Go 的 `desktop/internal/system/art.go` 是同一份契约：MPRIS/SMTC 给的
 * `artUrl` 可能是 `file://`（Chromium/Electron 把封面写在自己的缓存目录里）、
 * 裸路径、`http(s)://`，少数播放器给 `data:`。
 */
class AlbumArt(val bytes: ByteArray, val mime: String, val source: String)

/**
 * artUrl → 图片字节。上游是 `playerctl metadata {{mpris:artUrl}}`。
 *
 * 都是纯函数（不碰 D-Bus、不碰 HTTP 状态），所以能在 JVM 单测里钉住：
 * 真机上"封面出不来"你只能看到一张裂图，看不出是哪一步的错。
 */
object AlbumArtFetcher {

    /** 单张封面的取图上限（与 Go 的 16MiB 对齐）。 */
    private const val MAX_BYTES = 16 * 1024 * 1024

    /** http(s) 取图超时（与 Go 的 10s 对齐）。 */
    private const val HTTP_TIMEOUT_MS = 10_000

    /**
     * artUrl → 本地文件路径；不是本地文件（http/data）时返回 null。
     *
     * 与 Go 的 `filePathFromURLFor` 对齐：
     * - `file:///home/u/a.jpg` → `/home/u/a.jpg`
     * - `file:///C:/dir/a.jpg`（Windows 的 file URL）→ `C:/dir/a.jpg`
     * - `file://server/share/a.jpg`（UNC）→ `//server/share/a.jpg`
     * - 裸路径（`/tmp/a.jpg`、`C:\dir\a.jpg`）原样返回
     */
    fun localPath(artUrl: String): String? {
        val raw = artUrl.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return null
        if (!raw.startsWith("file://")) return raw
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return null
        }
        val path = uri.path ?: return null
        val host = uri.host
        if (!host.isNullOrBlank() && host != "localhost") {
            // UNC：file://server/share/x → //server/share/x
            return "//" + host + path
        }
        // Windows 的 url.Path 是 "/C:/dir/x"，直接读会失败
        if (path.length >= 3 && path[0] == '/' && path[2] == ':') return path.substring(1)
        return path
    }

    /** 本地文件 / http(s) 取图；失败（读不到、超时、非 200）返回 null。 */
    fun fetch(artUrl: String): AlbumArt? {
        val raw = artUrl.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return fetchHttp(raw)
        if (raw.startsWith("data:")) return fetchData(raw)
        val path = localPath(raw) ?: return null
        return try {
            val file = File(path)
            if (!file.isFile || file.length() > MAX_BYTES) return null
            val bytes = file.readBytes()
            AlbumArt(bytes, sniffMime(bytes, fallback = ""), raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchHttp(url: String): AlbumArt? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "HydroLink")
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { stream -> readLimited(stream) } ?: return null
            val header = conn.contentType.orEmpty().substringBefore(';').trim()
            AlbumArt(body, sniffMime(body, fallback = header.ifBlank { "application/octet-stream" }), url)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** `data:image/png;base64,xxx`（少数播放器直接内联封面）。 */
    private fun fetchData(url: String): AlbumArt? = try {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        if (!meta.endsWith(";base64", ignoreCase = true)) return null
        val body = java.util.Base64.getDecoder().decode(url.substring(comma + 1))
        val declared = meta.substringBefore(';').trim()
        AlbumArt(body, sniffMime(body, fallback = declared.ifBlank { "application/octet-stream" }), "data:")
    } catch (e: Exception) {
        null
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /**
     * 依文件头判定 MIME，未知时用 [fallback]。
     *
     * 与 Go 的 `mimeForImage` 认同样三种签名（PNG/JPEG/WebP）；但**先嗅探、后看
     * 声明的类型**——Chromium 缓存里的封面经常挂着错的 Content-Type（尤其
     * `application/octet-stream`），下游（浏览器 `<img>`、手表图片加载器）会按
     * 错的类型处理。Go 在 http 分支信任响应头，这里比它更保守一点。
     */
    fun sniffMime(data: ByteArray, fallback: String = "application/octet-stream"): String {
        if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() &&
            data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte() &&
            data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() && data[6] == 0x1a.toByte() &&
            data[7] == 0x0a.toByte()
        ) {
            return "image/png"
        }
        if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }
        return fallback.ifBlank { "application/octet-stream" }
    }
}
