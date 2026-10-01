package com.hydro.link.server.ui.miuix

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hydro.link.server.model.Command
import com.hydro.link.server.model.PairRequest
import com.hydro.link.server.model.TrustedDevice
import com.hydro.link.server.ui.PluginRow

/** miuix 模式下命令弹窗的编辑目标（[editIndex] == null 表示新建）。 */
data class CmdEdit(val editIndex: Int?, val name: String = "", val command: String = "")

/**
 * miuix 模式下五个 Tab 的共享状态：MainActivity 每 2 秒把最新数据发布进来
 * （与 XML 侧完全同一数据源），miuix Compose 界面只读这里、不直接碰 View；
 * MD3 模式下没有组合订阅这些状态，写入是零成本的。
 */
class MiuixModel {
    // 状态 Tab
    var serverRunning by mutableStateOf(false)
    var ipText by mutableStateOf("")
    var portText by mutableStateOf("")
    var accVisible by mutableStateOf(false)
    var accText by mutableStateOf("")
    var notifListenerVisible by mutableStateOf(false)
    var notifListenerText by mutableStateOf("")
    var notifPermVisible by mutableStateOf(false)
    var notifPermText by mutableStateOf("")
    var interconnectText by mutableStateOf("")
    var interconnectTone by mutableStateOf(Tone.Outline)
    var interconnectDetail by mutableStateOf("")
    var pairs by mutableStateOf<List<PairRequest>>(emptyList())
    /** 服务器版本（关于/信息卡）。 */
    var serverVersion by mutableStateOf("")

    // 设备 / 命令 / 插件
    var devices by mutableStateOf<List<TrustedDevice>>(emptyList())
    var commands by mutableStateOf<List<Command>>(emptyList())
    var pluginSummary by mutableStateOf("")
    var pluginMessage by mutableStateOf<String?>(null)
    var plugins by mutableStateOf<List<PluginRow>>(emptyList())

    // 设置 Tab
    var appName by mutableStateOf("")
    var rootStatus by mutableStateOf("")
    /** 命令授权器：none / root / shizuku。 */
    var authorizer by mutableStateOf("none")
    /** 授权器状态行文本。 */
    var authorizerStatus by mutableStateOf("")
    /** 授权器选了 Shizuku 但还没授权（决定是否显示「去授权」）。 */
    var authorizerNeedsGrant by mutableStateOf(false)
    /** MD3 二级页：授权器选择页（整页覆盖，期间隐藏底栏与底栏胶囊）。 */
    var authorizerPage by mutableStateOf(false)
    var bootStart by mutableStateOf(false)
    var liquidGlass by mutableStateOf(false)
    var miuix by mutableStateOf(false)

    // 对话框（WindowDialog 形态，替代 MD3 的 MaterialAlertDialogBuilder；
    // 提示一律走系统 Toast——Snackbar 会被底部 Tab 栏挡住）
    var cmdDialog by mutableStateOf<CmdEdit?>(null)
    var rootConfirm by mutableStateOf(false)
    /** 插件更新检查：正在查（插件 Tab 的刷新按钮转圈/提示用）。 */
    var updateChecking by mutableStateOf(false)
    /** 市场里有新版的已装插件；为空 = 没有可更新项。 */
    var pluginUpdates by mutableStateOf<List<com.hydro.link.server.market.PluginUpdate>>(emptyList())
    /**
     * 插件相关的确认/详情弹窗（安装确认、卸载确认、卡片详情）——由 [com.hydro.link.server.ui.
     * PluginUi] 的 miuix 引擎钩子投递进来，用 WindowDialog 渲染，替代 MD3 的
     * MaterialAlertDialogBuilder（否则 miuix 界面里会冒出 MD 风格弹窗）。
     */
    var pluginDialog by mutableStateOf<ConfirmDialog?>(null)
}

/**
 * 通用确认弹窗内容，字段与 MaterialAlertDialogBuilder 的 title / message /
 * 正负按钮一一对应（negative 为 null = 只有一个「知道了」按钮）。
 */
data class ConfirmDialog(
    val title: String,
    val message: String,
    val positive: String,
    val negative: String? = null,
    /** 点否定钮（如配对的「拒绝」）；单纯关窗/返回键不触发。排在 onConfirm 之前是有意的，
     *  尾随 lambda 只能落在 onConfirm 上（EngineDialog 同一个约定）。 */
    val onNegative: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
    /** 肯定钮是否可点：false 时灰显（例如宿主版本太低、更新装不了）。 */
    val positiveEnabled: Boolean = true,
    /** 倒计时秒数（高危权限安装强制等待）：> 0 时肯定钮禁用并显示倒计时。 */
    val countdownSeconds: Int = 0,
    /** 高危警示文案（红字突出显示）。 */
    val dangerWarning: String? = null,
)

/** 状态文案的语义色（成功/警告沿用应用自己的语义色，miuix 色板没有 success）。 */
enum class Tone { Outline, Warning, Success, Error }

/**
 * miuix 界面的动作出口：全部桥到 MainActivity 的现有实现——XML 开关走
 * performClick 复用原监听器（root 确认、落盘、后台探测一行不重复），
 * 保存/删除/安装等流程也直接复用。
 */
class MiuixActions(
    /** 刷新状态（状态卡点击触发，与 2 秒轮询同一入口）。 */
    val onRefresh: () -> Unit,
    /** 权限管理 / 运行日志：独立 Activity（系统返回栈）。 */
    val onOpenPermissions: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onEnableAccessibility: () -> Unit,
    val onEnableNotificationListener: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onAcceptPair: (PairRequest) -> Unit,
    val onRejectPair: (PairRequest) -> Unit,
    val onDeleteDevice: (TrustedDevice) -> Unit,
    val onAddCommand: () -> Unit,
    val onEditCommand: (Int) -> Unit,
    val onDeleteCommand: (Int) -> Unit,
    /** 保存命令；返回 false = 校验失败，弹窗不关。 */
    val onSaveCommand: (Int?, String, String) -> Boolean,
    val onRefreshPlugins: () -> Unit,
    val onInstallPlugin: () -> Unit,
    /** 插件市场（独立 Activity，搜索/分类/一键装）。 */
    val onOpenMarket: () -> Unit,
    /** 某个已装插件有新版：弹更新日志（宿主版本不够时按钮灰显）。 */
    val onOpenUpdate: (com.hydro.link.server.market.PluginUpdate) -> Unit,
    val onPluginOpenWebUi: (PluginRow) -> Unit,
    val onPluginUninstall: (PluginRow) -> Unit,
    val onPluginDetails: (PluginRow) -> Unit,
    /** 设置授权器：none / root / shizuku。 */
    val onSetAuthorizer: (String) -> Unit,
    /** Shizuku 去授权（请求权限 + 绑定用户服务）。 */
    val onAuthorizeShizuku: () -> Unit,
    /** 仅刷新授权状态（不请求权限）。 */
    val onRefreshAuthorizerStatus: () -> Unit,
    val onToggleBoot: (Boolean) -> Unit,
    val onToggleGlass: (Boolean) -> Unit,
    val onToggleMiuix: (Boolean) -> Unit,
    val onRootConfirmDismiss: () -> Unit,
    val onRootConfirmAccept: () -> Unit,
)
