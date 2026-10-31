package com.hydro.link.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.hydro.link.compose.tray.NativeTray
import com.hydro.link.compose.platform.SingleInstanceLock
import com.hydro.link.compose.platform.PlatformFileChooser
import java.awt.Window as AwtWindow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hydro.link.compose.market.MarketClient
import com.hydro.link.server.HydroServer
import com.hydro.link.server.mdns.MdnsPublisher
import com.hydro.link.server.store.Store
import kotlinx.coroutines.awaitCancellation
import com.hydro.link.compose.webui.PluginWebUi
import com.hydro.link.compose.webui.PluginWorkerHosts
import com.hydro.link.compose.webui.SystemWebEngine
import com.hydro.link.compose.market.MarketResult
import com.hydro.link.shared.CmdEdit
import com.hydro.link.shared.DetailTarget
import com.hydro.link.shared.market.MarketEntry
import com.hydro.link.shared.model.Command
import com.hydro.link.shared.model.LogEntry
import com.hydro.link.shared.market.MarketSources
import com.hydro.link.shared.market.marketFirstBase
import com.hydro.link.shared.ui.HomeTab
import java.io.File
import com.hydro.link.shared.data.HydroApi
import com.hydro.link.shared.data.HttpTransport
import com.hydro.link.server.SystemCapabilities
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess
import com.hydro.link.shared.AppActions
import com.hydro.link.shared.AppModel
import com.hydro.link.shared.model.PairRequest
import com.hydro.link.shared.model.TrustedDevice
import com.hydro.link.shared.ui.App
import com.hydro.link.shared.ui.LogsBody
import com.hydro.link.compose.update.AppUpdateClient
import com.hydro.link.shared.update.AppUpdateHelper
import com.hydro.link.shared.update.AppUpdateInfo
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.hydro.link.server.plugin.PluginManifest
import kotlinx.serialization.json.Json

object AppInstanceBridge {
    @Volatile
    var wakeUpAction: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && pendingWakeUp) {
                pendingWakeUp = false
                javax.swing.SwingUtilities.invokeLater { value.invoke() }
            }
        }

    @Volatile
    private var pendingWakeUp = false

    fun wakeUp() {
        val action = wakeUpAction
        if (action != null) {
            javax.swing.SwingUtilities.invokeLater {
                action.invoke()
            }
        } else {
            pendingWakeUp = true
        }
    }
}

/**
 * 桌面端入口。
 *
 * **服务端是内置的 Kotlin 实现**（`server/HydroServer.kt`），应用自己起、自己停——
 * 不再依赖外部 Go 进程；行为一致性由 `./gradlew httpParity` 对着真 Go 二进制逐字段验证。
 * UI 通过 [HydroApi] 走 loopback HTTP 调它（②s 轮询一轮快照，动作直接打端点）。
 */
