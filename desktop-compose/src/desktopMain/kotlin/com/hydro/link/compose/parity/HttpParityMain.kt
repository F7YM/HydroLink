package com.hydro.link.compose.parity

import com.hydro.link.server.HydroServer
import com.hydro.link.server.store.Store
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP 级一致性测试：同一串请求分别打 **Go 进程** 与 **Kotlin 进程**，规范化后逐字段 diff。
 *
 * 相比存储层的文件 diff，这一层能覆盖「路由顺序、状态码、错误文案、门禁语义、JSON 形状」
 * 这些最容易分叉的地方。
 *
 * 规范化规则（两边不同但语义等价的字段要抹平，否则测的就不是行为）：
 * - `pid`、`trusted_at`、日志时间戳 → 占位符
 * - `system.hostname/version/ip`、`battery`、`music`、`performance` → 这些取自操作系统的
 *   实测值，两套实现只要「字段结构一致」就算过（数值比对留给各自的单元测试）
 * - 响应体末尾的换行（Go 的 json.Encoder 会补）统一去尾
 *
 * 用法：`./gradlew httpParity`
 */
fun main() {
    val workRoot = File(System.getenv("HYDROLINK_PARITY_DIR") ?: "/tmp/hydrolink-parity")
    val goDir = File(workRoot, "http-go")
    val kotlinDir = File(workRoot, "http-kotlin")
    val desktopDir = sequenceOf("desktop", "../desktop")
        .map { File(it).canonicalFile }
        .firstOrNull { File(it, "go.mod").isFile }
        ?: run { println("[FAIL] 找不到 desktop/go.mod"); return }

    // 两边各起一个服务：Go 用真二进制（headless 模式），Kotlin 用本实现
    val goBinary = File(workRoot, "hydrolink-go")
    if (!goBinary.isFile || !run(listOf("go", "build", "-o", goBinary.absolutePath, "./cmd/hydrolink"), desktopDir)) {
        println("[FAIL] Go 二进制构建失败")
        return
    }

    goDir.deleteRecursively()
    kotlinDir.deleteRecursively()
    initGoDir(goDir, desktopDir)

    val goPort = freePort()
    val goProcess = ProcessBuilder(
        goBinary.absolutePath, "-port", goPort.toString(),
    ).directory(goDir).redirectErrorStream(true).apply {
        environment()["XDG_CONFIG_HOME"] = goDir.absolutePath
    }.start()
    waitPort(goPort, 10_000)

    // Kotlin 侧从**同一份起点**出发：复制 Go 初始化好的数据目录（含已装的示例插件），
    // 否则两边的插件清单起点就不同，比的没意义
    val kotlinDataDir = File(kotlinDir, "hydrolink")
    File(goDir, "hydrolink").copyRecursively(kotlinDataDir, overwrite = true)
    val store = Store.open(kotlinDataDir)
    val server = HydroServer(store, version = "1.1.0")
    server.start(0)
    val kotlinPort = server.port

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    val problems = mutableListOf<String>()

    try {
        for (step in SCENARIO) {
            val goResp = request(client, "http://127.0.0.1:$goPort", step)
            val ktResp = request(client, "http://127.0.0.1:$kotlinPort", step)
            val a = normalize(goResp)
            val b = normalize(ktResp)
            if (a != b) {
                problems += "【${step.label}】响应不一致：\n  Go    : $a\n  Kotlin: $b"
            }
        }
    } finally {
        server.stop()
        goProcess.destroy()
    }

    if (problems.isEmpty()) {
        println("[PASS] HTTP 表面与 Go 行为一致（${SCENARIO.size} 条请求）")
    } else {
        println("[FAIL] HTTP 表面与 Go 不一致：")
        problems.forEach { println("  · $it") }
    }
}

// ------------------------------------------------------------ 场景

private data class Step(
    val label: String,
    val method: String,
    val path: String,
    val body: String? = null,
    val deviceId: String? = null,
)

