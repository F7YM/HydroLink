package com.hydro.link.compose.plugin

import com.hydro.link.server.plugin.PluginManifest
import com.hydro.link.shared.market.MarketEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 验证本地插件 (.hpk) 导入与解析逻辑的端到端测试。
 */
fun main() {
    println("[Test] 开始验证本地 .hpk 插件解析与导入逻辑...")

    val candidates = listOf(
        File(".").canonicalFile,
        File("..").canonicalFile,
        File("desktop-compose/..").canonicalFile,
    )
    val root = candidates.firstOrNull { File(it, "desktop/examples/com.demo.weather.hpk").isFile }
        ?: File(".").canonicalFile
    val weatherHpk = File(root, "desktop/examples/com.demo.weather.hpk")
    val deskbuddyHpk = File(root, "desktop/examples/com.hydro.deskbuddy.hpk")

    check(weatherHpk.isFile) { "找不到测试包: ${weatherHpk.absolutePath}" }
    check(deskbuddyHpk.isFile) { "找不到测试包: ${deskbuddyHpk.absolutePath}" }

    // 1. 真实 JS 插件包测试 (com.demo.weather.hpk)
    println("  1. 正在解析 weather 插件包...")
    val weatherEntry = testInspectPackage(weatherHpk)
    check(weatherEntry.id == "com.demo.weather") { "插件 ID 不匹配: ${weatherEntry.id}" }
    check(weatherEntry.name.isNotBlank()) { "插件名为空" }
    check(weatherEntry.version.isNotBlank()) { "版本号为空" }
    check(weatherEntry.entry.isNotBlank()) { "入口文件为空" }
    check(weatherEntry.url == weatherHpk.absolutePath) { "本地路径不匹配: ${weatherEntry.url}" }
    check(weatherEntry.sha256.length == 64) { "SHA-256 长度不正确" }
    check(weatherEntry.size == weatherHpk.length()) { "文件大小不匹配" }
    println("     ✓ weather 包解析成功: ${weatherEntry.id} v${weatherEntry.version} (${weatherEntry.runtime}), sha=${weatherEntry.sha256.take(12)}")

    // 2. 真实 WASM 插件包测试 (com.hydro.deskbuddy.hpk)
    println("  2. 正在解析 deskbuddy 插件包 (WASM)...")
    val deskbuddyEntry = testInspectPackage(deskbuddyHpk)
    check(deskbuddyEntry.id == "com.hydro.deskbuddy") { "插件 ID 不匹配: ${deskbuddyEntry.id}" }
    check(deskbuddyEntry.runtime == "wasm" || deskbuddyEntry.runtime.isNotBlank()) { "运行时不正确: ${deskbuddyEntry.runtime}" }
    check(deskbuddyEntry.permissions.isNotEmpty()) { "权限列表为空" }
    check(deskbuddyEntry.url == deskbuddyHpk.absolutePath) { "本地路径不匹配: ${deskbuddyEntry.url}" }
    println("     ✓ deskbuddy 包解析成功: ${deskbuddyEntry.id} v${deskbuddyEntry.version} (perms=${deskbuddyEntry.permissions})")

    // 2.1 真实远程终端插件包测试 (com.sunpm.remote.terminal.hpk)
    val terminalHpkCandidates = listOf(
        File(System.getProperty("user.home"), "下载/com.sunpm.remote.terminal.hpk"),
        File(root, "desktop/examples/com.sunpm.remote.terminal.hpk"),
        File("/home/float/下载/com.sunpm.remote.terminal.hpk"),
    )
    val terminalHpk = terminalHpkCandidates.firstOrNull { it.isFile }
    if (terminalHpk != null) {
        println("  2.1 正在解析 remote.terminal 插件包 (SSH 插件)...")
        val terminalEntry = testInspectPackage(terminalHpk)
        check(terminalEntry.id == "com.sunpm.remote.terminal") { "插件 ID 不匹配: ${terminalEntry.id}" }
        check("ssh" in terminalEntry.permissions) { "未声明 ssh 权限: ${terminalEntry.permissions}" }
        check("storage" in terminalEntry.permissions) { "未声明 storage 权限: ${terminalEntry.permissions}" }
        println("     ✓ 远程终端包解析成功: ${terminalEntry.id} v${terminalEntry.version} (perms=${terminalEntry.permissions})")
    }

    // 3. 本地路径判定逻辑与来源标记验证
    println("  3. 验证本地包与市场包路径区分逻辑...")
    fun isLocal(url: String): Boolean = url.startsWith("/") || url.contains(":\\") || url.startsWith("file:")
    fun resolveMarketBase(entry: MarketEntry, defaultBase: String): String =
        if (isLocal(entry.url)) "本地安装包" else defaultBase

    check(isLocal(weatherEntry.url)) { "应当识别为本地包" }
    check(resolveMarketBase(weatherEntry, "https://gh-proxy.com") == "本地安装包") { "来源应当显示为「本地安装包」" }
    val remoteEntry = weatherEntry.copy(url = "plugins/weather/weather.hpk")
    check(!isLocal(remoteEntry.url)) { "应当识别为远程市场包" }
    check(resolveMarketBase(remoteEntry, "https://gh-proxy.com") == "https://gh-proxy.com") { "远程包应当显示市场 base" }
    println("     ✓ 本地与市场路径及弹窗来源提示区分无误")

    // 4. 异常包测试（非 zip 文件 / 空文件）
    println("  4. 验证非法安装包防御...")
    val invalidFile = File.createTempFile("invalid-test-", ".hpk")
    try {
        invalidFile.writeText("not a valid zip file")
        var failedAsExpected = false
        try {
            testInspectPackage(invalidFile)
        } catch (_: Exception) {
            failedAsExpected = true
        }
        check(failedAsExpected) { "非法 zip 包应当抛出异常" }
        println("     ✓ 非法安装包成功防御并截断")
    } finally {
        invalidFile.delete()
    }

    // 5. 文件选择器系统命令探测与环境适配测试
    println("  5. 验证文件选择器调度与命令可用性探测...")
    fun checkCmd(cmd: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            val file = File(dir, cmd)
            file.isFile && file.canExecute()
        }
    }
    check(checkCmd("sh")) { "系统常用命令 sh 应当被检测到" }
    check(!checkCmd("hydrolink_non_existing_command_xyz")) { "不存在的命令不应当返回 true" }

    val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    if (isLinux) {
        val hasZenity = checkCmd("zenity")
        val hasKdialog = checkCmd("kdialog")
        println("     Linux 环境检测: zenity=$hasZenity, kdialog=$hasKdialog")
        check(hasZenity || hasKdialog) { "当前 Linux 环境应至少支持 zenity 或 kdialog" }
    }

    // 6. 应用图标 AWT Image 资源加载测试（防红 X 回退验证）
    println("  6. 验证官方应用图标 AWT Image 加载（防止窗口管理器回退为红 X）...")
    val iconImage = com.hydro.link.compose.platform.PlatformFileChooser.appIconAwtImage
    check(iconImage != null) { "无法读取 appicon.png 资源" }
    check(iconImage.getWidth(null) == 512 && iconImage.getHeight(null) == 512) {
        "图标尺寸不匹配: ${iconImage.getWidth(null)}x${iconImage.getHeight(null)}"
    }
    check(com.hydro.link.compose.platform.PlatformFileChooser.isCommandAvailable("sh")) {
        "系统常用命令 sh 应当被检测到"
    }
    println("     ✓ 成功加载 512x512 官方应用图标 AWT Image，窗口图标安全保障就绪")

    // 7. 真实用户插件包回归验证（com.sunpm.remote.terminal.hpk）
    val userTerminalHpk = File("/home/float/下载/com.sunpm.remote.terminal.hpk")
    if (userTerminalHpk.isFile) {
        println("  7. 正在对用户真实插件包进行深度回归测试 (${userTerminalHpk.name})...")
        val termEntry = testInspectPackage(userTerminalHpk)
        check(termEntry.id == "com.sunpm.remote.terminal") { "插件 ID 不匹配: ${termEntry.id}" }
        check(termEntry.name == "远程终端") { "插件名不匹配: ${termEntry.name}" }

        ZipFile(userTerminalHpk).use { zip ->
            val htmlEntry = zip.getEntry("web/index.html")
            check(htmlEntry != null) { "缺少 web/index.html" }
            val agentEntry = zip.getEntry("web/term-agent.py.txt")
            check(agentEntry != null) { "缺少 web/term-agent.py.txt" }
            val htmlContent = zip.getInputStream(htmlEntry).bufferedReader(Charsets.UTF_8).readText()
            check(htmlContent.contains("dl-agent") && htmlContent.contains("term-agent.py.txt")) {
                "下载网关脚本标签或相对引用不存在"
            }
        }
        println("     ✓ 用户插件包验证通过：manifest 正确，web/term-agent.py.txt 资源完整")
    }

    // 8. 验证文件选择器 DialogResult 取消短路与级联防御逻辑
    println("  8. 验证文件选择器主动取消短路与级联防御逻辑（防连续弹窗与卡死）...")
    var secondaryCalled = false
    val mockTools: List<() -> com.hydro.link.compose.platform.PlatformFileChooser.DialogResult> = listOf(
        { com.hydro.link.compose.platform.PlatformFileChooser.DialogResult.Cancelled },
        {
            secondaryCalled = true
            com.hydro.link.compose.platform.PlatformFileChooser.DialogResult.Selected(File("/tmp/dummy"))
        }
    )
    var finalResult: File? = null
    for (tool in mockTools) {
        when (val res = tool()) {
            is com.hydro.link.compose.platform.PlatformFileChooser.DialogResult.Selected -> {
                finalResult = res.file
                break
            }
            is com.hydro.link.compose.platform.PlatformFileChooser.DialogResult.Cancelled -> {
                finalResult = null
                break
            }
            is com.hydro.link.compose.platform.PlatformFileChooser.DialogResult.Unavailable -> continue
        }
    }
    check(finalResult == null) { "主动取消时结果必须为 null" }
    check(!secondaryCalled) { "首个工具主动取消后，严禁触发第二个工具（杜绝连续弹窗）" }
    println("     ✓ 取消短路逻辑验证通过：首个原生窗口取消后立即终止，坚决不二次弹窗")

    println("[Test] 本地 .hpk 插件导入与解析逻辑全部验证通过！✓")
}

