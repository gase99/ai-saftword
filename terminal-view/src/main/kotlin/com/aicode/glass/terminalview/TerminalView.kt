package com.aicode.glass.terminalview

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import com.aicode.glass.terminal.TerminalLine

/** Selectable, horizontally scrollable terminal renderer with bounded line retention. */
@Composable
fun TerminalView(lines: List<TerminalLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull(); last == null || last.index >= lines.lastIndex } }
    LaunchedEffect(lines.size, lines.lastOrNull()) { if (lines.isNotEmpty() && atBottom) listState.scrollToItem(lines.lastIndex) }
    SelectionContainer(modifier.background(Color(0xFF08121E))) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("terminal-output"), contentPadding = PaddingValues(14.dp)) {
            items(lines) { line ->
                Text(line.text, modifier = Modifier.horizontalScroll(rememberScrollState()), softWrap = false,
                    color = when (line.kind) { TerminalLine.Kind.INPUT -> Color(0xFFBFC5FF); TerminalLine.Kind.ERROR -> Color(0xFFFFADB4); TerminalLine.Kind.SUCCESS -> Color(0xFF7DE2D1); else -> Color(0xFFD2DEEB) },
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
