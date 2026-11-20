package com.oms.client.ui.markdown

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeFence(val language: String, val code: String, val isClosed: Boolean) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class MathBlock(val latex: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class BulletList(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class Divider(val raw: String = "---") : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

data class IncrementalMarkdownDocument(
    val blocks: List<MdBlock>,
    val frozenBlockCount: Int
)

/**
 * Pure-Kotlin 120fps Block-Incremental Markdown Parser.
 * Splits streaming Markdown into discrete semantic blocks so completed blocks stay frozen (`key` stable)
 * while only the trailing active block recomposes during SSE streaming.
 */
object MarkdownBlockParser {
    private val HEADING_RE = Regex("""^(#{1,6})\s+(.+)$""")
    private val ORDERED_ITEM_RE = Regex("""^\d+[.)]\s+(.+)$""")
    private val BULLET_ITEM_RE = Regex("""^[-*+•]\s+(.+)$""")
    private val DIVIDER_RE = Regex("""^(?:---+|\*\*\*+|___+)\s*$""")
    private val TABLE_SEP_RE = Regex("""^\|?\s*:?-{2,}:?(?:\s*\|\s*:?-{2,}:?)+\s*\|?\s*$""")

    fun parse(markdown: String, isStreaming: Boolean = false): IncrementalMarkdownDocument {
        if (markdown.isBlank()) {
            return IncrementalMarkdownDocument(emptyList(), 0)
        }
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block ```lang
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = ArrayList<String>()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) {
                        closed = true
                        i++
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeFence(lang, codeLines.joinToString("\n"), closed))
                continue
            }

            // 2. Display math block $$ ... $$
            if (trimmed.startsWith("$$")) {
                if (trimmed.length > 4 && trimmed.endsWith("$$")) {
                    blocks.add(MdBlock.MathBlock(trimmed.removeSurrounding("$$").trim()))
                    i++
                    continue
                }
                val mathLines = ArrayList<String>()
                val firstRem = trimmed.removePrefix("$$").trim()
                if (firstRem.isNotEmpty()) mathLines.add(firstRem)
                i++
                while (i < lines.size) {
                    val cur = lines[i].trim()
                    if (cur.endsWith("$$")) {
                        val rem = cur.removeSuffix("$$").trim()
                        if (rem.isNotEmpty()) mathLines.add(rem)
                        i++
                        break
                    }
                    mathLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.MathBlock(mathLines.joinToString("\n")))
                continue
            }

            // 3. Horizontal rule
            if (DIVIDER_RE.matches(trimmed)) {
                blocks.add(MdBlock.Divider())
                i++
                continue
            }

            // 4. Heading
            val headingMatch = HEADING_RE.matchEntire(trimmed)
            if (headingMatch != null) {
                blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim()))
                i++
                continue
            }

            // 5. GFM Table
            if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP_RE.matches(lines[i + 1].trim())) {
                val headers = splitTableRow(trimmed)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                    rows.add(splitTableRow(lines[i].trim()))
                    i++
                }
                blocks.add(MdBlock.Table(headers, rows))
                continue
            }

            // 6. Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
                continue
            }

            // 7. Ordered / Bullet List
            val isBullet = BULLET_ITEM_RE.matches(trimmed)
            val isOrdered = !isBullet && ORDERED_ITEM_RE.matches(trimmed)
            if (isBullet || isOrdered) {
                val items = ArrayList<String>()
                while (i < lines.size) {
                    val curTrim = lines[i].trim()
                    val m = if (isOrdered) ORDERED_ITEM_RE.matchEntire(curTrim) else BULLET_ITEM_RE.matchEntire(curTrim)
                    if (m != null) {
                        items.add(m.groupValues[1].trim())
                        i++
                    } else if (lines[i].startsWith("  ") && items.isNotEmpty() && curTrim.isNotEmpty()) {
                        items[items.lastIndex] = items.last() + "\n" + curTrim
                        i++
                    } else {
                        break
                    }
                }
                blocks.add(MdBlock.BulletList(ordered = isOrdered, items = items))
                continue
            }

            // 8. Paragraph
            val paraLines = ArrayList<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    curTrim.startsWith("```") ||
                    curTrim.startsWith("$$") ||
                    curTrim.startsWith("#") ||
                    curTrim.startsWith(">") ||
                    DIVIDER_RE.matches(curTrim) ||
                    BULLET_ITEM_RE.matches(curTrim) ||
                    ORDERED_ITEM_RE.matches(curTrim) ||
                    (curTrim.contains('|') && i + 1 < lines.size && TABLE_SEP_RE.matches(lines[i + 1].trim()))
                ) {
                    break
                }
                paraLines.add(curTrim)
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
            } else {
                i++
            }
        }

        val frozen = if (isStreaming && blocks.isNotEmpty()) (blocks.size - 1).coerceAtLeast(0) else blocks.size
        return IncrementalMarkdownDocument(blocks, frozen)
    }

    private fun splitTableRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}
