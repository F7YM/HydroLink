package com.hydro.link.server.hydrokit.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HydroKit 原生 TCP 连接池管理器（Android 端）。
 *
 * 为插件提供受权限门控（`"tcp"`）的底层 TCP 通信能力，与桌面端保持 100% 协议一致。
 */
object TcpManager {

    data class TcpSession(
        val id: String,
        val pkg: String,
        val host: String,
        val port: Int,
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastActiveAt: Long = System.currentTimeMillis(),
    )

    data class ConnectResult(
        val id: String,
        val remote: String,
    )

    data class ReadResult(
        val data: String,
        val eof: Boolean,
        val bytesRead: Int,
    )

    private val sessions = ConcurrentHashMap<String, TcpSession>()

    /**
     * 建立 TCP 连接。
     */
    fun connect(
        pkg: String,
        host: String,
        port: Int,
        timeoutMs: Int = 10_000,
    ): ConnectResult {
        require(host.isNotBlank()) { "缺少 host" }
        require(port in 1..65535) { "无效端口: $port" }

        val timeout = timeoutMs.coerceIn(500, 30_000)
        val socket = Socket()
        socket.soTimeout = 1000
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeout)

        val id = "tcp_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val session = TcpSession(
            id = id,
            pkg = pkg,
            host = host,
            port = port,
            socket = socket,
            inputStream = socket.getInputStream(),
            outputStream = socket.getOutputStream(),
        )
        sessions[id] = session
        cleanupIdleSessions()
        return ConnectResult(id = id, remote = "$host:$port")
    }

    private fun findSession(pkg: String, id: String?): TcpSession {
        if (!id.isNullOrBlank()) {
            val session = sessions[id] ?: throw NoSuchElementException("会话不存在: $id")
            if (session.pkg != pkg) throw SecurityException("会话不属于当前插件")
            if (session.socket.isClosed) {
                sessions.remove(id)
                throw IllegalStateException("会话已关闭: $id")
            }
            return session
        }
        return sessions.values
            .filter { it.pkg == pkg && !it.socket.isClosed }
            .maxByOrNull { it.lastActiveAt }
            ?: throw NoSuchElementException("没有活动的 TCP 会话")
    }

    /**
     * 从 TCP 读取数据。
     */
    fun read(
        pkg: String,
        id: String?,
        timeoutMs: Int = 1000,
        encoding: String = "utf-8",
    ): ReadResult {
        val session = findSession(pkg, id)
        session.lastActiveAt = System.currentTimeMillis()

        val buf = ByteArray(16384)
        val originalTimeout = session.socket.soTimeout
        try {
            session.socket.soTimeout = timeoutMs.coerceIn(50, 15_000)
            val n = session.inputStream.read(buf)
            if (n < 0) {
                close(pkg, session.id)
                return ReadResult(data = "", eof = true, bytesRead = 0)
            }
            val slice = buf.copyOf(n)
            val text = encodeBytes(slice, encoding)
            return ReadResult(data = text, eof = false, bytesRead = n)
        } catch (_: SocketTimeoutException) {
            return ReadResult(data = "", eof = false, bytesRead = 0)
        } finally {
            try {
                session.socket.soTimeout = originalTimeout
            } catch (_: Exception) {}
        }
    }

    /**
     * 往 TCP 发送数据。
     */
    fun write(
        pkg: String,
        id: String?,
        data: String,
        encoding: String = "utf-8",
    ): Int {
        val session = findSession(pkg, id)
        session.lastActiveAt = System.currentTimeMillis()

        val bytes = decodeBytes(data, encoding)
        session.outputStream.write(bytes)
        session.outputStream.flush()
        return bytes.size
    }

    /**
     * 关闭指定或该插件全部 TCP 会话。
     */
    fun close(pkg: String, id: String?) {
        if (!id.isNullOrBlank()) {
            val s = sessions.remove(id) ?: return
            if (s.pkg != pkg) return
            try {
                s.socket.close()
            } catch (_: Exception) {}
        } else {
            val toRemove = sessions.filterValues { it.pkg == pkg }
            toRemove.forEach { (k, s) ->
                sessions.remove(k)
                try {
                    s.socket.close()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 列出该插件活跃的 TCP 会话。
     */
    fun list(pkg: String): List<Map<String, Any>> {
        return sessions.values
            .filter { it.pkg == pkg && !it.socket.isClosed }
            .map {
                mapOf(
                    "id" to it.id,
                    "host" to it.host,
                    "port" to it.port,
                    "remote" to "${it.host}:${it.port}",
                    "createdAt" to it.createdAt,
                    "lastActiveAt" to it.lastActiveAt,
                )
            }
    }

    private fun encodeBytes(bytes: ByteArray, encoding: String): String {
        return when (encoding.lowercase()) {
            "base64" -> encodeBase64(bytes)
            "hex" -> bytes.joinToString("") { "%02x".format(it) }
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun decodeBytes(data: String, encoding: String): ByteArray {
        return when (encoding.lowercase()) {
            "base64" -> decodeBase64(data)
            "hex" -> {
                val clean = data.filter { it.isLetterOrDigit() }
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
            else -> data.toByteArray(Charsets.UTF_8)
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun decodeBase64(data: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(data)
        } catch (_: Throwable) {
            android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        }
    }

    private fun cleanupIdleSessions() {
        val now = System.currentTimeMillis()
        val idleThreshold = 5 * 60 * 1000L // 5 分钟无活动
        sessions.entries.removeIf { (_, session) ->
            if (session.socket.isClosed || (now - session.lastActiveAt > idleThreshold)) {
                try {
                    session.socket.close()
                } catch (_: Exception) {}
                true
            } else {
                false
            }
        }
    }
}