fun main() {
    // 0. 单进程排他锁检查：防止多实例并发启动，第二实例自动唤醒已有实例并退出
    val instanceLock = SingleInstanceLock.tryAcquire {
        AppInstanceBridge.wakeUp()
    }
    if (instanceLock == null) {
        // 已有实例在运行，已发送唤醒信号，本进程直接安全退出
        exitProcess(0)
    }

    try {
        application(exitProcessOnExit = false) {
    // 内置服务端（Kotlin 实现，行为与 Go 一致，见 server/HydroServer.kt）：
    // 应用自己起、自己停，不再依赖外部 Go 进程。
    val store = remember { Store.open() }
    val server = remember {
        HydroServer(store).apply {
            start(serverPort(store))
            println("[Server] Kotlin 服务端已启动 · 端口 $port")
            MdnsPublisher.start(port)
        }
    }
    val model = remember {
        val cfg = store.loadConfig()
        println("[Main] store.dir=${store.dir.absolutePath}, cfg.trayEnabled=${cfg.trayEnabled}")
        AppModel().apply {
            baseUrl = baseUrlOf(server.port)
            trayEnabled = cfg.trayEnabled != false
        }
    }
    // 插件清单里的相对图标要拼成绝对 URL 才能给手表渲染器取；优先用局域网 IP
    server.baseUrlProvider = {
        val lanIp = SystemCapabilities.localIp()
        if (lanIp.isNotBlank() && lanIp != "127.0.0.1") {
            "http://$lanIp:${server.port}"
        } else {
            baseUrlOf(server.port)
        }
    }
    val api = remember { HydroApi(HttpTransport(baseUrlOf(server.port))) }
    val marketClient = remember {
        MarketClient(
            cacheDir = File(System.getProperty("user.home"), ".hydrolink/market"),
            sourceSetting = { model.marketSource },
        )
    }
    var awtWindow: AwtWindow? by remember { mutableStateOf(null) }
    val actions = remember { desktopActions(model, api, marketClient, store, server) { awtWindow } }

    // 关闭应用时把服务端停掉并注销 mDNS 广播
    LaunchedEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            MdnsPublisher.stop()
            server.stop()
        }
    }

    // 构建指纹只打到 stdout（窗口标题保持 "HydroLink" 不动）——之前为了确认「跑的是哪一版」
    // 反复折腾，标题里挂个 build id 明显多余。
    //
    // 这行**不能留在组合里**：`application { }` 的内容 lambda 每次重组都会重跑，
    // 于是日志里同一个 build id 会被打好几遍（看起来像应用启动了好几次），
    // 真正的问题会被淹没。remember 之后只打一次。
    val buildTag = remember {
        val tag = "b" + System.currentTimeMillis().toString(16).takeLast(6)
        println(
            "HydroLink 桌面端 $tag · 侧栏 ${HomeTab.entries.size} 项：" +
                HomeTab.entries.joinToString("/") { it.title } +
                " · 引擎 " + (System.getenv("HYDROLINK_ENGINE") ?: "miuix")
        )
        tag
    }
    // 首次进来拉一次市场索引与检查应用更新（延时 1 秒，避开首帧初始化时序）
    LaunchedEffect(Unit) { loadMarket(model, marketClient) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000)
        checkAppUpdate(model, store, manual = false)
    }

    // worker 宿主：每个已注册插件一个隐藏窗口跑胶水（胶水与服务端同一份协议）。
    // 端口变了 key 就变 → 旧窗口被换掉重建（否则会一直打没人听的端口）。
    val workerHosts = remember { PluginWorkerHosts() }
    // 插件 console.*（经胶水 __hkLog）进应用日志面板：手表侧没有日志出口，排障全靠这条
    remember(workerHosts, server) { workerHosts.logSink = { server.addWorkerLog(it) } }
    LaunchedEffect(model.plugins.map { it.id }, model.baseUrl) {
        workerHosts.sync(model.plugins.map { it.id }, model.baseUrl)
    }
    DisposableEffect(workerHosts, server) {
        server.runtime.bridge.onNeedWake = { pkg ->
            workerHosts.ensure(pkg, model.baseUrl)
        }
        onDispose {
            server.runtime.bridge.onNeedWake = null
        }
    }

    // 预热系统 WebView：worker 宿主窗口要用它，插件 WebUI 窗口也要用。
    // 系统 webview 不需要下载几百 MB（这是换掉 CEF 之后最直接的好处），但建 WebKit 视图
    // 仍要起 GTK/原生上下文，所以有插件时先在后台探一次可用性，别等用户点「界面」。
    LaunchedEffect(model.plugins.isNotEmpty()) {
        if (model.plugins.isNotEmpty()) SystemWebEngine.available
    }

    // 轮询：一轮里任何一个端点失败都只把 reachable 置 false，不清空已有数据
    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = api.snapshot()
            model.apply {
                serverRunning = snapshot.reachable
                serverVersion = snapshot.version
                ipText = snapshot.ip
                devices = snapshot.devices
                pairs = snapshot.pairs
                commands = snapshot.commands
                plugins = snapshot.plugins
                logs = snapshot.logs
                pluginSummary = snapshot.pluginSummary
                pluginMessage = snapshot.error
                port = snapshot.settings.port
                acceptAll = snapshot.settings.acceptAll
                // 服务端没配过（空串）统一落到 Gh-Proxy
                marketSource = snapshot.settings.marketSource
                    .takeIf { it.isNotBlank() } ?: MarketSources.GhProxy.key
                // WebUI 要同源访问服务端，用真实监听端口拼地址
                baseUrl = baseUrlOf(server.port)
                portText = if (snapshot.settings.currentPort > 0) {
                    snapshot.settings.currentPort.toString()
                } else {
                    snapshot.settings.port.toString()
                }
            }
            delay(2000)
        }
    }

    val appIcon = painterResource("appicon.png")
    var isMainWindowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(1080.dp, 720.dp))

    val performQuit = remember(server, workerHosts) {
        {
            println("[Main] 正在彻底退出 HydroLink...")
            isMainWindowVisible = false
            kotlin.concurrent.thread(isDaemon = false) {
                runCatching { workerHosts.stopAll() }
                runCatching { server.stop() }
                PlatformExit.exitCleanly(0)
            }
        }
    }

    val showMainWindow = remember {
        {
            isMainWindowVisible = true
            windowState.isMinimized = false
            awtWindow?.let { win ->
                win.isVisible = true
                if (win is java.awt.Frame) {
                    if ((win.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                        win.extendedState = java.awt.Frame.NORMAL
                    }
                }
                try {
                    win.isAlwaysOnTop = true
                    win.toFront()
                    win.requestFocus()
                    win.isAlwaysOnTop = false
                } catch (_: Throwable) {
                    win.toFront()
                    win.requestFocus()
                }
            }
            Unit
        }
    }

    DisposableEffect(showMainWindow) {
        AppInstanceBridge.wakeUpAction = showMainWindow
        onDispose {
            AppInstanceBridge.wakeUpAction = null
        }
    }

    val promptedPairs = remember { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }

    // 当手表/客户端发起配对请求时，由服务端回调直通 UI 弹窗并唤醒窗口与发送系统通知
    DisposableEffect(server, showMainWindow) {
        server.onPairRequested = { deviceId, deviceName ->
            val pair = PairRequest(deviceId, deviceName)
            promptedPairs.add(deviceId)
            javax.swing.SwingUtilities.invokeLater {
                model.pairConfirmTarget = pair
                showMainWindow()
            }
            SystemCapabilities.notify("HydroLink 配对请求", "${pair.deviceName.ifBlank { "未知设备" }} 请求连接")
        }
        onDispose {
            server.onPairRequested = null
        }
    }

    // 轮询或冷启动时检测未弹窗的待配对设备（双保险：即便漏掉回调也能立即触发弹窗）
    LaunchedEffect(model.pairs) {
        val currentIds = model.pairs.map { it.deviceId }.toSet()
        promptedPairs.retainAll(currentIds)
        if (model.pairConfirmTarget == null) {
            val unprompted = model.pairs.firstOrNull { it.deviceId !in promptedPairs }
            if (unprompted != null) {
                promptedPairs.add(unprompted.deviceId)
                model.pairConfirmTarget = unprompted
                showMainWindow()
                SystemCapabilities.notify("HydroLink 配对请求", "${unprompted.deviceName.ifBlank { "未知设备" }} 请求连接")
            }
        }
    }

    val toggleMainWindow = remember {
        {
            if (!isMainWindowVisible || windowState.isMinimized) {
                showMainWindow()
            } else {
                isMainWindowVisible = false
            }
            Unit
        }
    }

    // 在 Linux 下优先采用与桌面 StatusNotifierItem 协议直连的原生托盘（解决 Wayland/KDE 下 AWT SystemTray 无法点击和弹出的问题）
    val nativeTray = remember {
        if (NativeTray.isLinux) {
            NativeTray(
                onActivate = { showMainWindow() },
                onQuit = { performQuit() },
            )
        } else null
    }

    var isNativeTrayActive by remember { mutableStateOf(false) }

    LaunchedEffect(model.trayEnabled, nativeTray) {
        if (nativeTray != null) {
            if (model.trayEnabled) {
                val ok = nativeTray.start()
                isNativeTrayActive = ok
            } else {
                nativeTray.stop()
                isNativeTrayActive = false
            }
        }
    }

    DisposableEffect(nativeTray) {
        onDispose {
            nativeTray?.stop()
        }
    }

    // Compose 原生托盘（非 Linux 或 Linux 下原生托盘未就绪时作为回退）
    if (model.trayEnabled && (!NativeTray.isLinux || !isNativeTrayActive)) {
        Tray(
            icon = appIcon,
            tooltip = "HydroLink",
            onAction = { toggleMainWindow() },
            menu = {
                Item(
                    text = if (isMainWindowVisible && !windowState.isMinimized) "隐藏主窗口" else "显示主窗口",
                    onClick = { toggleMainWindow() },
                )
                Separator()
                Item(
                    text = "退出 HydroLink",
                    onClick = { performQuit() },
                )
            },
        )
    }

    Window(
        onCloseRequest = {
            println("[Main] onCloseRequest 触发, model.trayEnabled=${model.trayEnabled}")
            if (model.trayEnabled) {
                // 常驻后台模式：隐藏主窗口到托盘，服务继续运行
                println("[Main] 隐藏主窗口到托盘，后台持续运行")
                isMainWindowVisible = false
            } else {
                // 退出模式：直接彻底关闭进程
                println("[Main] 托盘未启用，直接退出进程")
                performQuit()
            }
        },
        visible = isMainWindowVisible,
        state = windowState,
        icon = appIcon,
        title = "HydroLink",
    ) {
        LaunchedEffect(window) {
            awtWindow = window
            appIconAwtImage?.let { img ->
                runCatching { window.iconImages = listOf(img) }
            }
        }
        App(model = model, actions = actions)
    }

    // 插件 WebUI：独立窗口 + 系统 WebView（页面与安卓端同源，插件不用写第二套页面）
    val webUi = model.webUiPlugin
    if (webUi != null) {
        val webUiIcon = remember(webUi.id) { loadPluginIcon(webUi.id) } ?: appIcon
        Window(
            onCloseRequest = { model.webUiPlugin = null },
            state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
            title = webUi.name,
            icon = webUiIcon,
        ) {
            LaunchedEffect(window, webUi.id) {
                val icon = loadPluginIconAwt(webUi.id) ?: appIconAwtImage
                icon?.let { img ->
                    runCatching { window.iconImages = listOf(img) }
                }
            }
            val isDark = isSystemInDarkTheme()
            val md3Colors = if (isDark) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            // 外层先给一份跟随系统的 Material3 方案，避免 miuix 模式下裸组件退回浅色
            MaterialTheme(colorScheme = md3Colors) {
                val content: @Composable () -> Unit = {
                    PluginWebUi(
                        pluginId = webUi.id,
                        pluginName = webUi.name,
                        baseUrl = model.baseUrl,
                        onBack = { model.webUiPlugin = null },
                        miuix = model.miuix,
                    )
                }
                if (model.miuix) {
                    MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
                } else {
                    content()
                }
            }
        }
    }

    // 运行日志：独立窗口（安卓端是独立 Activity + 系统返回栈，桌面没有返回栈）
    if (model.openLogs) {
        Window(
            onCloseRequest = { model.openLogs = false },
            state = rememberWindowState(size = DpSize(760.dp, 560.dp)),
            title = "运行日志",
            icon = appIcon,
        ) {
            LaunchedEffect(window) {
                appIconAwtImage?.let { img ->
                    runCatching { window.iconImages = listOf(img) }
                }
            }
            val content: @Composable () -> Unit = {
                LogsBody(logs = model.logs, miuix = model.miuix)
            }
            if (model.miuix) {
                MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) { content() }
            } else {
                // 日志是独立窗口，主题得自己给一份（裸 MaterialTheme 是浅色方案）
                MaterialTheme(
                    colorScheme = if (isSystemInDarkTheme()) {
                        androidx.compose.material3.darkColorScheme()
                    } else {
                        androidx.compose.material3.lightColorScheme()
                    },
                ) { content() }
            }
        }
    }
    }
    } finally {
        instanceLock.close()
    }
}

