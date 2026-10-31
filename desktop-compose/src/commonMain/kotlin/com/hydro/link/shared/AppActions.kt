package com.hydro.link.shared

import com.hydro.link.shared.model.Command
import com.hydro.link.shared.model.PairRequest
import com.hydro.link.shared.market.MarketCategory
import com.hydro.link.shared.market.MarketEntry
import com.hydro.link.shared.model.PluginRow
import com.hydro.link.shared.ui.HomeTab

/**
 * 界面动作的出口（与安卓端 `MiuixActions` 同构）。
 *
 * 所有会改状态/调后端的事都从这里走：界面不直接持有数据层，两个引擎也共用同一份动作，
 * 所以同一件事在 miuix 与 MD3 下的行为必然一致（之前安卓端出现过「miuix 下弹 MD 框」那类
 * 漏网之鱼，就是因为界面自己去拿数据层）。
 */
class AppActions(
    val onRefresh: () -> Unit,
    val onOpenLogs: () -> Unit,
    /** 界面内跳到别的 Tab（设备数量卡 → 设备页、插件数量卡 → 插件页）。 */
    val onOpenTab: (HomeTab) -> Unit,

    // 设备
    val onAcceptPair: (PairRequest) -> Unit,
    val onRejectPair: (PairRequest) -> Unit,
    val onPromptPair: (PairRequest) -> Unit = {},
    val onCancelPairPrompt: () -> Unit = {},
    val onDeleteDevice: (String) -> Unit,

    // 命令
    val onAddCommand: () -> Unit,
    val onEditCommand: (Int) -> Unit,
    val onDeleteCommand: (Int) -> Unit,
    /** 保存命令；返回 false = 校验失败（弹窗据此不关）。 */
    val onSaveCommand: (Int?, String, String) -> Boolean,

    // 插件
    val onRefreshPlugins: () -> Unit,
    val onInstallPlugin: () -> Unit,
    /** 打开市场 Tab（侧栏直接进，市场是独立 Tab）。 */
    val onOpenMarket: () -> Unit,
    val onMarketSearch: (String) -> Unit,
    val onMarketCategory: (MarketCategory) -> Unit,
    val onMarketRefresh: () -> Unit,
    val onMarketInstall: (MarketEntry) -> Unit,
    val onCancelMarketInstall: () -> Unit,
    val onConfirmMarketInstall: (MarketEntry) -> Unit,
    val onPluginOpenWebUi: (PluginRow) -> Unit,
    /** 卸载要**二次确认**：这里只是打开确认框，真正删除在 [onUninstallConfirmed]。 */
    val onRequestUninstall: (PluginRow) -> Unit,
    val onCancelUninstall: () -> Unit,
    val onUninstallConfirmed: () -> Unit,
    /** 详情页：已装插件 / 市场条目。 */
    val onPluginDetails: (PluginRow) -> Unit,
    val onMarketDetails: (MarketEntry) -> Unit,
    val onDetailBack: () -> Unit,

    // 设置
    /** 端口输入框：只在**合法端口**时回调（1..65535），非法值界面自己拦下不提交。 */
    val onPortChange: (Int) -> Unit,
    val onAcceptAllChange: (Boolean) -> Unit,
    val onToggleTray: (Boolean) -> Unit,
    val onMarketSourceChange: (String) -> Unit,
    val onToggleMiuix: (Boolean) -> Unit,
    /** 检查应用更新。 */
    val onCheckAppUpdate: () -> Unit,
    val onCancelAppUpdate: () -> Unit,
    val onConfirmAppUpdate: (com.hydro.link.shared.update.AppUpdateInfo) -> Unit,
)

/** 命令弹窗的编辑目标（[editIndex] == null 表示新建）。与安卓端同名同义。 */
data class CmdEdit(val editIndex: Int?, val name: String = "", val command: String = "")