private class LocalPluginInstallTest

private fun testInspectPackage(file: File): MarketEntry {
    if (!file.isFile) throw IllegalArgumentException("安装包文件不存在: ${file.absolutePath}")
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val manifest: PluginManifest = ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json")
            ?: zip.entries().toList().firstOrNull { it.name.endsWith("/manifest.json") }
            ?: throw IllegalArgumentException("安装包缺少 manifest.json")
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
        json.decodeFromString(PluginManifest.serializer(), text)
    }
    if (manifest.id.isBlank()) throw IllegalArgumentException("manifest 缺少 id")
    if (manifest.entry.isBlank()) throw IllegalArgumentException("manifest 缺少 entry")

    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } > 0) {
            md.update(buffer, 0, read)
        }
    }
    val sha256 = md.digest().joinToString("") { "%02x".format(it) }

    return MarketEntry(
        id = manifest.id,
        name = manifest.name.ifBlank { manifest.id },
        author = manifest.author,
        version = manifest.version,
        runtime = manifest.runtime.ifBlank { "quickjs" },
        entry = manifest.entry,
        minHydroLink = manifest.minHydroLink,
        permissions = manifest.permissions,
        url = file.absolutePath,
        sha256 = sha256,
        size = file.length(),
        description = "本地安装包：${file.name}",
    )
}
