package com.github.jvsena42.echo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Minimal token snapshot taken from the Pencil design (`design/main-design.pen`). Once we
 * codegen from the `.pen` variables these should be removed in favour of generated constants.
 */
@Immutable
data class EchoColors(
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceCard: Color,
    val accentPrimary: Color,
    val accentPrimarySoft: Color,
    val accentSecondary: Color,
    val accentSecondarySoft: Color,
    val foregroundPrimary: Color,
    val foregroundSecondary: Color,
    val foregroundMuted: Color,
    val foregroundOnAccent: Color,
    val navBarBackground: Color,
    val navBarInactive: Color,
    val borderSubtle: Color,
    val srsGood: Color,
    val srsAgain: Color,
    val srsHard: Color,
    val srsEasy: Color,
    val danger: Color,
    val dangerSoft: Color,
    // Shadow tints — accent "glow" and neutral elevation shadows by depth.
    val shadowAccent: Color,
    val shadowElevationLow: Color,
    val shadowElevationMedium: Color,
    val shadowElevationHigh: Color,
    val shadowElevationXHigh: Color,
)

val EchoLightColors = EchoColors(
    surfacePrimary = Color(0xFFFFFBF5),
    surfaceSecondary = Color(0xFFFFF4EA),
    surfaceCard = Color(0xFFFFFFFF),
    accentPrimary = Color(0xFFFF5C00),
    accentPrimarySoft = Color(0xFFFFE8D6),
    accentSecondary = Color(0xFF7A4CFF),
    accentSecondarySoft = Color(0xFFE9E0FF),
    foregroundPrimary = Color(0xFF1B1B1F),
    foregroundSecondary = Color(0xFF5A5A66),
    foregroundMuted = Color(0xFF8B8B99),
    foregroundOnAccent = Color(0xFFFFFFFF),
    navBarBackground = Color(0xFF1A1326),
    navBarInactive = Color(0xFF9A93A3),
    borderSubtle = Color(0xFFF0E6D9),
    srsGood = Color(0xFF21C97A),
    srsAgain = Color(0xFFFF4E64),
    // Amber, not orange: #FF8A1F sat right next to accentPrimary #FF5C00, so "Hard"
    // read as the brand action colour.
    srsHard = Color(0xFFF5A524),
    srsEasy = Color(0xFF3B82F6),
    danger = Color(0xFFD92C2C),
    dangerSoft = Color(0x14FF4E64),
    shadowAccent = Color(0x33FF5C00),
    shadowElevationLow = Color(0x0D1A1326),
    shadowElevationMedium = Color(0x121A1326),
    shadowElevationHigh = Color(0x141A1326),
    shadowElevationXHigh = Color(0x261A1326),
)
