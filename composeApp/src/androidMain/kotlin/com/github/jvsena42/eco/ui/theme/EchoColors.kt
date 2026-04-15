package com.github.jvsena42.eco.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Minimal token snapshot taken from the Pencil design (`design/main-design.pen`). Once we
 * codegen from the `.pen` variables these should be removed in favour of generated constants.
 */
@Immutable
data class EchoColors(
    val surfacePrimary: Color,
    val surfaceCard: Color,
    val accentPrimary: Color,
    val accentPrimarySoft: Color,
    val accentSecondary: Color,
    val accentSecondarySoft: Color,
    val foregroundPrimary: Color,
    val foregroundSecondary: Color,
    val foregroundMuted: Color,
    val foregroundOnAccent: Color,
    val danger: Color,
)

val EchoLightColors = EchoColors(
    surfacePrimary = Color(0xFFFFFBF5),
    surfaceCard = Color(0xFFFFFFFF),
    accentPrimary = Color(0xFFFF5C00),
    accentPrimarySoft = Color(0xFFFFE8D6),
    accentSecondary = Color(0xFF7A4CFF),
    accentSecondarySoft = Color(0xFFE9E0FF),
    foregroundPrimary = Color(0xFF1B1B1F),
    foregroundSecondary = Color(0xFF5A5A66),
    foregroundMuted = Color(0xFF8B8B99),
    foregroundOnAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFD92C2C),
)
