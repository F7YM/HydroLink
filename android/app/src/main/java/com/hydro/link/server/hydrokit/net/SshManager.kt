package com.hydro.link.server.hydrokit.net

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HydroKit 原生 SSH 会话管理器（Android 端）。
 *
 * 为插件提供受权限门控（`"ssh"`）的交互式 SSH 会话能力，完全兼容 `com.sunpm.remote.terminal`
 * 与通用终端规范，与桌面端保持 100% 协议与错误语义一致。
 */
object SshManager {

    data class SshSession(
        val id: String,
        val pkg: String,
        val host: String,
        val port: Int,
        val user: String,
        val jschSession: Session,
        val channel: ChannelShell,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val fingerprint: String,
        val banner: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastActiveAt: Long = System.currentTimeMillis(),
        @Volatile var closed: Boolean = false,
        @Volatile var exitCode: Int? = null,
    )

    data class OpenParams(
        val host: String,
        val port: Int = 22,
        val user: String,
        val password: String? = null,
        val agent: Boolean = false,
        val keyFile: String? = null,
        val privateKey: String? = null,
        val passphrase: String? = null,
        val fingerprint: String? = null,
        val term: String = "xterm-256color",
        val cols: Int = 80,
        val rows: Int = 24,
        val timeoutMs: Int = 10_000,
    )

    data class OpenResult(
        val id: String,
        val remote: String,
        val fingerprint: String,
        val banner: String,
    )

    data class ReadResult(
        val data: String,
        val closed: Boolean,
        val exitCode: Int? = null,
        val bytesRead: Int = 0,
    )

    private val sessions = ConcurrentHashMap<String, SshSession>()