private val SCENARIO = listOf(
    Step("ping", "GET", "/api/ping"),
    Step("ping 无 /api 前缀", "GET", "/ping"),
    Step("根路径", "GET", "/"),
    Step("version", "GET", "/api/version"),
    Step("尾部斜杠被去掉", "GET", "/api/status/"),
    Step("未知路由 404", "GET", "/api/nope"),
    Step("OPTIONS 预检", "OPTIONS", "/api/ping"),
    Step("状态（loopback 放行）", "GET", "/api/status"),
    Step("命令列表（loopback 放行）", "GET", "/api/commands"),
    Step("远端无 device id → 401", "GET", "/api/status", deviceId = null),
    Step("配对：空 device_id", "POST", "/api/pair", body = """{"device_id":"","device_name":"x"}"""),
    Step("配对：非法 JSON", "POST", "/api/pair", body = "not json"),
    Step("配对：新设备 → pending", "POST", "/api/pair", body = """{"device_id":"p1","device_name":"手表 P1"}"""),
    Step("配对：同一设备重复 → 仍 pending", "POST", "/api/pair", body = """{"device_id":"p1","device_name":"手表 P1"}"""),
    Step("待确认列表", "GET", "/api/pending_pair"),
    Step("check 未知设备 → pending", "GET", "/api/check/unknown"),
    Step("auth 缺 device id", "GET", "/api/auth"),
    Step("auth 未信任设备", "GET", "/api/auth", deviceId = "p1"),
    Step("respond：非法 action", "POST", "/api/pair/respond", body = """{"device_id":"p1","action":"nope"}"""),
    Step("respond：接受配对", "POST", "/api/pair/respond", body = """{"device_id":"p1","action":"accept","device_name":"手表 P1"}"""),
    Step("待确认列表（已摘掉）", "GET", "/api/pending_pair"),
    Step("auth 已信任设备", "GET", "/api/auth", deviceId = "p1"),
    Step("已配对设备", "GET", "/api/devices"),
    Step("新建命令：空名", "POST", "/api/commands", body = """{"name":"  ","command":"x"}"""),
    Step("新建命令", "POST", "/api/commands", body = """{"name":"回声","command":"echo hi"}"""),
    Step("改命令 0", "PUT", "/api/commands/0", body = """{"name":"回声","command":"echo ho"}"""),
    Step("改命令 越界 → 404", "PUT", "/api/commands/9", body = """{"name":"a","command":"b"}"""),
    Step("删命令 越界 → 404", "DELETE", "/api/commands/9"),
    Step("命令列表", "GET", "/api/commands"),
    Step("设置：端口越界 → 400", "PUT", "/api/settings", body = """{"port":70000}"""),
    Step("设置：改端口与 accept_all", "PUT", "/api/settings", body = """{"port":9100,"accept_all":true}"""),
    Step("读设置", "GET", "/api/settings"),
    Step("accept_all 打开后远端也放行", "GET", "/api/status", deviceId = null),
    Step("reload 用 GET → 405", "GET", "/api/hydro/reload"),
    Step("运行越界命令 → 404", "POST", "/api/commands/9/run"),
    Step("删设备", "DELETE", "/api/devices/p1"),
    Step("设备列表（已删）", "GET", "/api/devices"),
    // ---- 插件：清单、静态资源、分发 ----
    Step("插件列表", "GET", "/api/hydro/list"),
    Step("webui 首页", "GET", "/api/hydro/webui/com.demo.weather/index.html"),
    Step("webui 目录形式", "GET", "/api/hydro/webui/com.demo.weather/"),
    Step("webui 缺文件", "GET", "/api/hydro/webui/com.demo.weather/nope.html"),
    Step("webui 未知插件", "GET", "/api/hydro/webui/com.nope/index.html"),
    Step("webui 路径逃逸", "GET", "/api/hydro/webui/com.demo.weather/../../config.json"),
    Step("worker asset 图标", "GET", "/api/hydro/worker/asset?package=com.demo.weather&path=assets/icon.png"),
    Step("worker asset 逃逸", "GET", "/api/hydro/worker/asset?package=com.demo.weather&path=../../config.json"),
    Step("插件分发：四段路径不存在", "GET", "/api/hydro/com.demo.weather/app/main"),
    Step("插件分发：路径不合法", "GET", "/api/hydro/com.demo.weather/app"),
    Step("插件分发：未知插件", "GET", "/api/hydro/com.nope/app/main/view"),
    Step("reload GET → 405", "GET", "/api/hydro/reload"),
    Step("reload POST", "POST", "/api/hydro/reload"),
    // ---- worker 能力端点（权限门控）----
    Step("worker platform", "GET", "/api/hydro/worker/platform"),
    Step("worker 未知端点", "GET", "/api/hydro/worker/nope"),
    Step("kit.storage 缺权限", "POST", "/api/hydro/worker/store", body = """{"package":"com.demo.weather","op":"get","key":"x"}"""),
    Step("kit.storage 未知插件", "POST", "/api/hydro/worker/store", body = """{"package":"com.nope","op":"get","key":"x"}"""),
    Step("kit.storage watch 作用域", "POST", "/api/hydro/worker/store", body = """{"package":"com.demo.weather","op":"get","key":"x","scope":"watch"}"""),
    Step("kit.storage 非法 body", "POST", "/api/hydro/worker/store", body = "not json"),
    Step("kit.notify 缺权限", "POST", "/api/hydro/worker/notify", body = """{"package":"com.demo.weather","title":"t","text":"x"}"""),
    Step("kit.lock 缺权限", "POST", "/api/hydro/worker/lock", body = """{"package":"com.demo.weather"}"""),
    Step("kit.music 缺权限", "POST", "/api/hydro/worker/music", body = """{"package":"com.demo.weather","op":"now"}"""),
    Step("kit.commands 缺权限", "POST", "/api/hydro/worker/commands", body = """{"package":"com.demo.weather","op":"list"}"""),
    Step("kit.system 缺权限", "POST", "/api/hydro/worker/system", body = """{"package":"com.demo.weather","op":"perf"}"""),
    Step("kit.context 未知插件", "POST", "/api/hydro/worker/context", body = """{"package":"com.nope","surface":"app","item":"main"}"""),
)

