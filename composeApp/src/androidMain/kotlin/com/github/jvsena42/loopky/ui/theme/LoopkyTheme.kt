package com.github.jvsena42.loopky.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalLoopkyColors: ProvidableCompositionLocal<LoopkyColors> =
    staticCompositionLocalOf { LoopkyLightColors }

object LoopkyTheme {
    val colors: LoopkyColors
        @Composable get() = LocalLoopkyColors.current
}

@Composable
fun LoopkyTheme(content: @Composable () -> Unit) {
    val colors = LoopkyLightColors
    val material = lightColorScheme(
        primary = colors.accentPrimary,
        onPrimary = colors.foregroundOnAccent,
        background = colors.surfacePrimary,
        onBackground = colors.foregroundPrimary,
        surface = colors.surfacePrimary,
        onSurface = colors.foregroundPrimary,
        error = colors.danger,
        onError = Color.White,
    )
    CompositionLocalProvider(LocalLoopkyColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
