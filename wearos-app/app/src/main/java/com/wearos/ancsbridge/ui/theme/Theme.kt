package com.wearos.ancsbridge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** iOS system colors, so the watch app feels at home next to an iPhone. */
object WearBridgeColors {
    val Accent = Color(0xFF0A84FF)   // systemBlue (dark)
    val Online = Color(0xFF30D158)   // systemGreen (dark)
    val Alert = Color(0xFFFF9F0A)    // systemOrange (dark)
    val Danger = Color(0xFFFF453A)   // systemRed (dark)
    val Music = Color(0xFFFF375F)    // systemPink (dark), Music app tint
}

private val scheme = ColorScheme(
    primary = WearBridgeColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A3A6B),
    onPrimaryContainer = Color(0xFFD6E8FF),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF1C1C1E),     // iOS secondarySystemBackground (dark)
    surfaceContainerHigh = Color(0xFF2C2C2E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF98989F),
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun AncsBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
