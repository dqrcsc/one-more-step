package com.oms.client.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.core.ai.GroundingSource

@Composable
fun IncrementalMarkdownView(
    markdown: String,
    reasoning: String = "",
    sources: List<GroundingSource> = emptyList(),
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val parsed = remember(markdown, isStreaming) {
        MarkdownBlockParser.parse(markdown, isStreaming)
    }
    var reasoningExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Collapsible `💭 Thinking...` capsule
        if (reasoning.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1635))
                    .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(10.dp))
                    .clickable { reasoningExpanded = !reasoningExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isStreaming && markdown.isBlank()) "💭 正在深度思考…" else "💭 已完成深度推演",
                        color = Color(0xFFC4B5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (reasoningExpanded) "收起 ▲" else "展开思路 ▼",
                        color = Color(0xFF8B5CF6),
                        fontSize = 11.sp
                    )
                }
                AnimatedVisibility(visible = reasoningExpanded || (isStreaming && markdown.isBlank())) {
                    Text(
                        text = reasoning.trim(),
                        color = Color(0xFFDDD6FE),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // 2. Block-incremental Markdown blocks (historical blocks freeze without recomposition)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parsed.blocks.forEachIndexed { idx, block ->
                    val stableKey = if (idx < parsed.frozenBlockCount) {
                        "frozen_${idx}_${block.hashCode()}"
                    } else {
                        "live_$idx"
                    }
                    key(stableKey) {
                        RenderMdBlock(block = block, context = context)
                    }
                }
            }
        }

        // 3. Search Grounding citation pills
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sources.forEachIndexed { i, src ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F2942))
                            .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(14.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(src.uri))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🌐 [${i + 1}] ${src.title.take(22)}",
                            color = Color(0xFF7DD3FC),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderMdBlock(block: MdBlock, context: Context) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 19.sp
                2 -> 17.sp
                3 -> 15.5.sp
                else -> 14.5.sp
            }
            Text(
                text = formatInlineMarkdown(block.text),
                color = Color(0xFFF8FAFC),
                fontSize = size,
                fontWeight = FontWeight.Bold,
                lineHeight = (size.value + 6).sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        is MdBlock.CodeFence -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF090D16))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = block.language.ifBlank { "code" }.lowercase(),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "📋 复制代码",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("code", block.code))
                            Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = highlightCodeBasic(block.code),
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        is MdBlock.Table -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
            ) {
                Row(modifier = Modifier.background(Color(0xFF1E293B))) {
                    block.headers.forEach { h ->
                        Text(
                            text = formatInlineMarkdown(h),
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .widthIn(min = 88.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
                block.rows.forEachIndexed { rIdx, row ->
                    Row(
                        modifier = Modifier.background(
                            if (rIdx % 2 == 0) Color(0xFF0F172A) else Color(0xFF131C31)
                        )
                    ) {
                        block.headers.indices.forEach { cIdx ->
                            val cell = row.getOrNull(cIdx).orEmpty()
                            Text(
                                text = formatInlineMarkdown(cell),
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .widthIn(min = 88.dp, max = 220.dp)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        is MdBlock.MathBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111827))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = prettifyLatexSymbols(block.latex),
                    color = Color(0xFFFDE68A),
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp
                )
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF131C31))
                    .padding(end = 10.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .background(Color(0xFF38BDF8))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = formatInlineMarkdown(block.text),
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            }
        }

        is MdBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                block.items.forEachIndexed { idx, item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (block.ordered) "${idx + 1}. " else "• ",
                            color = Color(0xFF38BDF8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatInlineMarkdown(item),
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.5.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        is MdBlock.Divider -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF1E293B))
            )
        }

        is MdBlock.Paragraph -> {
            Text(
                text = formatInlineMarkdown(block.text),
                color = Color(0xFFE2E8F0),
                fontSize = 14.5.sp,
                lineHeight = 22.5.sp
            )
        }
    }
}

private val INLINE_TOKEN_RE = Regex(
    """(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|\[[^\]]+\]\([^)]+\)|\$[^$\n]+\$)"""
)

private fun formatInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in INLINE_TOKEN_RE.findAll(text)) {
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        val tok = m.value
        when {
            tok.startsWith("`") && tok.endsWith("`") -> {
                withStyle(
                    SpanStyle(
                        color = Color(0xFF7DD3FC),
                        background = Color(0xFF1E293B),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                ) {
                    append(" ${tok.substring(1, tok.length - 1)} ")
                }
            }
            tok.startsWith("**") && tok.endsWith("**") -> {
                withStyle(SpanStyle(color = Color(0xFFF8FAFC), fontWeight = FontWeight.Bold)) {
                    append(tok.substring(2, tok.length - 2))
                }
            }
            tok.startsWith("*") && tok.endsWith("*") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFCBD5E1))) {
                    append(tok.substring(1, tok.length - 1))
                }
            }
            tok.startsWith("$") && tok.endsWith("$") -> {
                withStyle(
                    SpanStyle(
                        color = Color(0xFFFDE68A),
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic
                    )
                ) {
                    append(prettifyLatexSymbols(tok.substring(1, tok.length - 1)))
                }
            }
            tok.startsWith("[") -> {
                val closeBr = tok.indexOf("](")
                if (closeBr > 0) {
                    val label = tok.substring(1, closeBr)
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF38BDF8),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(label)
                    }
                } else {
                    append(tok)
                }
            }
            else -> append(tok)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private val CODE_KW_RE = Regex(
    """\b(fun|val|var|class|object|interface|return|if|else|when|for|while|suspend|import|package|async|await|const|let|function|export|type|def|from|struct|impl|pub)\b"""
)

private fun highlightCodeBasic(code: String): AnnotatedString = buildAnnotatedString {
    val lines = code.split('\n')
    lines.forEachIndexed { idx, line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
            withStyle(SpanStyle(color = Color(0xFF64748B))) { append(line) }
        } else {
            var pos = 0
            for (m in CODE_KW_RE.findAll(line)) {
                if (m.range.first > pos) {
                    withStyle(SpanStyle(color = Color(0xFFE2E8F0))) {
                        append(line.substring(pos, m.range.first))
                    }
                }
                withStyle(SpanStyle(color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)) {
                    append(m.value)
                }
                pos = m.range.last + 1
            }
            if (pos < line.length) {
                withStyle(SpanStyle(color = Color(0xFFE2E8F0))) {
                    append(line.substring(pos))
                }
            }
        }
        if (idx < lines.lastIndex) append('\n')
    }
}

private fun prettifyLatexSymbols(latex: String): String = latex
    .replace("\\geq", "≥")
    .replace("\\leq", "≤")
    .replace("\\neq", "≠")
    .replace("\\approx", "≈")
    .replace("\\times", "×")
    .replace("\\rightarrow", "→")
    .replace("\\leftarrow", "←")
    .replace("\\alpha", "α")
    .replace("\\beta", "β")
    .replace("\\gamma", "γ")
    .replace("\\delta", "δ")
    .replace("\\lambda", "λ")
    .replace("\\sum", "∑")