// ------------------------------------------------------------ 跑请求

private fun request(client: HttpClient, base: String, step: Step): String {
    val builder = HttpRequest.newBuilder(URI.create(base + step.path))
        .timeout(Duration.ofSeconds(8))
    step.deviceId?.let { builder.header("X-Device-ID", it) }
    when (step.method) {
        "GET" -> builder.GET()
        "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        "DELETE" -> builder.DELETE()
        else -> builder.method(
            step.method,
            HttpRequest.BodyPublishers.ofString(step.body ?: "", Charsets.UTF_8),
        ).header("Content-Type", "application/json; charset=utf-8")
    }
    return try {
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        "HTTP ${resp.statusCode()} ${normalizeBody(resp.body())}"
    } catch (e: Exception) {
        "ERROR ${e::class.simpleName}: ${e.message}"
    }
}

/** 抹平两边必然不同但语义等价的字段。 */
private fun normalize(raw: String): String {
    var text = raw.trimEnd()
    val pid = Regex("\"pid\":\\s*\\d+").replace(text, "\"pid\":<pid>")
    val trustedAt = Regex("\"trusted_at\":\\s*\"[^\"]*\"").replace(pid, "\"trusted_at\":\"<time>\"")
    val key = Regex("\"encryption_key\":\\s*\"[0-9a-f]{64}\"").replace(trustedAt, "\"encryption_key\":\"<key>\"")
    val port = Regex("\"current_port\":\\s*\\d+").replace(key, "\"current_port\":<port>")
    // 操作系统实测值：只要字段在、类型对就算一致
    val hostname = Regex("\"hostname\":\\s*\"[^\"]*\"").replace(port, "\"hostname\":\"<host>\"")
    val sysVersion = Regex("\"version\":\\s*\"(?!ok|hydrolink)[^\"]*\"").replace(hostname, "\"version\":\"<sysver>\"")
    val ip = Regex("\"ip\":\\s*\"[^\"]*\"").replace(sysVersion, "\"ip\":\"<ip>\"")
    val percent = Regex("\"percent\":\\s*\\d+").replace(ip, "\"percent\":<n>")
    val cpu = Regex("\"cpu\":\\s*[0-9.]+").replace(percent, "\"cpu\":<f>")
    val memPct = Regex("\"mem_percent\":\\s*[0-9.]+").replace(cpu, "\"mem_percent\":<f>")
    val memUsed = Regex("\"mem_used\":\\s*\\d+").replace(memPct, "\"mem_used\":<n>")
    val memTotal = Regex("\"mem_total\":\\s*\\d+").replace(memUsed, "\"mem_total\":<n>")
    // 音乐字段依赖桌面会话，两边都可能空；只比字段在不在
    val title = Regex("\"title\":\\s*\"[^\"]*\"").replace(memTotal, "\"title\":\"<t>\"")
    val artist = Regex("\"artist\":\\s*\"[^\"]*\"").replace(title, "\"artist\":\"<a>\"")
    val exit = Regex("\"exit_code\":\\s*-?\\d+").replace(artist, "\"exit_code\":<n>")
    // 插件图标 URL 里带本服务的端口/地址，两边必然不同
    val iconUrl = Regex("http://[^\"]*/api/hydro/worker/asset[^\"]*").replace(exit, "<asset-url>")
    return iconUrl
}

