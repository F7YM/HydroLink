package com.hydro.link.server.ui.material

import androidx.annotation.ColorRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hydro.link.server.R

/**
 * 应用调色板 → Material3 ColorScheme（与 XML 的 md_theme_* 同一份色板）。
 * MD3 界面引擎整体套这个主题，组件直接用 Material3（InstallerX 同套）。
 */
@Composable
fun HydroMaterialTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    @ColorRes fun id(name: Int): Color = Color(ContextCompat.getColor(ctx, name))
    val scheme: ColorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = id(R.color.md_theme_primary),
            onPrimary = id(R.color.md_theme_onPrimary),
            primaryContainer = id(R.color.md_theme_primaryContainer),
            onPrimaryContainer = id(R.color.md_theme_onPrimaryContainer),
            secondary = id(R.color.md_theme_secondary),
            onSecondary = id(R.color.md_theme_onSecondary),
            secondaryContainer = id(R.color.md_theme_secondaryContainer),
            onSecondaryContainer = id(R.color.md_theme_onSecondaryContainer),
            tertiary = id(R.color.md_theme_tertiary),
            onTertiary = id(R.color.md_theme_onTertiary),
            tertiaryContainer = id(R.color.md_theme_tertiaryContainer),
            onTertiaryContainer = id(R.color.md_theme_onTertiaryContainer),
            error = id(R.color.md_theme_error),
            onError = id(R.color.md_theme_onError),
            errorContainer = id(R.color.md_theme_errorContainer),
            onErrorContainer = id(R.color.md_theme_onErrorContainer),
            background = id(R.color.md_theme_background),
            onBackground = id(R.color.md_theme_onBackground),
            surface = id(R.color.md_theme_surface),
            onSurface = id(R.color.md_theme_onSurface),
            surfaceVariant = id(R.color.md_theme_surfaceVariant),
            onSurfaceVariant = id(R.color.md_theme_onSurfaceVariant),
            surfaceTint = id(R.color.md_theme_surfaceTint),
            inverseSurface = id(R.color.md_theme_inverseSurface),
            inverseOnSurface = id(R.color.md_theme_inverseOnSurface),
            inversePrimary = id(R.color.md_theme_inversePrimary),
            outline = id(R.color.md_theme_outline),
            outlineVariant = id(R.color.md_theme_outlineVariant),
            scrim = id(R.color.md_theme_scrim),
            surfaceBright = id(R.color.md_theme_surfaceBright),
            surfaceDim = id(R.color.md_theme_surfaceDim),
            surfaceContainer = id(R.color.md_theme_surfaceContainer),
            surfaceContainerHigh = id(R.color.md_theme_surfaceContainerHigh),
            surfaceContainerHighest = id(R.color.md_theme_surfaceContainerHighest),
            surfaceContainerLow = id(R.color.md_theme_surfaceContainerLow),
            surfaceContainerLowest = id(R.color.md_theme_surfaceContainerLowest),
        )
    } else {
        lightColorScheme(
            primary = id(R.color.md_theme_primary),
            onPrimary = id(R.color.md_theme_onPrimary),
            primaryContainer = id(R.color.md_theme_primaryContainer),
            onPrimaryContainer = id(R.color.md_theme_onPrimaryContainer),
            secondary = id(R.color.md_theme_secondary),
            onSecondary = id(R.color.md_theme_onSecondary),
            secondaryContainer = id(R.color.md_theme_secondaryContainer),
            onSecondaryContainer = id(R.color.md_theme_onSecondaryContainer),
            tertiary = id(R.color.md_theme_tertiary),
            onTertiary = id(R.color.md_theme_onTertiary),
            tertiaryContainer = id(R.color.md_theme_tertiaryContainer),
            onTertiaryContainer = id(R.color.md_theme_onTertiaryContainer),
            error = id(R.color.md_theme_error),
            onError = id(R.color.md_theme_onError),
            errorContainer = id(R.color.md_theme_errorContainer),
            onErrorContainer = id(R.color.md_theme_onErrorContainer),
            background = id(R.color.md_theme_background),
            onBackground = id(R.color.md_theme_onBackground),
            surface = id(R.color.md_theme_surface),
            onSurface = id(R.color.md_theme_onSurface),
            surfaceVariant = id(R.color.md_theme_surfaceVariant),
            onSurfaceVariant = id(R.color.md_theme_onSurfaceVariant),
            surfaceTint = id(R.color.md_theme_surfaceTint),
            inverseSurface = id(R.color.md_theme_inverseSurface),
            inverseOnSurface = id(R.color.md_theme_inverseOnSurface),
            inversePrimary = id(R.color.md_theme_inversePrimary),
            outline = id(R.color.md_theme_outline),
            outlineVariant = id(R.color.md_theme_outlineVariant),
            scrim = id(R.color.md_theme_scrim),
            surfaceBright = id(R.color.md_theme_surfaceBright),
            surfaceDim = id(R.color.md_theme_surfaceDim),
            surfaceContainer = id(R.color.md_theme_surfaceContainer),
            surfaceContainerHigh = id(R.color.md_theme_surfaceContainerHigh),
            surfaceContainerHighest = id(R.color.md_theme_surfaceContainerHighest),
            surfaceContainerLow = id(R.color.md_theme_surfaceContainerLow),
            surfaceContainerLowest = id(R.color.md_theme_surfaceContainerLowest),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
