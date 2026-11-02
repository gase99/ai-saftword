package com.aicode.glass.terminalview

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

@Composable
fun TerminalView(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Color(0xFFB9F6DA), fontFamily = FontFamily.Monospace)
}
