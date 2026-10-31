package com.hydro.link.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hydro.link.shared.AppActions
import com.hydro.link.shared.AppModel
import com.hydro.link.shared.DetailTarget
import com.hydro.link.shared.market.marketFirstBase
import com.hydro.link.shared.ui.icons.IcNavPlugins
import com.hydro.link.shared.ui.md.MdShell
import com.hydro.link.shared.ui.miuix.MiuixShell
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用外壳：按当前引擎（miuix / Google Material 3）渲染同一份 [AppModel] + [AppActions]，
 * 左侧挂一列悬浮侧栏（[SideBar]）做导航——桌面上导航在左边比手机那套底部栏顺手。
 */
@Composable
fun App(model: AppModel, actions: AppActions) {
    if (model.miuix) {
        MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) {
            Host(model, actions)
        }
    } else {
        // MD3 侧要显式给配色方案：裸 MaterialTheme 用的是默认**浅色**方案，
        // 深色系统下会是一块白底（安卓侧是靠 res 里的 md_theme_* 资源适配的，
        // 桌面没有那套资源，就跟系统明暗走）。
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        ) {
            Host(model, actions)
        }
    }
}

@Composable
private fun Host(model: AppModel, actions: AppActions) {
    val tab = model.tab
    // 点侧栏 = 离开当前页：详情页开着的时候点 Tab 必须能跳走，
    // 否则详情页把 Tab 整个盖住（targetState 优先看 detail），点侧栏像没反应。
    val onTab: (HomeTab) -> Unit = {
        model.detail = null
        model.tab = it
    }
    val miuix = model.miuix
    // 注意：MD3 引擎下没有 MiuixTheme，取色必须走 MaterialTheme（写错会直接崩）
    val background = if (miuix) MiuixTheme.colorScheme.surface else MaterialTheme.colorScheme.surface
    // 侧栏面板要比页面底色更"浮"：miuix 侧 surfaceContainer 本身就深一档；
    // MD3 深色下这两个色几乎一样，所以改用 surfaceContainerHigh + 描边
    val container = if (miuix) {
        MiuixTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val panelBorder = if (miuix) null else MaterialTheme.colorScheme.outlineVariant
    val accent = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val content = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    // 侧栏宽度随窗口自适应（最大化时不再显得又窄又空），内容区的让位跟着同一个值走
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val sideBarWidth = adaptiveSideBarWidth(maxWidth)
        val contentInset = sideBarWidth + PageEdge * 2
        val detail = model.detail
        // 页面容器：详情页和六个 Tab 走同一个出口，切换时有过渡（之前是硬切，
        // 点侧栏像"闪一下"，和安卓端点 Tab 的淡入淡出不是一个手感）
        AnimatedContent(
            targetState = detail?.let { PageTarget.Detail(it) } ?: PageTarget.Tab(tab),
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "page",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                is PageTarget.Detail -> {
                    val target = page.detail
                    // 详情是**页内换页**：侧栏留着，内容区整体替换，返回键排在内容区里
                    // （内容区左侧已给侧栏留了 contentInset，所以不会和悬浮侧栏重叠）
                    DetailPage(
                        target = target,
                        miuix = miuix,
                        onBack = actions.onDetailBack,
                        onInstall = when (target) {
                            is DetailTarget.MarketEntryItem -> { entry ->
                                actions.onMarketInstall(entry)
                            }
                            else -> null
                        },
                        onUninstall = when (target) {
                            is DetailTarget.Installed -> {
                                { actions.onRequestUninstall(target.plugin) }
                            }
                            else -> null
                        },
                        contentInset = contentInset,
                        // 返回键底色跟侧栏面板同一个值：两个悬浮物颜色一致才不像拼的
                        panelColor = container,
                        fallbackIcon = IcNavPlugins,
                        marketBaseUrl = model.market.baseHint
                            ?: marketFirstBase(model.marketSource),
                    )
                }
                is PageTarget.Tab -> if (miuix) {
                    MiuixShell(model, actions, page.tab, contentInset = contentInset)
                } else {
                    MdShell(model, actions, page.tab, contentInset = contentInset)
                }
            }
        }
        // 插件市场 / 本地安装确认弹窗（双引擎：含 input 高危权限警示与 3s 倒计时）
        val installTarget = model.marketInstallTarget
        val isLocalPackage = installTarget?.url?.let {
            it.startsWith("/") || it.contains(":\\") || it.startsWith("file:")
        } == true
        InstallConfirmDialog(
            miuix = miuix,
            target = installTarget,
            marketBaseUrl = if (isLocalPackage) {
                "本地安装包"
            } else {
                model.market.baseHint ?: marketFirstBase(model.marketSource)
            },
            onCancel = actions.onCancelMarketInstall,
            onConfirm = actions.onConfirmMarketInstall,
        )
        // 卸载二次确认（两个引擎各一套弹窗组件，但文案只有这一份）
        UninstallConfirmDialog(
            miuix = miuix,
            target = model.uninstallTarget,
            onCancel = actions.onCancelUninstall,
            onConfirm = actions.onUninstallConfirmed,
        )
        // 应用版本更新弹窗（双引擎适配）
        AppUpdateDialog(
            miuix = miuix,
            target = model.appUpdateTarget,
            currentVersion = model.appVersion,
            onCancel = actions.onCancelAppUpdate,
            onConfirm = actions.onConfirmAppUpdate,
        )
        // 设备配对请求弹窗（双引擎适配：手表/客户端发起配对时弹出授权确认）
        PairConfirmDialog(
            miuix = miuix,
            target = model.pairConfirmTarget,
            onCancel = actions.onCancelPairPrompt,
            onAccept = actions.onAcceptPair,
            onReject = actions.onRejectPair,
        )
        SideBar(
            selected = tab,
            containerColor = container,
            accentColor = accent,
            contentColor = content,
            onSelect = onTab,
            width = sideBarWidth,
            modifier = Modifier.align(Alignment.CenterStart),
            border = panelBorder,
        )
    }
}

/** 外壳当前显示哪一页：某个 Tab，或者某个插件/市场条目的详情。 */
private sealed interface PageTarget {
    data class Tab(val tab: HomeTab) : PageTarget
    data class Detail(val detail: DetailTarget) : PageTarget
}
