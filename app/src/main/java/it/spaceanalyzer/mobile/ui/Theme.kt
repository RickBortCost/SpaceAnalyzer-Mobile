package it.spaceanalyzer.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Space Fieldbus Analyzer colour standard, taken from the desktop app
 * (source/static/corporate.css and source/static/trend.js of SpaceFieldbusAnalyzer_Free).
 * This is the only place in the app that defines colours; res/values/colors.xml mirrors the two
 * values the launcher icon and the system bars need.
 */
object SpaceBrand {
    val primary = Color(0xFF006EFF)
    val primarySoft = Color(0xFFCCE2FF)
    val primaryDeep = Color(0xFF003B88)
    val orange = Color(0xFFF58C3A)
    val teal = Color(0xFF0AAF96)
    val disabled = Color(0xFFCCC3BE)
    val error = Color(0xFFE53C14)
    val errorSoft = Color(0xFFFAB7A7)
}

/** Colours that are not part of the Material scheme and change with the theme. */
class SpaceColors(val series: List<Color>, val plotText: Color, val plotGrid: Color,
    val recording: Color, val warning: Color)

private val LightColors = SpaceColors(
    series = listOf("006EFF", "F58C3A", "0AAF96", "8A4FD8", "D6336C", "4E9A06", "B8860B", "505A60").map(::hex),
    plotText = Color(0xFF707E86), plotGrid = Color(0xFFE5E5E5),
    recording = SpaceBrand.error, warning = SpaceBrand.orange)

private val DarkColors = SpaceColors(
    series = listOf("5AA9FF", "FFA552", "2ED3B7", "B48CFF", "FF6B8B", "9BE15D", "FFD43B", "C9D1D9").map(::hex),
    plotText = Color(0xFFCCC3BE), plotGrid = Color(0xFF404040),
    recording = SpaceBrand.error, warning = SpaceBrand.orange)

private fun hex(rgb: String) = Color(0xFF000000L or rgb.toLong(16))

val LocalSpaceColors = staticCompositionLocalOf { LightColors }

@Composable
fun SpaceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = SpaceBrand.primary, onPrimary = Color.White,
        primaryContainer = SpaceBrand.primaryDeep, onPrimaryContainer = Color.White,
        secondary = SpaceBrand.teal, onSecondary = Color.Black,
        secondaryContainer = SpaceBrand.primaryDeep, onSecondaryContainer = Color.White,
        tertiary = SpaceBrand.orange, onTertiary = Color.Black,
        background = Color(0xFF101010), onBackground = Color.White,
        surface = Color(0xFF1A1A1A), onSurface = Color.White,
        surfaceVariant = Color(0xFF292929), onSurfaceVariant = Color(0xFFCCC3BE),
        outline = Color(0xFFCCC3BE), outlineVariant = Color(0xFF404040),
        error = SpaceBrand.error, onError = Color.White, errorContainer = SpaceBrand.error, onErrorContainer = Color.White
    ) else lightColorScheme(
        primary = SpaceBrand.primary, onPrimary = Color.White,
        primaryContainer = SpaceBrand.primarySoft, onPrimaryContainer = Color.Black,
        secondary = SpaceBrand.teal, onSecondary = Color.Black,
        secondaryContainer = SpaceBrand.primarySoft, onSecondaryContainer = Color.Black,
        tertiary = SpaceBrand.orange, onTertiary = Color.Black,
        background = Color(0xFFE5E5E5), onBackground = Color.Black,
        surface = Color.White, onSurface = Color.Black,
        surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF707E86),
        outline = Color(0xFF707E86), outlineVariant = Color(0xFFE5E5E5),
        error = SpaceBrand.error, onError = Color.White, errorContainer = SpaceBrand.errorSoft, onErrorContainer = Color.Black
    )
    CompositionLocalProvider(LocalSpaceColors provides if (dark) DarkColors else LightColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
