package com.hydro.link.server.net

import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * 验证 HydroKit 原生网络能力接口（TCP 与 SSH）。
 */
fun main() {
    println("[Test] 开始验证 HydroKit TCP 与 SSH 网络能力接口...")

    testTcpManager()
    testSshManager()
    testHttpEndpoints()

    println("[Test] ✅ 所有 HydroKit 原生网络能力单测全部通过！")
}

private fun testTcpManager() {
    println("--- 1. 测试 TcpManager ---")

    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort
    println("  [TCP] 临时测试服务端监听在端口: $port")

    val pkg = "com.test.terminal"
    var serverAcceptedSocket: Socket? = null

    val serverThread = thread {
        try {
            val client = serverSocket.accept()
            serverAcceptedSocket = client
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            val line = reader.readLine()
            if (line == "PING") {
                writer.write("PONG\n")
                writer.flush()
            }
        } catch (_: Exception) {}
    }

    try {
        // 1.1 建立连接
        val connResult = TcpManager.connect(pkg, "127.0.0.1", port, 5000)
        check(connResult.id.startsWith("tcp_")) { "TCP session ID 格式不正确: ${connResult.id}" }
        check(connResult.remote == "127.0.0.1:$port") { "TCP 远端地址不正确: ${connResult.remote}" }
        println("  [TCP] ✓ 连接成功: ${connResult.id} -> ${connResult.remote}")

        // 1.2 列表查询
        val list = TcpManager.list(pkg)
        check(list.any { it["id"] == connResult.id }) { "TCP 列表未找到连接" }
        println("  [TCP] ✓ 会话列表检索成功 (count=${list.size})")

        // 1.3 写入数据
        val bytesWritten = TcpManager.write(pkg, connResult.id, "PING\n")
        check(bytesWritten == 5) { "写入字节数不匹配: $bytesWritten" }
        println("  [TCP] ✓ 数据写入成功: $bytesWritten 字节")

        // 1.4 读取响应
        val readResult = TcpManager.read(pkg, connResult.id, 3000)
        check(readResult.data == "PONG\n") { "读取响应不匹配: '${readResult.data}'" }
        check(!readResult.eof) { "未达到 EOF" }
        println("  [TCP] ✓ 数据读取成功: '${readResult.data.trim()}'")

        // 1.5 关闭连接
        TcpManager.close(pkg, connResult.id)
        val remaining = TcpManager.list(pkg)
        check(remaining.none { it["id"] == connResult.id }) { "关闭后会话仍在列表中" }
        println("  [TCP] ✓ 会话关闭成功")
    } finally {
        serverThread.join(2000)
        serverSocket.close()
        serverAcceptedSocket?.close()
    }
}

private fun testSshManager() {
    println("--- 2. 测试 SshManager ---")

    val pkg = "com.sunpm.remote.terminal"

    // 2.1 参数校验
    try {
        SshManager.open(pkg, SshManager.OpenParams(host = "", user = "root"))
        error("空 host 应抛出异常")
    } catch (e: IllegalArgumentException) {
        check(e.message?.contains("缺少 host") == true)
        println("  [SSH] ✓ 空 host 参数校验通过")
    }

    try {
        SshManager.open(pkg, SshManager.OpenParams(host = "127.0.0.1", user = "", port = 22))
        error("空 user 应抛出异常")
    } catch (e: IllegalArgumentException) {
        check(e.message?.contains("缺少 user") == true)
        println("  [SSH] ✓ 空 user 参数校验通过")
    }

    // 2.2 连接不可达地址超时与错误语义
    try {
        // 使用无法路由的测试地址或短超时触发连接超时
        SshManager.open(
            pkg,
            SshManager.OpenParams(
                host = "192.0.2.1", // TEST-NET-1 (RFC 5737)，不会有主机响应
                port = 22,
                user = "nobody",
                timeoutMs = 1200,
            )
        )
        error("不可达主机应连接失败")
    } catch (e: Exception) {
        val msg = e.message.orEmpty()
        check(msg.contains("连接超时") || msg.contains("连接失败")) {
            "错误信息未按终端插件契约包含超时/失败提示: $msg"
        }
        println("  [SSH] ✓ 连接超时契约对齐通过: $msg")
    }

    // 2.3 指纹不匹配错误语义
    // 连本地 22 端口，故意给一个错误的指纹测试拒绝逻辑
    try {
        SshManager.open(
            pkg,
            SshManager.OpenParams(
                host = "127.0.0.1",
                port = 22,
                user = "invalid_test_user_xyz",
                password = "wrong_password_123",
                fingerprint = "SHA256:NONEXISTENT_FINGERPRINT_FOR_TESTING_PURPOSES",
                timeoutMs = 3000,
            )
        )
        error("错误指纹应抛出异常")
    } catch (e: Exception) {
        val msg = e.message.orEmpty()
        check(msg.contains("主机密钥不匹配") || msg.contains("认证失败") || msg.contains("连接")) {
            "错误信息不匹配: $msg"
        }
        println("  [SSH] ✓ 指纹拦截与认证错误语义对齐通过: $msg")
    }

    // 2.4 会话不存在读取语义
    val readNonExistent = SshManager.read(pkg, "ssh_non_existent", 500)
    check(readNonExistent.closed) { "不存在会话 read 应返回 closed=true" }
    println("  [SSH] ✓ 不存在会话读取安全降级通过 (closed=${readNonExistent.closed})")
}

