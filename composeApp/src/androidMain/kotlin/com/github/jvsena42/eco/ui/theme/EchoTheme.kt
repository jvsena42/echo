package com.github.jvsena42.eco.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalEchoColors: ProvidableCompositionLocal<EchoColors> =
    staticCompositionLocalOf { EchoLightColors }

object EchoTheme {
    val colors: EchoColors
        @Composable get() = LocalEchoColors.current
}

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    val colors = EchoLightColors
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
    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
