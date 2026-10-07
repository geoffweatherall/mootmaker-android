package com.mootmaker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand tokens, mirrored from mootmaker's branding/tokens.css via the webapp's theme/tokens.ts.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color.White,
    secondary = Color(0xFF0E8F82),
    onSecondary = Color.White,
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF1E1B2E),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF1E1B2E),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceVariant = Color(0xFFF4F1EA),
    onSurfaceVariant = Color(0xFF58527A),
    outlineVariant = Color(0xFFE7E3F6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B85F0),
    onPrimary = Color(0xFF17152A),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF17152A),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF17152A),
    onBackground = Color(0xFFF1EFFA),
    surface = Color(0xFF17152A),
    onSurface = Color(0xFFF1EFFA),
    surfaceContainer = Color(0xFF201D38),
    surfaceContainerLow = Color(0xFF201D38),
    surfaceVariant = Color(0xFF201D38),
    onSurfaceVariant = Color(0xFFB6B0D8),
    outlineVariant = Color(0xFF34305A),
)

// The categorical room palette, in RoomColor order. Colour is never the only cue: the room's name
// is always shown beside it.
private val RoomPaletteLight = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF4A3AA7, 0xFFE34948)
private val RoomPaletteDark = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767)

fun roomColor(slot: Int, dark: Boolean): Color {
    val palette = if (dark) RoomPaletteDark else RoomPaletteLight
    return Color(palette[Math.floorMod(slot, palette.size)])
}

@Composable
fun MootmakerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // No dynamic (wallpaper) colour: the app keeps mootmaker's own brand colours.
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
