package com.hydro.link.server.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb
import com.hydro.link.server.R
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 系统状态栏/导航栏配色跟随**当前引擎的真实主题色**。
 *
 * 之前 MD3 直接用 `R.color.md_theme_surfaceContainer`，但 MD3 引擎的配色是用
 * materialkolor 从种子色现算的（Tonal Spot + Expressive 2025），两者不是一个值，
 * 所以状态栏和页面顶部颜色对不上。这里按同一套参数现算，保证一致。
 */
object SystemBars {
    fun apply(activity: Activity, miuix: Boolean) {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val color: Int = if (miuix) {
            if (night) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
        } else {
            val seed = androidx.compose.ui.graphics.Color(
                ContextCompat.getColor(activity, R.color.md_theme_primary)
            )
            val scheme = dynamicColorScheme(
                seedColor = seed,
                isDark = night,
                style = PaletteStyle.TonalSpot,
                contrastLevel = 0.0,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
            )
            scheme.surfaceContainer.toArgb()
        }
        activity.window.statusBarColor = color
        activity.window.navigationBarColor = color
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
