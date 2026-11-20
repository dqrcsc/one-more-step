package com.oms.client.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oms.client.ui.theme.OmsColorPalette
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import com.oms.core.ai.GroundingSource
import kotlinx.coroutines.delay

@Composable
fun IncrementalMarkdownView(
    markdown: String,
    reasoning: String = "",
    sources: List<GroundingSource> = emptyList(),
    isStreaming: Boolean = false,
    bodyFontSize: TextUnit = 15.5.sp,
    bodyLineHeight: TextUnit = 25.5.sp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = OmsTheme.colors
    val parsed = remember(markdown, isStreaming) {
        MarkdownBlockParser.parse(markdown, isStreaming)
    }
    var reasoningExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // 1. Editorial Borderless Thinking Accordion (ChatGPT / Claude style)
        if (reasoning.isNotBlank()) {
            val activelyThinking = isStreaming && markdown.isBlank()
            val showBody = reasoningExpanded || activelyThinking
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .omsBounceClick(scaleDown = 0.96f, shape = RoundedCornerShape(10.dp)) {
                            reasoningExpanded = !reasoningExpanded
                        }
                        .padding(vertical = 3.dp, horizontal = 2.dp)
                ) {
                    Icon(
                        imageVector = OmsIcons.Lightbulb,
                        contentDescription = null,
                        tint = if (activelyThinking) palette.accent else palette.textTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (activelyThinking) "正在深度推演…" else "思考过程",
                        color = if (activelyThinking) palette.accent else palette.textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (showBody) OmsIcons.ChevronUp else OmsIcons.ChevronDown,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                AnimatedVisibility(
                    visible = showBody,
                    enter = expandVertically(tween(180)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp)
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(start = 5.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(palette.strongBorder)
                        )
                        Spacer(modifier = Modifier.width(11.dp))
                        Text(
                            text = reasoning.trim(),
                            color = palette.textSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 20.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Block-incremental Markdown Prose (frozen historical blocks + smooth animated tail)
        if (parsed.blocks.isNotEmpty()) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    parsed.blocks.forEachIndexed { idx, block ->
                        val isFrozen = idx < parsed.frozenBlockCount
                        val stableKey = if (isFrozen) {
                            "frozen_${idx}_${block.hashCode()}"
                        } else {
                            "live_$idx"
                        }
                        key(stableKey) {
                            val liveMod = if (!isFrozen && isStreaming) {
                                Modifier.animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            } else Modifier
                            Box(modifier = liveMod) {
                                RenderMdBlock(
                                    block = block,
                                    context = context,
                                    palette = palette,
                                    bodyFontSize = bodyFontSize,
                                    bodyLineHeight = bodyLineHeight
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Sleek Grounding Citation Pills
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                sources.forEachIndexed { i, src ->
                    val pillShape = RoundedCornerShape(14.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .omsBounceClick(scaleDown = 0.95f, shape = pillShape) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(src.uri))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .background(palette.subtleSurface, pillShape)
                            .border(0.6.dp, palette.hairline, pillShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = OmsIcons.Globe,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(12.5.dp)
                        )
                        Text(
                            text = "${i + 1}. ${src.title.ifBlank { src.uri }.take(24)}",
                            color = palette.textSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderMdBlock(
    block: MdBlock,
    context: Context,
    palette: OmsColorPalette,
    bodyFontSize: TextUnit,
    bodyLineHeight: TextUnit
) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 20.5.sp
                2 -> 18.sp
                3 -> 16.2.sp
                else -> 15.5.sp
            }
            Text(
                text = formatInlineMarkdown(block.text, palette),
                color = palette.textPrimary,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                lineHeight = (size.value + 7).sp,
                letterSpacing = (-0.15).sp,
                modifier = Modifier.padding(top = if (block.level <= 2) 5.dp else 2.dp)
            )
        }

        is MdBlock.CodeFence -> {
            CodeFenceBlockView(block = block, context = context, palette = palette)
        }

        is MdBlock.Table -> {
            val tableShape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clip(tableShape)
                    .border(0.6.dp, palette.hairline, tableShape)
            ) {
                Row(modifier = Modifier.background(palette.codeHeaderSurface)) {
                    block.headers.forEach { h ->
                        Text(
                            text = formatInlineMarkdown(h, palette),
                            color = palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .widthIn(min = 92.dp, max = 230.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                block.rows.forEachIndexed { rIdx, row ->
                    Row(
                        modifier = Modifier.background(
                            if (rIdx % 2 == 0) palette.cardSurface else palette.subtleSurface
                        )
                    ) {
                        block.headers.indices.forEach { cIdx ->
                            val cell = row.getOrNull(cIdx).orEmpty()
                            Text(
                                text = formatInlineMarkdown(cell, palette),
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier
                                    .widthIn(min = 92.dp, max = 230.dp)
                                    .padding(horizontal = 12.dp, vertical = 7.5.dp)
                            )
                        }
                    }
                }
            }
        }

        is MdBlock.MathBlock -> {
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(palette.subtleSurface)
                    .border(0.6.dp, palette.hairline, shape)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = prettifyLatexSymbols(block.latex),
                    color = palette.textPrimary,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 15.sp
                )
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(palette.accent.copy(alpha = 0.65f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = formatInlineMarkdown(block.text, palette),
                    color = palette.textSecondary,
                    fontSize = (bodyFontSize.value - 0.5f).sp,
                    lineHeight = (bodyLineHeight.value - 1.5f).sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MdBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.5.dp)) {
                block.items.forEachIndexed { idx, item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (block.ordered) "${idx + 1}." else "•",
                            color = palette.textTertiary,
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp)
                        )
                        Text(
                            text = formatInlineMarkdown(item, palette),
                            color = palette.textPrimary,
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
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
                    .padding(vertical = 4.dp)
                    .height(0.6.dp)
                    .background(palette.hairline)
            )
        }

        is MdBlock.Paragraph -> {
            Text(
                text = formatInlineMarkdown(block.text, palette),
                color = palette.textPrimary,
                fontSize = bodyFontSize,
                lineHeight = bodyLineHeight
            )
        }
    }
}

@Composable
private fun CodeFenceBlockView(
    block: MdBlock.CodeFence,
    context: Context,
    palette: OmsColorPalette
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600L)
            copied = false
        }
    }

    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.codeSurface)
            .border(0.6.dp, palette.hairline, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.codeHeaderSurface)
                .padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language.ifBlank { "text" }.lowercase(),
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .omsBounceClick(scaleDown = 0.92f, shape = RoundedCornerShape(8.dp)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        copied = true
                        OmsFeedbackController.show("已复制代码片段", OmsToastKind.SUCCESS, durationMs = 1500L)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = {
                        (scaleIn(spring(stiffness = 650f)) + fadeIn(tween(120))) togetherWith
                            (scaleOut(tween(100)) + fadeOut(tween(100)))
                    },
                    label = "codeCopyMorph"
                ) { done ->
                    Icon(
                        imageVector = if (done) OmsIcons.Check else OmsIcons.Copy,
                        contentDescription = "复制代码",
                        tint = if (done) palette.success else palette.textSecondary,
                        modifier = Modifier.size(13.5.dp)
                    )
                }
                Text(
                    text = if (copied) "已复制" else "复制",
                    color = if (copied) palette.success else palette.textSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = highlightCodeBasic(block.code, palette),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.5.sp
            )
        }
    }
}

private val INLINE_TOKEN_RE = Regex(
    """(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|\[[^\]]+\]\([^)]+\)|\$[^$\n]+\$)"""
)

private fun formatInlineMarkdown(text: String, palette: OmsColorPalette): AnnotatedString = buildAnnotatedString {
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
                        color = palette.accent,
                        background = palette.subtleSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                ) {
                    append(" ${tok.substring(1, tok.length - 1)} ")
                }
            }
            tok.startsWith("**") && tok.endsWith("**") -> {
                withStyle(SpanStyle(color = palette.textPrimary, fontWeight = FontWeight.SemiBold)) {
                    append(tok.substring(2, tok.length - 2))
                }
            }
            tok.startsWith("*") && tok.endsWith("*") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = palette.textSecondary)) {
                    append(tok.substring(1, tok.length - 1))
                }
            }
            tok.startsWith("$") && tok.endsWith("$") -> {
                withStyle(
                    SpanStyle(
                        color = palette.textPrimary,
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
                            color = palette.accent,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
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
    """\b(fun|val|var|class|object|interface|return|if|else|when|for|while|suspend|import|package|async|await|const|let|function|export|type|def|from|struct|impl|pub|true|false|null)\b"""
)

private fun highlightCodeBasic(code: String, palette: OmsColorPalette): AnnotatedString = buildAnnotatedString {
    val lines = code.split('\n')
    val kwColor = if (palette.isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1)
    val commentColor = palette.textTertiary
    val baseColor = palette.textPrimary

    lines.forEachIndexed { idx, line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
            withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(line) }
        } else {
            var pos = 0
            for (m in CODE_KW_RE.findAll(line)) {
                if (m.range.first > pos) {
                    withStyle(SpanStyle(color = baseColor)) {
                        append(line.substring(pos, m.range.first))
                    }
                }
                withStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.SemiBold)) {
                    append(m.value)
                }
                pos = m.range.last + 1
            }
            if (pos < line.length) {
                withStyle(SpanStyle(color = baseColor)) {
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
