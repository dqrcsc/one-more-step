package com.oms.client.cockpit

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import com.oms.client.data.StreamItemModel

class MacRequestScopeTest {
    @Test fun localHistoryFromAnotherMacDoesNotMergeIntoSameIdOrTitle() {
        val remote = ChisuArticleItemUi(stableKey = "B:wx_1", ownerMacId = "B", articleId = 1,
            title = "Same title", sourceUrl = "https://example.test/same", createdAtMs = 1)
        val local = StreamItemModel(itemId = "localA", direction = "MAC_TO_PHONE", itemType = "COMMUTE_READER",
            intentTag = "wechat_context", title = "Same title", textContent = "# Same title\nA-only body", voiceTranscript = null,
            localFileUri = null, macSavedPath = null, syncStatus = "DELIVERED",
            metadataJson = """{"sourceMacId":"A","wechatArticleId":1,"sourceUrl":"https://example.test/same"}""", createdAtMs = 1)
        val merged = ChisuDocumentParser.mergeChisuLibrary(listOf(remote), listOf(local))
        assertEquals(2, merged.size)
        assertNull(merged.first { it.ownerMacId == "B" }.localNoteMarkdown)
        assertTrue(merged.first { it.ownerMacId == "A" }.localNoteMarkdown!!.contains("A-only"))
    }

    @Test fun switchingAwayAndBackRejectsOldResponsesAndUsesDistinctCacheFolders() {
        val scope = MacRequestScope()
        scope.switchTo("macA")
        val old = scope.ticket()
        scope.switchTo("macB")
        assertFalse(scope.accepts(old))
        scope.switchTo("macA")
        assertFalse(scope.accepts(old))
        assertTrue(scope.accepts(scope.ticket()))
        val root = Files.createTempDirectory("oms-mac-cache").toFile()
        val a = MacRequestScope.directory(root, "macA")
        val b = MacRequestScope.directory(root, "macB")
        assertNotEquals(a, b)
        a.resolve("article_1.json").writeText("A")
        assertFalse(b.resolve("article_1.json").exists())
    }

    @Test fun imageReadStopsAtLimitEvenWithoutOrWithLyingLengthHeader() {
        var consumed = 0
        val infinite = object : InputStream() { override fun read(): Int { consumed++; return 1 } }
        try { infinite.readBounded(100, 1); fail("must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(101, consumed)
        try { ByteArrayInputStream(byteArrayOf(1)).readBounded(100, 101); fail("must reject declared length") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBounded(100))
    }
}
