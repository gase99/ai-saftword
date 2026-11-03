package com.aicode.glass.terminalview

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import com.aicode.glass.terminal.TerminalLine

@Composable
fun TerminalView(lines: List<TerminalLine>, modifier: Modifier = Modifier, sessionId: String = "") {
    val listState = rememberLazyListState(sessionId)
    val follow by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull(); last == null || last.index >= lines.lastIndex - 1 } }
    LaunchedEffect(lines.lastOrNull()?.id, sessionId) { if (lines.isNotEmpty() && follow) listState.scrollToItem(lines.lastIndex) }
    val horizontal = rememberScrollState(sessionId.hashCode())
    SelectionContainer(modifier.background(Color(0xFF08121E))) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().horizontalScroll(horizontal).testTag("terminal-output"), contentPadding = PaddingValues(14.dp)) {
            items(lines, key = { it.id }) { line ->
                val message = line.message
                val text = when {
                    message?.code == "terminal_welcome" -> stringResource(R.string.terminal_welcome)
                    message?.code == "command_cancelled" -> stringResource(R.string.terminal_cancelled)
                    message?.code == "command_too_long" -> stringResource(R.string.terminal_too_long)
                    line.text == "HELP" -> stringResource(R.string.terminal_help_text)
                    line.text == "UNSUPPORTED_RUNTIME" -> stringResource(R.string.terminal_unsupported)
                    line.text.startsWith("UNKNOWN_COMMAND: ") -> stringResource(R.string.terminal_unknown, line.text.substringAfter(": "))
                    else -> line.text
                }
                val label = stringResource(if(line.kind == TerminalLine.Kind.ERROR) R.string.terminal_stderr else if(line.kind == TerminalLine.Kind.INPUT) R.string.terminal_input else R.string.terminal_stdout)
                // Shell text, paths and flags stay LTR; surrounding controls and help use the app locale.
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(text, modifier = Modifier.semantics { contentDescription = label + ": " + text }, softWrap = false,
                        color = when(line.kind) { TerminalLine.Kind.INPUT -> Color(0xFFBFC5FF); TerminalLine.Kind.ERROR -> Color(0xFFFFADB4); TerminalLine.Kind.SUCCESS -> Color(0xFF7DE2D1); else -> Color(0xFFD2DEEB) },
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}