private fun testHttpEndpoints() {
    println("--- 3. 测试 HTTP 路由与权限门控 (HydroServer) ---")

    val tempDir = Files.createTempDirectory("hydrolink-net-test").toFile()
    val store = Store.open(tempDir)

    // 创建两个测试插件：一个无 tcp/ssh 权限，一个具有 tcp/ssh 权限
    val noPermDir = File(store.pluginsDir, "com.test.noperms").apply { mkdirs() }
    File(noPermDir, "manifest.json").writeText(
        """{"id":"com.test.noperms","name":"No Perms","version":"1.0.0","permissions":["storage"]}"""
    )

    val withPermDir = File(store.pluginsDir, "com.test.withperms").apply { mkdirs() }
    File(withPermDir, "manifest.json").writeText(
        """{"id":"com.test.withperms","name":"With Perms","version":"1.0.0","permissions":["storage","tcp","ssh"]}"""
    )

    val server = HydroServer(store, version = "1.3.0")
    server.start(0)
    val port = server.port
    val client = HttpClient.newHttpClient()
    println("  [HTTP] HydroServer 测试实例启动于端口: $port")

    val tcpServer = ServerSocket(0)
    val echoPort = tcpServer.localPort
    val echoThread = thread {
        try {
            val sock = tcpServer.accept()
            sock.close()
        } catch (_: Exception) {}
    }

    try {
        // 3.1 TCP 权限拒绝测试 (com.test.noperms)
        val reqNoTcp = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/hydro/worker/tcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"package":"com.test.noperms","op":"connect","host":"127.0.0.1","port":$echoPort}"""))
            .build()
        val respNoTcp = client.send(reqNoTcp, HttpResponse.BodyHandlers.ofString())
        check(respNoTcp.statusCode() == 403) { "无权限应返回 403: ${respNoTcp.statusCode()}" }
        check(respNoTcp.body().contains("权限不足: tcp")) { "错误信息不匹配: ${respNoTcp.body()}" }
        println("  [HTTP] ✓ TCP 权限门控拦截通过: 403 权限不足: tcp")

        // 3.2 TCP 权限允许并成功连接 (com.test.withperms)
        val reqWithTcp = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/hydro/worker/tcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"package":"com.test.withperms","op":"connect","host":"127.0.0.1","port":$echoPort}"""))
            .build()
        val respWithTcp = client.send(reqWithTcp, HttpResponse.BodyHandlers.ofString())
        check(respWithTcp.statusCode() == 200) { "有权限连接应成功: ${respWithTcp.statusCode()}" }
        check(respWithTcp.body().contains(""""ok":true""")) { "响应体不匹配: ${respWithTcp.body()}" }
        println("  [HTTP] ✓ TCP 权限放行与连接通过: 200 OK")

        // 3.3 SSH 权限拒绝测试 (com.test.noperms, 路径式 /api/hydro/worker/ssh/open)
        val reqNoSsh = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/hydro/worker/ssh/open"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"package":"com.test.noperms","host":"127.0.0.1","user":"root"}"""))
            .build()
        val respNoSsh = client.send(reqNoSsh, HttpResponse.BodyHandlers.ofString())
        check(respNoSsh.statusCode() == 403) { "无权限应返回 403: ${respNoSsh.statusCode()}" }
        check(respNoSsh.body().contains("权限不足: ssh")) { "错误信息不匹配: ${respNoSsh.body()}" }
        println("  [HTTP] ✓ SSH 路径式权限门控拦截通过: 403 权限不足: ssh")

        // 3.4 SSH 会话列表查询 (com.test.withperms)
        val reqListSsh = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/hydro/worker/ssh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"package":"com.test.withperms","op":"list"}"""))
            .build()
        val respListSsh = client.send(reqListSsh, HttpResponse.BodyHandlers.ofString())
        check(respListSsh.statusCode() == 200) { "SSH 列表查询失败: ${respListSsh.statusCode()}" }
        check(respListSsh.body().contains(""""ok":true""")) { "响应体不匹配: ${respListSsh.body()}" }
        check(respListSsh.body().contains(""""sessions":[]""")) { "初始会话列表应为空: ${respListSsh.body()}" }
        println("  [HTTP] ✓ SSH 会话列表查询通过: 200 OK")
    } finally {
        server.stop()
        tcpServer.close()
        echoThread.join(1000)
        tempDir.deleteRecursively()
        println("  [HTTP] ✓ 测试服务端与临时文件清理完成")
    }
}
