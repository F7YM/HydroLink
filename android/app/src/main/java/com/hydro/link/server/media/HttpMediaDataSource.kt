package com.hydro.link.server.media

import android.media.MediaDataSource
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 给 MediaExtractor 用的可随机读取数据源（HTTP Range）。
 *
 * 为什么不能"先整段下载再解码"：切片是**按段懒生成**的，手表每滚到一段才要下一段，
 * 而一部视频可能几十 MB；而且插件给的地址常常是防盗链链接（带 Referer/UA），
 * 一次性下载会把首段延迟拉到几秒。MediaExtractor 要随机访问（先读 moov 头、seek
 * 时再读中间），所以必须支持 `readAt(position, …)`。
 *
 * 服务器不认 Range（回 200 全量）时会逐个字节跳过到目标位置——**必须**这么做：
 * 直接当 206 解析会静默取错位置，解出来是花屏/黑屏，比直接报错难查得多。
 */
internal class HttpMediaDataSource(
    private val url: String,
    private val headers: Map<String, String>,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 8000,
) : MediaDataSource() {

    private var knownSize = -1L

    @Volatile
    private var closed = false

    private fun open(range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        // 中间层若做了内容压缩，Range 的字节位置就和视频文件对不上了
        conn.setRequestProperty("Accept-Encoding", "identity")
        for ((k, v) in headers) {
            if (k.equals("Range", ignoreCase = true)) continue // Range 由本类自己管
            conn.setRequestProperty(k, v)
        }
        if (range != null) conn.setRequestProperty("Range", range)
        conn.connect()
        return conn
    }

    /** 视频总长度；拿不到返回 -1（MediaExtractor 会当成不可 seek 的数据源）。 */
    override fun getSize(): Long {
        if (knownSize > 0) return knownSize
        val conn = open("bytes=0-0")
        try {
            val code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range") // "bytes 0-0/1234567"
            val total = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
            knownSize = when {
                total != null && total > 0 -> total
                // 服务器忽略了 Range（回 200 全量）：Content-Length 就是真实大小
                code == 200 && conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> -1
            }
        } catch (e: Exception) {
            knownSize = -1
        } finally {
            runCatching { conn.disconnect() }
        }
        return knownSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (size <= 0) return 0
        if (position < 0) throw IOException("非法读取位置: $position")

        val conn = open("bytes=$position-${position + size - 1}")
        try {
            val code = conn.responseCode
            if (code != 206 && code != 200) {
                throw IOException("HTTP $code（取视频数据失败）")
            }
            conn.inputStream.use { input ->
                if (code == 200 && position > 0) skipFully(input, position)
                var read = 0
                while (read < size) {
                    val n = input.read(buffer, offset + read, size - read)
                    if (n < 0) break
                    read += n
                }
                return if (read == 0) -1 else read
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    override fun close() {
        closed = true
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val sink = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val read = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
            if (read < 0) throw IOException("响应提前结束（跳过 $left 字节失败）")
            left -= read
        }
    }
}