/** 拉一次市场索引（搜索/分类在界面上过滤，这里只管原始列表）。 */
private suspend fun loadMarket(model: AppModel, client: MarketClient) {
    model.market = model.market.copy(loading = true, message = null)
    val result = withContext(Dispatchers.IO) { client.fetchIndex() }
    model.market = when (result) {
        is MarketResult.Ok -> model.market.copy(
            loading = false,
            entries = result.index.entries,
            baseHint = result.base,
            message = null,
        )
        is MarketResult.Err -> model.market.copy(loading = false, message = result.message)
    }
}

/**
 * 装一个插件（市场下载或本地包）：校验/下载 → 调用接口安装（它会热注册）。
 * 插件存储按安卓端语义保留。
 */
private suspend fun installMarketEntry(
    model: AppModel,
    api: HydroApi,
    client: MarketClient,
    entry: MarketEntry,
) {
    model.market = model.market.copy(installing = entry.id, message = null)
    val isLocal = File(entry.url).isFile
    val error = withContext(Dispatchers.IO) {
        val targetPath = if (isLocal) {
            entry.url
        } else {
            val target = File(System.getProperty("user.home"), ".hydrolink/downloads/${entry.id}.hpk")
            val base = model.market.baseHint ?: marketFirstBase(model.marketSource)
            val downloadError = client.download(base, entry, target)
            if (downloadError != null) return@withContext downloadError
            target.absolutePath
        }
        api.installPlugin(targetPath)
    }
    if (error == null) {
        model.market = model.market.copy(
            installing = null,
            installed = model.market.installed + entry.id,
        )
        val successLog = if (isLocal) {
            "已安装本地插件: ${entry.name} v${entry.version}"
        } else {
            "已安装插件: ${entry.name} v${entry.version}"
        }
        model.logs = (model.logs + LogEntry(successLog, "success")).takeLast(200)
    } else {
        val errLog = "安装插件 ${entry.name} 失败: $error"
        model.logs = (model.logs + LogEntry(errLog, "error")).takeLast(200)
        model.market = model.market.copy(installing = null, message = "安装失败：$error")
    }

    // 安装完成后立即刷新已装插件列表，使界面实时呈现
    runCatching {
        val plugins = api.listPlugins()
        model.plugins = plugins
        model.pluginSummary = "已安装 ${plugins.size} 个插件"
    }
}

