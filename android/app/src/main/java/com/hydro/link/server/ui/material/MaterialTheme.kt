package com.hydro.link.server.ui.material

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hydro.link.server.R
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * MD3 引擎主题：用 materialkolor 从种子色生成配色（**颜色规格 Expressive 2025 +
 * 调色板样式 Tonal Spot**，与 InstallerX 默认一致），再套 Material3 的
 * [MaterialExpressiveTheme]（Expressive 组件/动效规格）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HydroMaterialTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val seed = remember { Color(ContextCompat.getColor(context, R.color.md_theme_primary)) }
    val dark = isSystemInDarkTheme()
    val scheme = remember(seed, dark) {
        dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            style = PaletteStyle.TonalSpot,
            contrastLevel = 0.0,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }
    MaterialExpressiveTheme(colorScheme = scheme, content = content)
}
