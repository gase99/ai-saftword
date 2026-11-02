package com.aicode.glass.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.glass.ui.theme.GlassCard
import org.commonmark.node.*
import org.commonmark.parser.Parser

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { Parser.builder().build().parse(text) }
    SelectionContainer(modifier) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Block(document, 0) } }
}
@Composable private fun Block(node: Node, depth: Int) {
    when (node) {
        is Heading -> Text(inlineNodes(node), style = when(node.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium }, fontWeight = FontWeight.Bold)
        is Paragraph -> {
            val annotated = inlineNodes(node); val color = MaterialTheme.colorScheme.onSurface; val uriHandler = LocalUriHandler.current
            ClickableText(annotated, style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 23.sp)) { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { if (it.item.startsWith("https://") || it.item.startsWith("http://")) runCatching { uriHandler.openUri(it.item) } }
            }
        }
        is FencedCodeBlock -> CodeBlock(node.literal, node.info ?: "")
        is IndentedCodeBlock -> CodeBlock(node.literal, "")
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            var child = node.firstChild; var index = (node as? OrderedList)?.markerStartNumber ?: 1
            while (child != null) { Row(Modifier.padding(start = (depth * 8).dp)) {
                Text(if (node is BulletList) "•  " else "${index++}.  ", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) { Block(child, depth + 1) }
            }; child = child.next }
        }
        is BlockQuote -> GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) { Column(Modifier.padding(12.dp)) { Children(node, depth) } }
        is ThematicBreak -> HorizontalDivider()
        is HtmlBlock, is HtmlInline -> Text(node.literal, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        else -> Children(node, depth)
    }
}
@Composable private fun Children(node: Node, depth: Int) { var child = node.firstChild; while (child != null) { Block(child, depth); child = child.next } }
@Composable private fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp)) { Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Icon(Icons.Outlined.ContentCopy, "Copy code", Modifier.size(17.dp)) }
            }
            Text(highlightedCode(code.trimEnd(), language), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, softWrap = false)
        } }
    }
}
@Composable private fun inlineNodes(parent: Node): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary; val surface = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        fun visit(node: Node) {
            when (node) {
                is Text -> append(node.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = primary, background = surface)) { append(node.literal) }
                is SoftLineBreak -> append(" ")
                is HardLineBreak -> append("\n")
                is StrongEmphasis -> { val start = length; var child = node.firstChild; while(child != null) { visit(child); child = child.next }; addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length) }
                is Emphasis -> { val start = length; var child = node.firstChild; while(child != null) { visit(child); child = child.next }; addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length) }
                is Link -> { val start = length; var child = node.firstChild; while(child != null) { visit(child); child = child.next }; addStyle(SpanStyle(color = primary, textDecoration = TextDecoration.Underline), start, length); addStringAnnotation("URL", node.destination, start, length) }
                is Image -> append(node.title ?: node.destination ?: "image")
                else -> { var child = node.firstChild; while(child != null) { visit(child); child = child.next } }
            }
        }
        visit(parent)
    }
}