/** 服务端地址：默认本机 9000，可用环境变量指到别的实例（真机联调手表时用）。 */
private fun baseUrl(): String =
    System.getenv("HYDROLINK_BASE_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:9000"

/**
 * 插件 WebUI 是否用内嵌（系统 WebView）窗口。**默认内嵌**。
 *
 * 引擎就是系统那块 webview（与 Go 端 Wails 同一个引擎，见 `SystemWebEngine`），
 * 没有下载、没有额外进程，也不用再担心 CEF 自飘一个 Chromium 窗口。
 * 真要退回系统浏览器：`HYDROLINK_WEBUI=browser`。
 */
private fun webUiInApp(): Boolean =
    !System.getenv("HYDROLINK_WEBUI").equals("browser", ignoreCase = true)

/** 用系统默认浏览器打开 URL。 */
private fun openInSystemBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() &&
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
        ) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } else {
            // Desktop 不可用（无桌面环境/精简 JRE）时退回 xdg-open
            ProcessBuilder("xdg-open", url).start()
        }
    }.onFailure { println("[WebUI] 打开系统浏览器失败: ${it.message}") }
}

/** 按服务端实际监听端口拼同源地址（插件 WebUI 要用它，端口错了页面打不开）。 */
/** 服务端监听端口：优先环境变量 HYDROLINK_PORT，其次配置中的 lastPort，兜底 9000。 */
private fun serverPort(store: Store): Int {
    val env = System.getenv("HYDROLINK_PORT")?.toIntOrNull()
    if (env != null && env > 0) return env
    val configured = store.loadConfig().lastPort
    if (configured > 0) return configured
    return Store.DEFAULT_PORT
}