    /**
     * 打开 SSH 会话并分配 PTY Shell。
     */
    fun open(pkg: String, params: OpenParams): OpenResult {
        require(params.host.isNotBlank()) { "缺少 host" }
        require(params.user.isNotBlank()) { "缺少 user" }
        require(params.port in 1..65535) { "无效端口: ${params.port}" }

        val jsch = JSch()
        setupIdentities(jsch, params)

        val timeout = params.timeoutMs.coerceIn(1000, 30_000)
        val jschSession = try {
            jsch.getSession(params.user, params.host, params.port)
        } catch (e: Exception) {
            throw IllegalStateException("创建 SSH 会话失败: ${e.message}", e)
        }

        if (!params.password.isNullOrEmpty()) {
            jschSession.setPassword(params.password)
        }

        jschSession.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = params.passphrase
            override fun getPassword(): String? = params.password
            override fun promptPassword(message: String?): Boolean = !params.password.isNullOrEmpty()
            override fun promptPassphrase(message: String?): Boolean = !params.passphrase.isNullOrEmpty()
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<out String>?,
                echo: BooleanArray?
            ): Array<String>? {
                if (!params.password.isNullOrEmpty() && prompt != null && prompt.isNotEmpty()) {
                    return Array(prompt.size) { params.password }
                }
                return null
            }
        }

        jschSession.setConfig("StrictHostKeyChecking", "no")
        jschSession.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")

        try {
            jschSession.connect(timeout)
        } catch (e: JSchException) {
            val msg = e.message.orEmpty()
            when {
                msg.contains("Auth fail", ignoreCase = true) || msg.contains("authentication", ignoreCase = true) ->
                    throw IllegalStateException("认证失败: 用户名、密码或密钥不正确")
                msg.contains("timeout", ignoreCase = true) ->
                    throw IllegalStateException("连接超时: 主机不通或端口未响应 ($timeout ms)")
                msg.contains("HostKey", ignoreCase = true) ->
                    throw IllegalStateException("主机密钥不匹配: 远端密钥校验失败")
                else ->
                    throw IllegalStateException("连接失败: $msg", e)
            }
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("连接超时: 主机未响应 ($timeout ms)")
        }

        val hostKey = jschSession.hostKey
        val actualFp = hostKey?.let { "${it.type} ${it.getFingerPrint(jsch)}" }.orEmpty()

        if (!params.fingerprint.isNullOrBlank()) {
            val expected = params.fingerprint.trim().lowercase()
            val actual = actualFp.lowercase()
            if (!actual.contains(expected)) {
                jschSession.disconnect()
                throw IllegalStateException("主机密钥不匹配: 期望包含 '$expected'，实际为 '$actualFp'")
            }
        }

        val channel = try {
            val ch = jschSession.openChannel("shell") as ChannelShell
            val cols = params.cols.coerceIn(20, 500)
            val rows = params.rows.coerceIn(5, 200)
            ch.setPtyType(params.term.ifBlank { "xterm-256color" }, cols, rows, cols * 8, rows * 16)
            ch.connect(timeout)
            ch
        } catch (e: Exception) {
            jschSession.disconnect()
            throw IllegalStateException("分配 PTY 失败: ${e.message}", e)
        }

        val id = "ssh_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val banner = jschSession.serverVersion.orEmpty()

        val session = SshSession(
            id = id,
            pkg = pkg,
            host = params.host,
            port = params.port,
            user = params.user,
            jschSession = jschSession,
            channel = channel,
            inputStream = channel.inputStream,
            outputStream = channel.outputStream,
            fingerprint = actualFp,
            banner = banner,
        )
        sessions[id] = session
        cleanupIdleSessions()

        return OpenResult(
            id = id,
            remote = "${params.user}@${params.host}:${params.port}",
            fingerprint = actualFp,
            banner = banner,
        )
    }

    private fun setupIdentities(jsch: JSch, params: OpenParams) {
        if (!params.privateKey.isNullOrBlank()) {
            val keyBytes = params.privateKey.toByteArray(Charsets.UTF_8)
            val passBytes = params.passphrase?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("key_" + params.user, keyBytes, null, passBytes)
            return
        }

        if (!params.keyFile.isNullOrBlank()) {
            val resolved = resolvePath(params.keyFile)
            val file = File(resolved)
            if (file.exists() && file.isFile) {
                if (!params.passphrase.isNullOrEmpty()) {
                    jsch.addIdentity(file.absolutePath, params.passphrase)
                } else {
                    jsch.addIdentity(file.absolutePath)
                }
                return
            }
        }

        // 尝试载入默认 SSH 密钥
        if (params.password.isNullOrEmpty() || params.agent) {
            val userHome = System.getProperty("user.home").orEmpty()
            if (userHome.isNotBlank()) {
                val candidateKeys = listOf(
                    File(userHome, ".ssh/id_ed25519"),
                    File(userHome, ".ssh/id_rsa"),
                    File(userHome, ".ssh/id_ecdsa"),
                )
                for (key in candidateKeys) {
                    if (key.exists() && key.isFile) {
                        try {
                            jsch.addIdentity(key.absolutePath)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun resolvePath(path: String): String {
        var p = path.trim()
        if (p.startsWith("~")) {
            val userHome = System.getProperty("user.home").orEmpty()
            p = if (userHome.isNotBlank()) userHome + p.removePrefix("~") else p
        }
        return p
    }

    private fun findSession(pkg: String, id: String?): SshSession {
        if (!id.isNullOrBlank()) {
            val session = sessions[id] ?: throw NoSuchElementException("会话不存在: $id")
            if (session.pkg != pkg) throw SecurityException("会话不属于当前插件")
            if (session.closed || !session.jschSession.isConnected) {
                session.closed = true
                throw IllegalStateException("会话不存在或已断开")
            }
            return session
        }
        return sessions.values
            .filter { it.pkg == pkg && !it.closed && it.jschSession.isConnected }
            .maxByOrNull { it.lastActiveAt }
            ?: throw NoSuchElementException("会话不存在（没有活动的 SSH 连接）")
    }

    /**
     * 读取终端输出。
     */
    fun read(
        pkg: String,
        id: String?,
        timeoutMs: Int = 1000,
        encoding: String = "utf-8",
    ): ReadResult {
        val session = try {
            findSession(pkg, id)
        } catch (e: Exception) {
            return ReadResult(data = "", closed = true, exitCode = -1, bytesRead = 0)
        }
        session.lastActiveAt = System.currentTimeMillis()

        val buf = ByteArray(16384)
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0, 15_000)

        while (true) {
            val avail = try {
                session.inputStream.available()
            } catch (_: Exception) {
                -1
            }
            if (avail > 0) {
                val n = session.inputStream.read(buf, 0, minOf(buf.size, avail))
                if (n > 0) {
                    out.write(buf, 0, n)
                    continue
                }
            }
            if (out.size() > 0) break

            val channelClosed = session.channel.isClosed || !session.channel.isConnected
            if (channelClosed) {
                session.closed = true
                session.exitCode = session.channel.exitStatus.takeIf { it >= 0 }
                break
            }

            if (System.currentTimeMillis() >= deadline) break
            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                break
            }
        }

        val channelClosed = session.channel.isClosed || !session.channel.isConnected || session.closed
        if (channelClosed) {
            session.closed = true
            if (session.exitCode == null && session.channel.exitStatus >= 0) {
                session.exitCode = session.channel.exitStatus
            }
        }

        val bytes = out.toByteArray()
        val text = encodeBytes(bytes, encoding)
        return ReadResult(
            data = text,
            closed = session.closed,
            exitCode = session.exitCode,
            bytesRead = bytes.size,
        )
    }

    /**
     * 向终端输入内容。
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
     * 调整终端窗口尺寸（PTY resize）。
     */
    fun resize(
        pkg: String,
        id: String?,
        cols: Int,
        rows: Int,
    ) {
        val session = findSession(pkg, id)
        session.lastActiveAt = System.currentTimeMillis()

        val c = cols.coerceIn(20, 500)
        val r = rows.coerceIn(5, 200)
        session.channel.setPtySize(c, r, c * 8, r * 16)
    }

    /**
     * 关闭指定或全部 SSH 会话。
     */
    fun close(pkg: String, id: String?) {
        if (!id.isNullOrBlank()) {
            val s = sessions.remove(id) ?: return
            if (s.pkg != pkg) return
            s.closed = true
            try {
                s.channel.disconnect()
            } catch (_: Exception) {}
            try {
                s.jschSession.disconnect()
            } catch (_: Exception) {}
        } else {
            val toRemove = sessions.filterValues { it.pkg == pkg }
            toRemove.forEach { (k, s) ->
                sessions.remove(k)
                s.closed = true
                try {
                    s.channel.disconnect()
                } catch (_: Exception) {}
                try {
                    s.jschSession.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 列出该插件活跃的 SSH 会话。
     */
    fun list(pkg: String): List<Map<String, Any?>> {
        return sessions.values
            .filter { it.pkg == pkg && !it.closed && it.jschSession.isConnected }
            .map {
                mapOf(
                    "id" to it.id,
                    "host" to it.host,
                    "port" to it.port,
                    "user" to it.user,
                    "fingerprint" to it.fingerprint,
                    "banner" to it.banner,
                    "createdAt" to it.createdAt,
                    "lastActiveAt" to it.lastActiveAt,
                )
            }
    }

    private fun encodeBytes(bytes: ByteArray, encoding: String): String {
        return when (encoding.lowercase()) {
            "base64" -> encodeBase64(bytes)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun decodeBytes(data: String, encoding: String): ByteArray {
        return when (encoding.lowercase()) {
            "base64" -> decodeBase64(data)
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
        val idleThreshold = 10 * 60 * 1000L // 10 分钟无活动
        sessions.entries.removeIf { (_, session) ->
            if (session.closed || !session.jschSession.isConnected || (now - session.lastActiveAt > idleThreshold)) {
                session.closed = true
                try {
                    session.channel.disconnect()
                } catch (_: Exception) {}
                try {
                    session.jschSession.disconnect()
                } catch (_: Exception) {}
                true
            } else {
                false
            }
        }
    }
}
