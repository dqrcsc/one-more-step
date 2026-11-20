package com.oms.client.cockpit

import com.oms.client.data.StreamItemModel
import com.oms.client.ui.markdown.MarkdownBlockParser
import com.oms.client.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChisuDocumentParserTest {

    @Test
    fun stripsYamlFrontmatterAndVerboseNoteHeaderBlock() {
        val rawNote = """
            ---
            generated_content_sha256: 7f8a9c1234567890abcdef1234567890abcdef1234567890abcdef1234567890
            type: source_note
            title: "AI Agent 的记忆架构设计（初筛伴读笔记）"
            source_path: "/Users/dqrcsc/entropy/thoughts/01_inputs/wechat/2026_11_20_agent_memory.md"
            source_url: "https://mp.weixin.qq.com/s?__biz=MzA1234&mid=999&idx=1&sn=abc"
            account_name: "认知架构笔记"
            created: 2026-11-20
            updated: 2026-11-20
            process_level: light
            triage: process_now
            candidate_vault_nodes: ["agent_memory", "context_engineering"]
            tags:
              - wechat_article
              - source_note
            ---

            # AI Agent 的记忆架构设计（初筛伴读笔记）

            - **原始材料**：[[2026_11_20_agent_memory]]（[微信原文](https://mp.weixin.qq.com/s?__biz=MzA1234&mid=999&idx=1&sn=abc)）
            - **公众号 / 作者**：认知架构笔记
            - **处理深度**：`light`（后台自动初筛）
            - **建议分诊（Triage）**：`process_now`
            - **关联状态**：候选线索仅供精读核验，尚未建立笔记关系。

            ---

            ## 💧 脱水干货

            核心观点是把长期记忆与工作记忆分层管理，避免上下文窗口被噪声淹没。
        """.trimIndent()

        val parsed = ChisuDocumentParser.parse(rawNote, fallbackTitle = "💬 AI Agent 的记忆架构设计")
        assertEquals("AI Agent 的记忆架构设计", parsed.cleanTitle)
        assertEquals("认知架构笔记", parsed.author)
        assertEquals("https://mp.weixin.qq.com/s?__biz=MzA1234&mid=999&idx=1&sn=abc", parsed.sourceUrl)
        assertEquals("process_now", parsed.triage)
        assertEquals("light", parsed.processLevel)

        // Body must NOT contain any YAML frontmatter or verbose metadata noise
        assertFalse(parsed.bodyMarkdown.contains("generated_content_sha256"))
        assertFalse(parsed.bodyMarkdown.contains("source_path"))
        assertFalse(parsed.bodyMarkdown.contains("candidate_vault_nodes"))
        assertFalse(parsed.bodyMarkdown.contains("关联状态"))
        assertFalse(parsed.bodyMarkdown.contains("原始材料"))
        assertTrue(parsed.bodyMarkdown.startsWith("## 💧 脱水干货"))
        assertTrue(parsed.previewExcerpt.contains("长期记忆与工作记忆分层管理"))
        assertFalse(parsed.previewExcerpt.contains("generated_content_sha256"))

        // Clean TTS output must be pure readable Chinese without YAML/URLs
        val tts = ChisuDocumentParser.cleanForTts(rawNote, "💬 AI Agent 的记忆架构设计")
        assertTrue(tts.startsWith("AI Agent 的记忆架构设计。"))
        assertFalse(tts.contains("generated_content_sha256"))
        assertFalse(tts.contains("https://"))
    }

    @Test
    fun stripsSourceMarkdownMetadataAndPreservesInlineImages() {
        val rawSource = """
            ---
            type: source
            source_type: wechat_article
            title: "上下文工程实战"
            account_name: "深度技术周刊"
            source_url: "https://mp.weixin.qq.com/s/xyz"
            published_date: "2026-11-18"
            captured_date: 2026-11-20
            process_level: raw
            triage: process_later
            tags:
              - wechat_article
              - input
            ---

            # 上下文工程实战

            - **公众号 / 作者**：深度技术周刊
            - **发布时间**：2026-11-18
            - **捕获时间**：2026-11-20
            - **原始链接**：[原文链接](https://mp.weixin.qq.com/s/xyz)
            - **转发时批注**：重点看第三节的评测基准

            ---

            这是保存下来的原文第一段。

            ![架构示意](assets/arch_01.jpg)

            这是原文第二段。
        """.trimIndent()

        val parsed = ChisuDocumentParser.parse(rawSource)
        assertEquals("上下文工程实战", parsed.cleanTitle)
        assertEquals("深度技术周刊", parsed.author)
        assertEquals("2026-11-18", parsed.publishDate)
        assertEquals("重点看第三节的评测基准", parsed.userNotes)
        assertFalse(parsed.bodyMarkdown.contains("source_type"))
        assertFalse(parsed.bodyMarkdown.contains("捕获时间"))
        assertTrue(parsed.bodyMarkdown.startsWith("这是保存下来的原文第一段。"))

        // Verify MarkdownBlockParser produces MdBlock.Image for standalone images
        val mdDoc = MarkdownBlockParser.parse(parsed.bodyMarkdown)
        assertEquals(3, mdDoc.blocks.size)
        assertTrue(mdDoc.blocks[0] is MdBlock.Paragraph)
        val img = mdDoc.blocks[1] as MdBlock.Image
        assertEquals("架构示意", img.alt)
        assertEquals("assets/arch_01.jpg", img.src)
        assertTrue(mdDoc.blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun extractsArticleIdAndMergesMacAndLocalStreamsWithoutDuplicates() {
        assertEquals(42, ChisuDocumentParser.extractArticleIdFromFileUri("file:///data/user/0/com.oms.client/files/uuid_wx_42_agent_memory.md"))
        assertEquals(17, ChisuDocumentParser.extractArticleIdFromFileUri("file:///tmp/article_17_test.md"))
        assertNull(ChisuDocumentParser.extractArticleIdFromFileUri("file:///tmp/random_note.md"))

        val macArticles = listOf(
            ChisuArticleItemUi(
                stableKey = "wx_42",
                articleId = 42,
                title = "AI Agent 的记忆架构设计",
                author = "认知架构笔记",
                sourceUrl = "https://mp.weixin.qq.com/s/42",
                createdAtMs = 2000L,
                triage = "process_now",
                hasNote = true,
                hasSource = true
            )
        )
        val localItems = listOf(
            StreamItemModel(
                itemId = "s_1",
                direction = "MAC_TO_PHONE",
                itemType = "COMMUTE_READER",
                intentTag = "wechat_context",
                title = "💬 AI Agent 的记忆架构设计",
                textContent = "## 脱水干货\n本地已推送的伴读正文",
                voiceTranscript = null,
                localFileUri = "file:///files/uuid_wx_42_agent.md",
                macSavedPath = null,
                syncStatus = "DELIVERED",
                metadataJson = """{"enableCommuteTts":true}""",
                createdAtMs = 2100L
            ),
            StreamItemModel(
                itemId = "edge_wx_99",
                direction = "MAC_TO_PHONE",
                itemType = "TEXT",
                intentTag = "wechat_context",
                title = "💬 夜间抢答文章",
                textContent = "## 脱水干货\n手机离线抢答结果",
                voiceTranscript = null,
                localFileUri = null,
                macSavedPath = null,
                syncStatus = "DELIVERED",
                metadataJson = """{"edgeAnalyzed":true,"sourceFilePath":"/tmp/edge_source.md","hasSource":true}""",
                createdAtMs = 3000L
            )
        )

        val merged = ChisuDocumentParser.mergeChisuLibrary(macArticles, localItems)
        assertEquals(2, merged.size)
        // Newest first: edge standby item (3000ms), then deduplicated article #42 (2000ms)
        assertEquals("local_edge_wx_99", merged[0].stableKey)
        assertTrue(merged[0].isEdgeNightStandby)
        assertTrue(merged[0].hasSource)
        assertEquals("wx_42", merged[1].stableKey)
        assertEquals("s_1", merged[1].localStreamItemId)
        assertTrue(merged[1].localNoteMarkdown!!.contains("本地已推送的伴读正文"))
        assertTrue(merged[1].hasSource)
    }

    @Test
    fun mapsTriageLabelsToReadableChinese() {
        assertEquals("深度精读", ChisuDocumentParser.triageDisplayLabel("process_later", "deep"))
        assertEquals("建议深读", ChisuDocumentParser.triageDisplayLabel("process_now", "light"))
        assertEquals("备查", ChisuDocumentParser.triageDisplayLabel("reference", "light"))
        assertEquals("仅存原文", ChisuDocumentParser.triageDisplayLabel("keep_raw", "raw"))
        assertEquals("待精读", ChisuDocumentParser.triageDisplayLabel("process_later", "light"))
    }
}