private fun baseUrlOf(currentPort: Int): String {
    val base = baseUrl()
    return if (currentPort > 0) {
        val withoutPort = base.substringBeforeLast(':')
        "$withoutPort:$currentPort"
    } else {
        base
    }
}

/** 动作全部落到 Go 端点；状态由 2 秒轮询回填（不本地乐观更新，避免和后端打架）。 */
private fun desktopActions(
    model: AppModel,
    api: HydroApi,
    marketClient: MarketClient,
    store: Store,
    server: HydroServer,
    parentWindow: () -> AwtWindow?,
): AppActions {
    val scope = CoroutineScope(Dispatchers.Default)
    return AppActions(
        onRefresh = {},
        onOpenLogs = { model.openLogs = true },
        onOpenTab = { tab -> model.tab = tab },
        onAcceptPair = { pair ->
            scope.launch {
                api.respondPair(pair.deviceId, accept = true, deviceName = pair.deviceName)
                if (model.pairConfirmTarget?.deviceId == pair.deviceId) {
                    model.pairConfirmTarget = null
                }
            }
        },
        onRejectPair = { pair ->
            scope.launch {
                api.respondPair(pair.deviceId, accept = false)
                if (model.pairConfirmTarget?.deviceId == pair.deviceId) {
                    model.pairConfirmTarget = null
                }
            }
        },
        onPromptPair = { pair -> model.pairConfirmTarget = pair },
        onCancelPairPrompt = { model.pairConfirmTarget = null },
        onDeleteDevice = { id -> scope.launch { api.deleteDevice(id) } },
        onAddCommand = { model.cmdEdit = CmdEdit(editIndex = null) },
        onEditCommand = { index ->
            val existing = model.commands.getOrNull(index) ?: return@AppActions
            model.cmdEdit = CmdEdit(editIndex = index, name = existing.name, command = existing.command)
        },
        onDeleteCommand = { index ->
            store.removeCommand(index)
            model.commands = store.loadCommands().map { Command(name = it.name, command = it.command) }
        },
        onSaveCommand = { index, name, command ->
            if (name.isBlank() || command.isBlank()) {
                false
            } else {
                val cleanName = name.trim()
                val cleanCmd = command.trim()
                if (index == null) {
                    store.addCommand(cleanName, cleanCmd)
                } else {
                    val current = store.loadCommands().toMutableList()
                    if (index in current.indices) {
                        current[index] = com.hydro.link.server.store.Command(cleanName, cleanCmd)
                        store.saveCommands(current)
                    }
                }
                model.commands = store.loadCommands().map { Command(name = it.name, command = it.command) }
                model.cmdEdit = null
                true
            }
        },
        onRefreshPlugins = {
            scope.launch {
                val plugins = api.listPlugins()
                model.plugins = plugins
                model.pluginSummary = "已安装 ${plugins.size} 个插件"
            }
        },
        // 点击插件页「+」号：弹出文件选择器，选取本地 .hpk 文件导入并弹窗确认
        onInstallPlugin = {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    pickPluginFile(parentWindow())
                }
                if (file != null) {
                    try {
                        val entry = withContext(Dispatchers.IO) {
                            inspectPluginPackage(file)
                        }
                        model.marketInstallTarget = entry
                    } catch (e: Exception) {
                        val msg = "读取插件安装包失败: ${e.message}"
                        model.logs = (model.logs + LogEntry(msg, "error")).takeLast(200)
                        model.market = model.market.copy(message = msg)
                    }
                }
            }
        },
        onOpenMarket = { model.tab = HomeTab.Market },
        onMarketSearch = { query -> model.marketQuery = query },
        onMarketCategory = { category -> model.marketCategory = category },
        onMarketRefresh = { scope.launch { loadMarket(model, marketClient) } },
        onMarketInstall = { entry -> model.marketInstallTarget = entry },
        onCancelMarketInstall = { model.marketInstallTarget = null },
        onConfirmMarketInstall = { entry ->
            model.marketInstallTarget = null
            scope.launch { installMarketEntry(model, api, marketClient, entry) }
        },
        onMarketSourceChange = { key ->
            model.marketSource = key
            scope.launch {
                api.saveSettings(marketSource = key)
                loadMarket(model, marketClient)
            }
        },
        // 默认在应用内开一个窗口（同源 URL，插件页面直接能调 kit）。
        // 只有两种情况退回系统浏览器：显式 HYDROLINK_WEBUI=browser，或系统 webview 确实
        // 起不来（Linux 缺 libwebkit2gtk 之类）——这时给用户一个能用的页面比报错强。
        onPluginOpenWebUi = { row ->
            val url = "${model.baseUrl}/api/hydro/webui/${row.id}/index.html"
            if (webUiInApp() && SystemWebEngine.available) {
                model.webUiPlugin = row
            } else {
                // 原因写进应用日志窗口（用户反馈"怎么还是浏览器"时这里能自证）
                SystemWebEngine.error?.let { reason ->
                    model.logs = (model.logs + LogEntry(
                        "系统 WebView 不可用（$reason），改用系统浏览器打开",
                        "warning",
                    )).takeLast(200)
                }
                openInSystemBrowser(url)
            }
        },
        onRequestUninstall = { row -> model.uninstallTarget = row },
        onCancelUninstall = { model.uninstallTarget = null },
        onUninstallConfirmed = {
            val row = model.uninstallTarget ?: return@AppActions
            model.uninstallTarget = null
            model.detail = null
            scope.launch {
                api.uninstallPlugin(row.id)
                api.reloadPlugins()
            }
        },
        onPluginDetails = { row -> model.detail = DetailTarget.Installed(row) },
        onMarketDetails = { entry ->
            model.detail = DetailTarget.MarketEntryItem(
                entry,
                installed = entry.id in model.plugins.map { it.id },
            )
        },
        onDetailBack = { model.detail = null },
        onPortChange = { newPort ->
            scope.launch {
                if (newPort in 1..65535 && newPort != server.port) {
                    val success = server.restart(newPort)
                    if (success) {
                        val newBaseUrl = baseUrlOf(server.port)
                        api.updateBaseUrl(newBaseUrl)
                        model.baseUrl = newBaseUrl
                        model.port = server.port
                        model.portText = server.port.toString()
                        MdnsPublisher.start(server.port)
                        println("[Main] 服务端端口已自动切换至 ${server.port} 并应用")
                    }
                }
            }
        },
        onAcceptAllChange = { value -> scope.launch { api.saveSettings(acceptAll = value) } },
        onToggleTray = { enabled ->
            model.trayEnabled = enabled
            scope.launch {
                val cfg = store.loadConfig().copy(trayEnabled = enabled)
                store.saveConfig(cfg)
            }
        },
        onToggleMiuix = { model.miuix = it },
        onCheckAppUpdate = {
            scope.launch { checkAppUpdate(model, store, manual = true) }
        },
        onCancelAppUpdate = {
            model.appUpdateTarget = null
            scope.launch {
                val today = java.time.LocalDate.now().toString()
                val cfg = store.loadConfig().copy(lastUpdateIgnoreDate = today)
                store.saveConfig(cfg)
            }
        },
        onConfirmAppUpdate = { update ->
            model.appUpdateTarget = null
            if (update.password.isNotBlank()) {
                runCatching {
                    val sel = java.awt.datatransfer.StringSelection(update.password)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                    model.logs = (model.logs + LogEntry("网盘提取码「${update.password}」已复制到剪贴板", "info")).takeLast(200)
                }
            }
            val url = update.downloadUrl.ifBlank { update.backupUrl }
            if (url.isNotBlank()) openInSystemBrowser(url)
        },
    )
}

