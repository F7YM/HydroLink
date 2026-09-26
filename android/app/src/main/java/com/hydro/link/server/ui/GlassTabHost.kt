package com.hydro.link.server.ui

import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hydro.link.server.R
import com.hydro.link.server.ui.liquid.LiquidBarItem
import com.hydro.link.server.ui.liquid.LiquidFloatingBar
import com.hydro.link.server.ui.material.HydroMaterialTheme
import com.hydro.link.server.ui.material.MaterialApp
import com.hydro.link.server.ui.miuix.MiuixActions
import com.hydro.link.server.ui.miuix.MiuixApp
import com.hydro.link.server.ui.miuix.MiuixModel
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val TAB_ICON_RES = listOf(
    R.drawable.ic_nav_status,
    R.drawable.ic_nav_devices,
    R.drawable.ic_nav_commands,
    R.drawable.ic_nav_plugins,
    R.drawable.ic_settings,
)

/**
 * 界面承载层：两个引擎都进 Compose（miuix → [MiuixApp]；MD3 → [MaterialApp]）。
 *
 * 「启用悬浮底栏」= 与 InstallerX 同源的 iOS 液态玻璃底栏（[LiquidFloatingBar]，
 * 移植自 Apache-2.0 的 miuix 示例 IosLiquidGlassNavigationBar）：
 * - **整页内容**录进 backdrop（[rememberLayerBackdrop] + `layerBackdrop`），底栏画在
 *   采集层之外压在上面，才能折射到真正的内容；
 * - API 33+ 走 miuix blur（vibrancy + lens 折射 + 高光 + 可拖动胶囊），低版本退化为纯色；
 * - 内容不为其让位（流到底），页面尾部由各引擎自己留一段空白。
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
                miuix = true, glassOn = glassOn, model = model, actions = actions,
                selectedIndex = selectedIndex, onTabSelected = onTabSelected, tabLabels = tabLabels,
            )
        }
    } else {
        HydroMaterialTheme {
            GlassTabHostContent(
                miuix = false, glassOn = glassOn, model = model, actions = actions,
                selectedIndex = selectedIndex, onTabSelected = onTabSelected, tabLabels = tabLabels,
            )
        }
    }
}

@Composable
private fun GlassTabHostContent(
    miuix: Boolean,
    glassOn: Boolean,
    model: MiuixModel,
    actions: MiuixActions,
    selectedIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    tabLabels: List<CharSequence>,
) {
    val context = LocalContext.current
    val background = if (miuix) {
        MiuixTheme.colorScheme.surfaceContainer
    } else {
        Color(ContextCompat.getColor(context, R.color.md_theme_surfaceContainer))
    }
    // 只有 API 33+（miuix-blur 依赖 RuntimeShader）才建背景层，低版本退化为纯色胶囊
    val backdrop = if (glassOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLayerBackdrop {
            drawRect(background)
            drawContent()
        }
    } else {
        null
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层由各引擎包住自己的**页面内容**（不含顶栏，避免自引用）；底栏是这里的
        // 兄弟节点，压在内容上，才能折射到底下的真实内容
        if (miuix) {
            MiuixApp(
                model = model,
                actions = actions,
                selectedIndex = selectedIndex,
                tabLabels = tabLabels,
                capsuleOn = glassOn,
                onTabSelected = onTabSelected,
                backdrop = backdrop,
            )
        } else {
            MaterialApp(
                model = model,
                actions = actions,
                selectedIndex = selectedIndex,
                tabLabels = tabLabels,
                floatingBar = glassOn,
                onTabSelected = onTabSelected,
                backdrop = backdrop,
            )
        }

        if (glassOn) {
            // 授权器二级页整页覆盖时胶囊淡出（与 ShortNavigationBar 同款处理）
            androidx.compose.animation.AnimatedVisibility(
                visible = !model.authorizerPage,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
            LiquidFloatingBar(
                items = tabLabels.mapIndexed { index, label ->
                    LiquidBarItem(
                        TAB_ICON_RES[index.coerceIn(TAB_ICON_RES.indices)],
                        label.toString(),
                    )
                },
                selectedIndex = selectedIndex().coerceIn(0, 4),
                onItemClick = onTabSelected,
                backdrop = backdrop,
                mode = if (!miuix) {
                    if (backdrop != null) com.hydro.link.server.ui.liquid.LiquidBarMode.Blur
                    else com.hydro.link.server.ui.liquid.LiquidBarMode.None
                } else {
                    if (backdrop != null) com.hydro.link.server.ui.liquid.LiquidBarMode.LiquidGlass
                    else com.hydro.link.server.ui.liquid.LiquidBarMode.None
                },
                indicatorColor = if (miuix) MiuixTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary,
                contentColor = if (miuix) MiuixTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface,
                surfaceContainerColor = if (miuix) MiuixTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                // 对齐交给外层 AnimatedVisibility（BottomCenter）
                modifier = Modifier,
            )
            }
        }
    }
}
