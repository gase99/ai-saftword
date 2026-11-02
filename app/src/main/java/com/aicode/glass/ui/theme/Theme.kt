package com.aicode.glass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GlassInk = Color(0xFF080E1D)
val GlassMint = Color(0xFF7DE2D1)
val GlassLavender = Color(0xFFB6BDFF)

@Composable
fun AiCodeTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkColorScheme(
        primary = GlassMint, secondary = GlassLavender, tertiary = Color(0xFFF3C88B),
        background = GlassInk, surface = Color(0xFF111D32), surfaceVariant = Color(0xFF22324B),
        onPrimary = Color(0xFF073A34), onBackground = Color(0xFFEDF3FF), onSurface = Color(0xFFEDF3FF),
        onSurfaceVariant = Color(0xFFADBDD5), outline = Color(0xFF536780)
    ) else lightColorScheme(
        primary = Color(0xFF006B5E), secondary = Color(0xFF5359A8), tertiary = Color(0xFF81551B),
        background = Color(0xFFF1F6FC), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE2EAF4),
        onPrimary = Color.White, onBackground = Color(0xFF142338), onSurface = Color(0xFF142338),
        onSurfaceVariant = Color(0xFF495D75), outline = Color(0xFF7A8DA5)
    )
    MaterialTheme(colorScheme = colors, content = content)
}
