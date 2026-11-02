package com.aicode.glass.ui.theme

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

val GlassInk = Color(0xFF080E1D)
val GlassMint = Color(0xFF7DE2D1)
val GlassLavender = Color(0xFFB6BDFF)

@Composable
fun AiCodeTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkColorScheme(
        primary = GlassMint, secondary = GlassLavender, tertiary = Color(0xFFF3C88B),
        background = GlassInk, surface = Color(0xFF111D32), surfaceVariant = Color(0xFF22324B),
        onPrimary = Color(0xFF073A34), onBackground = Color(0xFFEDF3FF), onSurface = Color(0xFFEDF3FF),
        onSurfaceVariant = Color(0xFFADBDD5), outline = Color(0xFF536780), error = Color(0xFFFFADB4),
        primaryContainer = Color(0xFF174B49), onPrimaryContainer = Color(0xFFC4FFF2),
        secondaryContainer = Color(0xFF343B62), onSecondaryContainer = Color(0xFFE1E2FF)
    ) else lightColorScheme(
        primary = Color(0xFF006B5E), secondary = Color(0xFF5359A8), tertiary = Color(0xFF81551B),
        background = Color(0xFFF1F6FC), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE2EAF4),
        onPrimary = Color.White, onBackground = Color(0xFF142338), onSurface = Color(0xFF142338),
        onSurfaceVariant = Color(0xFF495D75), outline = Color(0xFF7A8DA5),
        primaryContainer = Color(0xFFCAEEE5), onPrimaryContainer = Color(0xFF005046),
        secondaryContainer = Color(0xFFE1E2FF), onSecondaryContainer = Color(0xFF343B62)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background == GlassInk
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(if (dark) listOf(Color(0xFF102338), Color(0xFF080E1D), Color(0xFF171B35)) else listOf(Color(0xFFE1F5F0), Color(0xFFF1F6FC), Color(0xFFE8E8FC)))))
        Box(Modifier.align(Alignment.TopEnd).offset(x = 90.dp, y = (-80).dp).size(310.dp)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(85.dp) else Modifier)
            .background(Brush.radialGradient(listOf(GlassMint.copy(alpha = if (dark) .20f else .30f), Color.Transparent))))
        Box(Modifier.align(Alignment.CenterStart).offset(x = (-130).dp, y = 130.dp).size(340.dp)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(90.dp) else Modifier)
            .background(Brush.radialGradient(listOf(GlassLavender.copy(alpha = .15f), Color.Transparent))))
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(24.dp), content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(Brush.verticalGradient(listOf(colors.surface.copy(alpha = .86f), colors.surface.copy(alpha = .62f))))
        .border(1.dp, Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .17f), colors.primary.copy(alpha = .06f))), shape)) { content() }
}
