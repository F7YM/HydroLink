package com.hydro.link.server.ui

import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

/**
 * Liquid Glass Tab 承载层（Kyant0/AndroidLiquidGlass 的 catalog 组件 LiquidBottomTabs）：
 *
 * - 现有 View 页面 [page] 经 AndroidView 进入组合，用 layerBackdrop 录成背景层；
 *   背景层开头 drawRect 页面底色，胶囊下没内容时玻璃也不会透出黑底
 * - glassOn=true 时底部渲染胶囊玻璃条 LiquidBottomTabs（拖拽切换 + 按压反馈 +
 *   vibrancy/blur/lens 折射全在组件内部），真实 BottomNavigationView 由
 *   applyGlassMode 隐藏，切换回调走同一套 selectedItemId 监听逻辑
 * - glassOn=false（默认）时胶囊不渲染，壳里的老底栏照常工作——开关默认关是因为
 *   FAQ 有 RenderThread SIGSEGV 案例，必须能一键退回
 */
@Composable
fun GlassTabHost(
    glassOn: Boolean,
    page: View,
    selectedIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    tabIcons: List<Drawable>,
    tabLabels: List<CharSequence>,
) {
    val context = LocalContext.current
    val background = Color(ContextCompat.getColor(context, R.color.md_theme_background))
    val primary = Color(ContextCompat.getColor(context, R.color.md_theme_primary))
    val onSurfaceVariant = Color(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
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
            AndroidView(factory = { page }, modifier = Modifier.fillMaxSize())
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