/** 检查桌面端版本更新（基于 hydromarket-index/app.json 与网盘分发）。 */
private suspend fun checkAppUpdate(model: AppModel, store: Store, manual: Boolean) {
    val today = java.time.LocalDate.now().toString()
    if (manual) {
        if (model.checkingAppUpdate) return
        model.checkingAppUpdate = true
        model.updateNotice = "正在检查更新..."
    }

    val client = AppUpdateClient()
    val bases = com.hydro.link.shared.market.marketBases(model.marketSource)
    val update = withContext(Dispatchers.IO) { client.fetchUpdate(bases) }

    if (manual) model.checkingAppUpdate = false

    if (update == null) {
        if (manual) {
            model.updateNotice = "检查更新失败，请稍后重试"
            model.logs = (model.logs + LogEntry("检查更新失败，请稍后重试", "warning")).takeLast(200)
        }
        return
    }

    val hasNewer = AppUpdateHelper.isNewer(update.version, model.appVersion)
    val isForce = AppUpdateHelper.isForceUpdate(update, model.appVersion)

    if (hasNewer) {
        if (manual || isForce) {
            model.appUpdateTarget = update
            if (manual) model.updateNotice = "发现新版本 v${update.version}"
        } else {
            val latestCfg = store.loadConfig()
            val shouldAuto = AppUpdateHelper.shouldAutoCheck(latestCfg.lastUpdateCheckDate, today)
            val shouldPrompt = AppUpdateHelper.shouldPrompt(latestCfg.lastUpdateIgnoreDate, today)
            if (shouldAuto && shouldPrompt) {
                model.appUpdateTarget = update
                store.saveConfig(latestCfg.copy(lastUpdateCheckDate = today))
            }
        }
    } else {
        if (manual) {
            model.updateNotice = "当前已是最新版本 (v${model.appVersion})"
            model.logs = (model.logs + LogEntry("当前已是最新版本 (v${model.appVersion})", "info")).takeLast(200)
        } else {
            val latestCfg = store.loadConfig()
            store.saveConfig(latestCfg.copy(lastUpdateCheckDate = today))
        }
    }
}

