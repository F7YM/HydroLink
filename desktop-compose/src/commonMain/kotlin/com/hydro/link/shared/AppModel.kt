package com.hydro.link.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hydro.link.shared.model.Command
import com.hydro.link.shared.model.LogEntry
import com.hydro.link.shared.model.PairRequest
import com.hydro.link.shared.model.PluginRow
import com.hydro.link.shared.market.MarketCategory
import com.hydro.link.shared.market.MarketSources
import com.hydro.link.shared.market.MarketEntry
import com.hydro.link.shared.market.MarketState
import com.hydro.link.shared.model.TrustedDevice
import com.hydro.link.shared.ui.HomeTab

/**
 * 界面只读的状态容器（与安卓端 `MiuixModel` 同构，名字改为跨端的 `AppModel`）。
 *
 * 约定照旧：**界面只读这里、不直接碰数据层**；数据层（S2 的 Go HTTP 客户端 / 插件安装）
 * 负责写入。两个引擎（miuix / MD3）共用同一份状态与动作，所以切引擎时界面数据不丢。
 */
class AppModel {
    /** 当前 Tab。放在状态里而不是界面里：卡片点击要能跳页（设备数量 → 设备页）。 */
    var tab by mutableStateOf(HomeTab.Status)
    /** 命令弹窗的编辑目标（null = 没有弹窗）。弹窗本体在引擎侧渲染。 */
    var cmdEdit by mutableStateOf<CmdEdit?>(null)
    /** 插件市场状态（列表、加载中、安装中、已装 id）。 */
    var market by mutableStateOf(MarketState())
    /** 市场的搜索词与分类筛选（列表按这两个过滤）。 */
    var marketQuery by mutableStateOf("")
    var marketCategory by mutableStateOf(MarketCategory.All)

    /**
     * 真正要显示的市场列表 = 搜索 + 分类过滤后的结果。
     *
     * 之前两个引擎都直接渲染 [MarketState.entries]，搜索词和分类 chip 只改状态不起作用——
     * 过滤必须在这里收口，不然两个引擎各写一遍又会长歪。
     */
    val marketVisible: List<MarketEntry>
        get() = market.entries.filter { entry ->
            val inCategory = marketCategory == MarketCategory.All ||
                MarketCategory.of(entry.category) == marketCategory
            val q = marketQuery.trim()
            val matches = q.isEmpty() ||
                entry.name.contains(q, ignoreCase = true) ||
                entry.description.contains(q, ignoreCase = true) ||
                entry.author.contains(q, ignoreCase = true) ||
                entry.keywords.any { it.contains(q, ignoreCase = true) }
            inCategory && matches
        }
    /** 详情页当前展示的对象（已装插件或市场条目；null = 没打开）。 */
    var detail by mutableStateOf<DetailTarget?>(null)

    /** 市场里哪些已经装了——按实际已装列表算，不是「本次会话装过」。 */
    val installedPluginIds: Set<String> get() = plugins.map { it.id }.toSet()

    /** 待确认安装的市场插件（非 null 时弹安装确认框，含 input 高危权限提示与 3s 倒计时）。 */
    var marketInstallTarget by mutableStateOf<MarketEntry?>(null)

    /** 待确认卸载的插件（非 null 时弹二次确认；与安卓端一致，不确认不动手）。 */
    var uninstallTarget by mutableStateOf<PluginRow?>(null)
    /** 正在打开 WebUI 的插件（null = 没打开）。桌面用独立窗口承载（同安卓端独立 Activity）。 */
    var webUiPlugin by mutableStateOf<PluginRow?>(null)
    /** 服务端实际监听的地址（WebUI 要同源访问，所以得用真实端口而不是默认 9000）。 */
    var baseUrl by mutableStateOf("http://127.0.0.1:9000")
    /** 运行日志窗口：桌面没有系统返回栈，日志按安卓端 LogsActivity 的做法开独立窗口。 */
    var openLogs by mutableStateOf(false)

    // ---- 状态 Tab ----
    var serverRunning by mutableStateOf(false)
    var ipText by mutableStateOf("")
    var portText by mutableStateOf("")
    var serverVersion by mutableStateOf("")
    var pairs by mutableStateOf<List<PairRequest>>(emptyList())
    /** 待确认的设备配对请求（非 null 时弹配对授权确认框）。 */
    var pairConfirmTarget by mutableStateOf<PairRequest?>(null)

    // ---- 设备 / 命令 / 插件 ----
    var devices by mutableStateOf<List<TrustedDevice>>(emptyList())
    var commands by mutableStateOf<List<Command>>(emptyList())
    var logs by mutableStateOf<List<LogEntry>>(emptyList())
    var pluginSummary by mutableStateOf("")
    var pluginMessage by mutableStateOf<String?>(null)
    var plugins by mutableStateOf<List<PluginRow>>(emptyList())
    /** 插件 Tab 顶部的「插件市场」入口是否显示为独立卡片（与安卓端一致）。 */
    var pluginUpdates by mutableStateOf<List<String>>(emptyList())
    var updateChecking by mutableStateOf(false)

    // ---- 设置 Tab ----
    var appName by mutableStateOf("HydroLink")
    var appVersion by mutableStateOf("1.3.0")
    var checkingAppUpdate by mutableStateOf(false)
    var updateNotice by mutableStateOf<String?>(null)
    var appUpdateTarget by mutableStateOf<com.hydro.link.shared.update.AppUpdateInfo?>(null)
    var port by mutableStateOf(9000)
    var acceptAll by mutableStateOf(false)
    /** 关闭主窗口时是否最小化到系统托盘（默认开启）。 */
    var trayEnabled by mutableStateOf(true)
    /** 插件市场镜像源：统一默认 Gh-Proxy。 */
    var marketSource by mutableStateOf(MarketSources.GhProxy.key)

    // ---- 外观（与安卓端同名同义）----
    /** false = Google Material 3，true = Miuix（对应设置里的「UI 引擎」）。 */
    var miuix by mutableStateOf(true)
}

/** 详情页展示什么。 */
sealed interface DetailTarget {
    /** 已安装插件。 */
    data class Installed(val plugin: PluginRow) : DetailTarget

    /** 市场条目（还没装）。 */
    data class MarketEntryItem(val entry: MarketEntry, val installed: Boolean) : DetailTarget
}
