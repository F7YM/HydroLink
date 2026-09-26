package com.hydro.link.server.ui

import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hydro.link.server.R
import com.hydro.link.server.ui.material.MaterialApp
import com.hydro.link.server.ui.miuix.MiuixActions
import com.hydro.link.server.ui.miuix.MiuixApp
import com.hydro.link.server.ui.miuix.MiuixModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 底部 Tab 承载层（Liquid Glass 的 catalog 组件 LiquidBottomTabs）：
 *
 * - 页面主体进 Compose 组合：miuix 引擎 → [MiuixApp]，MD3 引擎 → [MaterialApp]
 *   （两者都是真 Compose 组件，MD3 不再用 XML View 拼）；内容用 layerBackdrop 录成
 *   背景层，背景层开头 drawRect 页面底色，胶囊下没内容时玻璃也不会透出黑底
 * - 底栏：「启用悬浮底栏」开（[glassOn]）→ 悬浮胶囊（拖拽切换 + 折射）；否则各引擎
 *   的 Scaffold 挂自己的 NavigationBar；真实 BottomNavigationView 始终隐藏
 * - Liquid Glass 默认关是因为 FAQ 有 RenderThread SIGSEGV 案例，必须能一键退回
 */
@Composable
fun GlassTabHost(
    glassOn: Boolean,
    miuixOn: Boolean,
    model: MiuixModel,
    actions: MiuixActions,
    selectedIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    tabIcons: List<Drawable>,
    tabLabels: List<CharSequence>,
) {
    if (miuixOn) {
        MiuixTheme(remember { ThemeController(ColorSchemeMode.System) }) {
            GlassTabHostContent(
                miuix = true,
                glassOn = glassOn,
                model = model,
                actions = actions,
                selectedIndex = selectedIndex,
                onTabSelected = onTabSelected,
                tabIcons = tabIcons,
                tabLabels = tabLabels,
            )
        }
    } else {
        GlassTabHostContent(
            miuix = false,
            glassOn = glassOn,
            model = model,
            actions = actions,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected,
            tabIcons = tabIcons,
            tabLabels = tabLabels,
        )
    }
}

@Composable
private fun GlassTabHostContent(
    miuix: Boolean,
    glassOn: Boolean,
    model: MiuixModel,
    actions: MiuixActions,
    selectedIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    tabIcons: List<Drawable>,
    tabLabels: List<CharSequence>,
) {
    val context = LocalContext.current
    // miuix 模式页面底色用 surface（暗色下 background==surfaceContainer==#242424，
    // 用 background 会让默认卡片整片隐形）；MD3 模式仍是应用主题色
    val background = if (miuix) MiuixTheme.colorScheme.surface
    else Color(ContextCompat.getColor(context, R.color.md_theme_background))
    // 配色：miuix 开时取 MiuixTheme.colorScheme（须在 MiuixTheme 组合内读），关时走应用主题色
    val primary = if (miuix) MiuixTheme.colorScheme.primary
    else Color(ContextCompat.getColor(context, R.color.md_theme_primary))
    val onSurfaceVariant = if (miuix) MiuixTheme.colorScheme.onSurfaceContainer
    else Color(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            if (miuix) {
                MiuixApp(
                    model = model,
                    actions = actions,
                    selectedIndex = selectedIndex,
                    tabLabels = tabLabels,
                    capsuleOn = glassOn,
                    onTabSelected = onTabSelected,
                )
            } else {
                MaterialApp(
                    model = model,
                    actions = actions,
                    selectedIndex = selectedIndex,
                    tabLabels = tabLabels,
                    floatingBar = glassOn,
                    onTabSelected = onTabSelected,
                )
            }
        }

        if (glassOn) {
            LiquidBottomTabs(
                selectedTabIndex = selectedIndex,
                onTabSelected = onTabSelected,
                backdrop = backdrop,
                tabsCount = tabIcons.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                tabIcons.forEachIndexed { index, icon ->
                    val tint = if (selectedIndex() == index) primary else onSurfaceVariant
                    LiquidBottomTab(onClick = { onTabSelected(index) }) {
                        TabIcon(icon, tint)
                        BasicText(
                            text = tabLabels[index].toString(),
                            style = TextStyle(fontSize = 11.sp, color = tint),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(icon: Drawable, tint: Color) {
    // 菜单 drawable 与底栏共享实例：newDrawable 起一份干净的再染色，两边互不串色
    val d = remember(icon) { icon.constantState?.newDrawable()?.mutate() ?: icon.mutate() }
    Canvas(Modifier.size(24.dp)) {
        d.setTint(tint.toArgb())
        d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        d.draw(drawContext.canvas.nativeCanvas)
    }
}