/** 尝试从插件安装目录中读取自定义图标，失败或不存在时返回 null（退回 HydroLink 应用图标）。 */
private fun loadPluginIcon(pluginId: String): Painter? = runCatching {
    val pluginsDir = Store.dataDir().resolve("plugins").resolve(pluginId)
    if (!pluginsDir.isDirectory) return@runCatching null

    var iconFile: File? = null
    val manifestFile = pluginsDir.resolve("manifest.json")
    if (manifestFile.isFile) {
        val manifest = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<PluginManifest>(manifestFile.readText())
        }.getOrNull()
        val appIconPath = manifest?.apps?.firstOrNull { it.icon.isNotBlank() }?.icon
        if (!appIconPath.isNullOrBlank()) {
            val candidate = pluginsDir.resolve(appIconPath)
            if (candidate.isFile) iconFile = candidate
        }
    }
    if (iconFile == null) {
        val fallback = pluginsDir.resolve("icon.png")
        if (fallback.isFile) iconFile = fallback
    }
    if (iconFile == null) {
        val fallbackJpg = pluginsDir.resolve("icon.jpg")
        if (fallbackJpg.isFile) iconFile = fallbackJpg
    }

    iconFile?.let { file ->
        val bytes = file.readBytes()
        org.jetbrains.skia.Image.makeFromEncoded(bytes)?.let { skiaImage ->
            BitmapPainter(skiaImage.toComposeImageBitmap())
        }
    }
}.getOrNull()

