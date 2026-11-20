package com.oms.client.cockpit

import com.oms.client.ui.cockpit.prettifyModelShortName
import com.oms.client.ui.markdown.MarkdownBlockParser
import com.oms.client.ui.markdown.MdBlock
import com.oms.client.ui.theme.OmsAppearanceMode
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsThemeStyle
import com.oms.client.ui.theme.buildRawPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitMarkdownAndParserTest {

    @Test
    fun incrementalMarkdownParserFreezesCompletedBlocksAndParsesCodeTablesAndMath() {
        val streamingMd = """
            # 咫尺 v3.0 架构要点
            
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

    @Test
    fun multiThemePalettesBuildCleanlyAcrossAllFiveStylesInLightAndDarkModes() {
        assertEquals(5, OmsThemeStyle.entries.size)
        assertEquals(OmsAppearanceMode.SYSTEM, OmsAppearanceMode.fromKey("unknown"))
        assertEquals(OmsAppearanceMode.LIGHT, OmsAppearanceMode.fromKey("light"))
        assertEquals(OmsAppearanceMode.DARK, OmsAppearanceMode.fromKey("dark"))

        for (style in OmsThemeStyle.entries) {
            assertEquals(style, OmsThemeStyle.fromKey(style.key))
            val light = buildRawPalette(style, dark = false)
            val dark = buildRawPalette(style, dark = true)

            assertFalse(light.isDark)
            assertTrue(dark.isDark)
            assertNotEquals(light.canvas, dark.canvas)
            assertNotEquals(light.textPrimary, dark.textPrimary)
            assertNotEquals(light.canvas, light.textPrimary)
            assertNotEquals(dark.canvas, dark.textPrimary)
        }
    }

    @Test
    fun bespokeVectorIconSuiteConstructsAllIconsWithoutPathErrors() {
        val allIcons = listOf(
            OmsIcons.Menu,
            OmsIcons.NewChat,
            OmsIcons.Search,
            OmsIcons.Plus,
            OmsIcons.Close,
            OmsIcons.Check,
            OmsIcons.ArrowUp,
            OmsIcons.ArrowDown,
            OmsIcons.ArrowLeft,
            OmsIcons.ChevronRight,
            OmsIcons.ChevronDown,
            OmsIcons.ChevronUp,
            OmsIcons.MoreHorizontal,
            OmsIcons.Sparkles,
            OmsIcons.Lightbulb,
            OmsIcons.Globe,
            OmsIcons.Incognito,
            OmsIcons.Mic,
            OmsIcons.MicOff,
            OmsIcons.Waveform,
            OmsIcons.StopSquare,
            OmsIcons.Play,
            OmsIcons.Volume,
            OmsIcons.Camera,
            OmsIcons.Leaf,
            OmsIcons.Image,
            OmsIcons.Paperclip,
            OmsIcons.FileText,
            OmsIcons.Palette,
            OmsIcons.Video,
            OmsIcons.Music,
            OmsIcons.Copy,
            OmsIcons.Bookmark,
            OmsIcons.BookmarkFilled,
            OmsIcons.Laptop,
            OmsIcons.Terminal,
            OmsIcons.Send,
            OmsIcons.Refresh,
            OmsIcons.Sliders,
            OmsIcons.Pin,
            OmsIcons.Trash,
            OmsIcons.Shield,
            OmsIcons.Bolt,
            OmsIcons.Calendar,
            OmsIcons.Clock,
            OmsIcons.Sun,
            OmsIcons.Moon,
            OmsIcons.BookOpen,
            OmsIcons.Flashlight
        )
        assertEquals(49, allIcons.size)
        allIcons.forEach { vec ->
            assertNotNull(vec)
            assertEquals(24f, vec.viewportWidth, 0.01f)
            assertEquals(24f, vec.viewportHeight, 0.01f)
        }
        assertEquals("GPT-6.2 Sol", prettifyModelShortName("openai:gpt-6.2-sol"))
        assertEquals("Gemini 4 Argon", prettifyModelShortName("gemini:gemini-4-argon"))
    }
}
