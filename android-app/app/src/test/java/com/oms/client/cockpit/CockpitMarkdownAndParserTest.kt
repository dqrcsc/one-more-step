package com.oms.client.cockpit

import com.oms.client.ui.markdown.MarkdownBlockParser
import com.oms.client.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitMarkdownAndParserTest {

    @Test
    fun incrementalMarkdownParserFreezesCompletedBlocksAndParsesCodeTablesAndMath() {
        val streamingMd = """
            # 咫尺 v2.0 架构要点
            
            以下是对比表格：
            
            | 维度 | 协议 |
            | --- | --- |
            | 日常对话 | GPT-6.2 Sol |
            | 博物识物 | Gemini 4 Argon |
            
            $$
            \text{TTFT} \leq 800\text{ms}
            $$
            
            ```kotlin
            fun greet() {
                println("streaming...")
        """.trimIndent()

        val docStreaming = MarkdownBlockParser.parse(streamingMd, isStreaming = true)
        assertEquals(5, docStreaming.blocks.size)
        // First 4 blocks are frozen; only the trailing unclosed code fence is live
        assertEquals(4, docStreaming.frozenBlockCount)

        assertTrue(docStreaming.blocks[0] is MdBlock.Heading)
        assertTrue(docStreaming.blocks[1] is MdBlock.Paragraph)
        val tbl = docStreaming.blocks[2] as MdBlock.Table
        assertEquals(listOf("维度", "协议"), tbl.headers)
        assertEquals(2, tbl.rows.size)

        val math = docStreaming.blocks[3] as MdBlock.MathBlock
        assertTrue(math.latex.contains("800"))

        val code = docStreaming.blocks[4] as MdBlock.CodeFence
        assertEquals("kotlin", code.language)
        assertFalse(code.isClosed)

        val docDone = MarkdownBlockParser.parse("$streamingMd\n}\n```", isStreaming = false)
        assertEquals(5, docDone.frozenBlockCount)
        assertTrue((docDone.blocks[4] as MdBlock.CodeFence).isClosed)
    }
}
