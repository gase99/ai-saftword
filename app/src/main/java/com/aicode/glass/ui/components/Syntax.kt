package com.aicode.glass.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val keywords = Regex("\\b(fun|val|var|class|object|interface|return|if|else|when|for|while|import|package|suspend|override|private|public|internal|data|sealed|def|print|from|as|in|is|try|catch|finally|throw|true|false|null|None|True|False|async|await|function|const|let|export|default|new|type|enum|struct|use|impl|match|void|static|final)\\b")
private val tokens = Regex("//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b")
@Composable fun highlightedCode(code: String, language: String = ""): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        append(code)
        keywords.findAll(code).forEach { addStyle(SpanStyle(color = colors.secondary), it.range.first, it.range.last + 1) }
        tokens.findAll(code).forEach { match ->
            val comment = match.value.startsWith("//") || match.value.startsWith("#") || match.value.startsWith("/*")
            addStyle(SpanStyle(color = if (comment) colors.onSurfaceVariant else colors.primary), match.range.first, match.range.last + 1)
        }
    }
}
class SyntaxTransformation(private val colors: Map<String, Color>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val annotated = buildAnnotatedString {
            append(source)
            // Bound highlighting work on large files; editing offsets are always preserved.
            val limit = minOf(source.length, 100_000); val code = source.take(limit)
            keywords.findAll(code).forEach { addStyle(SpanStyle(color = colors.getValue("keyword")), it.range.first, it.range.last + 1) }
            tokens.findAll(code).forEach { match ->
                val comment = match.value.startsWith("//") || match.value.startsWith("#") || match.value.startsWith("/*")
                addStyle(SpanStyle(color = colors.getValue(if (comment) "comment" else "string")), match.range.first, match.range.last + 1)
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