/**
 * 规范化响应体：压掉多余空白 + **按键名排序**。
 *
 * 键序要忽略：Go 的 `map[string]any` 编码时会按字母序输出，而结构体按字段序；
 * Kotlin 这边是插入序。JSON 里键序没有语义，客户端不该依赖它——但排序后仍能逐字段比出差异。
 */
private fun normalizeBody(body: String): String {
    val compact = body.replace(Regex("\\s+"), " ").trim()
    return sortKeys(compact)
}

private val json = kotlinx.serialization.json.Json

private fun sortKeys(text: String): String = try {
    val element = json.parseToJsonElement(text)
    json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), sorted(element))
} catch (e: Exception) {
    text
}

private fun sorted(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
    when (element) {
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.buildJsonObject {
            element.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, sorted(v)) }
        }
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.buildJsonArray {
            element.forEach { add(sorted(it)) }
        }
        else -> element
    }

// ------------------------------------------------------------ 工具

private fun initGoDir(dir: File, desktopDir: File) {
    dir.mkdirs()
    // Go 服务按 XDG_CONFIG_HOME/hydrolink 找数据目录，所以让 XDG 指向 dir 的父目录
    File(dir, ".keep").writeText("")
    run(
        listOf("go", "run", "./cmd/parityinit", File(dir, "hydrolink").absolutePath),
        desktopDir,
        xdgConfigHome = dir.absolutePath,
    )
}

private fun run(
    cmd: List<String>,
    cwd: File,
    xdgConfigHome: String? = null,
): Boolean = try {
    val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
    // Go 侧按 XDG_CONFIG_HOME/hydrolink 找数据目录，Kotlin 侧是显式传目录——
    // 两边必须指向同一份数据（这也是「数据目录兼容」的验证）
    pb.environment()["XDG_CONFIG_HOME"] = xdgConfigHome ?: cwd.absolutePath
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) println("${cmd.joinToString(" ")} -> $code\n$out")
    code == 0
} catch (e: Exception) {
    println("${cmd.joinToString(" ")} 失败: ${e.message}")
    false
}

private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

/** 等端口起来（Go 起来比 Kotlin 慢一点）。 */
private fun waitPort(port: Int, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            java.net.Socket("127.0.0.1", port).close()
            return
        } catch (e: Exception) {
            Thread.sleep(200)
        }
    }
    println("[WARN] 端口 $port 在 ${timeoutMs}ms 内没起来")
}
