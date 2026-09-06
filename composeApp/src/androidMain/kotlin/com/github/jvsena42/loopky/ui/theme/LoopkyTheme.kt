package com.github.jvsena42.loopky.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.github.jvsena42.loopky.domain.model.AppTheme

private val LocalLoopkyColors: ProvidableCompositionLocal<LoopkyColors> =
    staticCompositionLocalOf { LoopkyLightColors }

object LoopkyTheme {
    val colors: LoopkyColors
        @Composable get() = LocalLoopkyColors.current
}

/**
 * Whether [this] means dark *right now*, asking the system only when the user has not decided.
 *
 * A `@Composable` on purpose: `isSystemInDarkTheme()` reads the configuration, so a user on
 * [AppTheme.System] follows the device flipping at sunset without anything having to notice.
 */
@Composable
fun AppTheme.isDark(): Boolean = when (this) {
    AppTheme.System -> isSystemInDarkTheme()
    AppTheme.Light -> false
    AppTheme.Dark -> true
}

/**
 * Loopky's own palette **and** Material's, from one decision.
 *
 * Both are provided because the app draws in both vocabularies: brand surfaces come from
 * [LoopkyTheme.colors], while every Material component reached for under the native-first rule —
 * `Scaffold`, `ShortNavigationBar`, `ExposedDropdownMenuBox`, an `AlertDialog` — takes its own
 * from the `MaterialTheme` scheme. Setting only one of them leaves a dropdown menu or a dialog
 * drawing light chrome over a dark screen, which is the same split that made iOS Settings
 * unreadable before the palette existed.
 */
@Composable
fun LoopkyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) LoopkyDarkColors else LoopkyLightColors
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.foregroundOnAccent,
            background = colors.surfacePrimary,
            onBackground = colors.foregroundPrimary,
            surface = colors.surfacePrimary,
            onSurface = colors.foregroundPrimary,
            // The container a menu, a dialog or a dropdown draws itself on. Left at Material's own
            // near-black it sits *behind* the app's plum surfaces rather than above them.
            surfaceContainer = colors.surfaceCard,
            surfaceContainerHigh = colors.surfaceCard,
            outline = colors.foregroundMuted,
            outlineVariant = colors.borderSubtle,
            error = colors.danger,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.foregroundOnAccent,
            background = colors.surfacePrimary,
            onBackground = colors.foregroundPrimary,
            surface = colors.surfacePrimary,
            onSurface = colors.foregroundPrimary,
            error = colors.danger,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalLoopkyColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
