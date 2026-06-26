package com.v2raytester.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-green palette mirroring the desktop app.
val Bg = Color(0xFF0E1512)
val Card = Color(0xFF15201B)
val Card2 = Color(0xFF101A15)
val Stroke = Color(0xFF26392F)
val Fg = Color(0xFFE7F1EB)
val Muted = Color(0xFF8FA99B)
val Green = Color(0xFF34D17A)
val GreenHov = Color(0xFF49DD8B)
val GreenDeep = Color(0xFF1E5E3C)

// latency gradient
val LatFast = Color(0xFF5BE08A)   // < 150 ms
val LatMed = Color(0xFFB6E36A)    // 150-350
val LatSlow = Color(0xFFE0B34A)   // > 350
val BadFg = Color(0xFFE06A6A)
val WarnFg = Color(0xFFE0B34A)
val TestFg = Color(0xFF8FA99B)

private val scheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF06150D),
    secondary = GreenDeep,
    background = Bg,
    onBackground = Fg,
    surface = Card,
    onSurface = Fg,
    surfaceVariant = Card2,
    onSurfaceVariant = Muted,
    outline = Stroke,
    error = BadFg,
)

@Composable
fun V2rayTesterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

fun latencyColor(ms: Int?): Color = when {
    ms == null -> LatMed
    ms < 150 -> LatFast
    ms <= 350 -> LatMed
    else -> LatSlow
}