/** 缓存应用图标的 AWT Image 实例，用于底层窗口或 Swing 对话框的 iconImage 设置 */
private val appIconAwtImage: java.awt.Image? get() = PlatformFileChooser.appIconAwtImage

/** 尝试从插件安装目录中读取自定义图标的 AWT Image 实例，供底层窗口直接应用 */
private fun loadPluginIconAwt(pluginId: String): java.awt.Image? = runCatching {
    val pluginsDir = Store.dataDir().resolve("plugins").resolve(pluginId)
    if (!pluginsDir.isDirectory) return@runCatching null

    var iconFile: File? = null
    val manifestFile = pluginsDir.resolve("manifest.json")
    if (manifestFile.isFile) {
        val manifest = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<PluginManifest>(manifestFile.readText())
        }.getOrNull()
        val appIconPath = manifest?.apps?.firstOrNull { it.icon.isNotBlank() }?.icon
        if (!appIconPath.isNullOrBlank()) {
            val candidate = pluginsDir.resolve(appIconPath)
            if (candidate.isFile) iconFile = candidate
        }
    }
    if (iconFile == null) {
        val fallback = pluginsDir.resolve("icon.png")
        if (fallback.isFile) iconFile = fallback
    }
    if (iconFile == null) {
        val fallbackJpg = pluginsDir.resolve("icon.jpg")
        if (fallbackJpg.isFile) iconFile = fallbackJpg
    }

    iconFile?.let { file ->
        javax.imageio.ImageIO.read(file)
    }
}.getOrNull()

/** 打开文件选择器，选取本地 .hpk 或 .zip 插件包。 */
private fun pickPluginFile(parent: AwtWindow?): File? =
    PlatformFileChooser.chooseOpenFile(
        parent = parent,
        title = "选择 HydroLink 插件安装包 (.hpk / .zip)",
        filterDescription = "HydroLink 插件包 (*.hpk, *.zip)",
        extensions = listOf("hpk", "zip"),
    )

/** 读取并校验本地插件包中的 manifest.json，计算文件 SHA-256，封装为安装确认弹窗所需的 MarketEntry。 */
private fun inspectPluginPackage(file: File): MarketEntry {
    if (!file.isFile) throw IllegalArgumentException("安装包文件不存在: ${file.absolutePath}")
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val manifest: PluginManifest = java.util.zip.ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json")
            ?: zip.entries().toList().firstOrNull { it.name.endsWith("/manifest.json") }
            ?: throw IllegalArgumentException("安装包缺少 manifest.json")
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
        json.decodeFromString(PluginManifest.serializer(), text)
    }
    if (manifest.id.isBlank()) throw IllegalArgumentException("manifest 缺少 id")
    if (manifest.entry.isBlank()) throw IllegalArgumentException("manifest 缺少 entry")

    val md = java.security.MessageDigest.getInstance("SHA-256")
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
