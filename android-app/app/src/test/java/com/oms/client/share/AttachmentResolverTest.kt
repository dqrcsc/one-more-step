package com.oms.client.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentResolverTest {

    @Test
    fun `resolves Word docx without broken vendor suffix`() {
        val (mime, ext) = AttachmentResolver.resolveMimeAndExtension(
            rawFileName = "年度产品规划 (终版).docx",
            resolverMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mime)
        assertEquals("docx", ext)
        val safe = AttachmentResolver.sanitizeFileName("年度产品规划 (终版).docx", "file", ext)
        assertEquals("年度产品规划 (终版).docx", safe)
    }

    @Test
    fun `appends docx extension when display name has no extension`() {
        val (mime, ext) = AttachmentResolver.resolveMimeAndExtension(
            rawFileName = "季度复盘报告",
            resolverMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        assertEquals("docx", ext)
        assertEquals(
            "季度复盘报告.docx",
            AttachmentResolver.sanitizeFileName("季度复盘报告", "file", ext)
        )
        assertEquals("📘", AttachmentResolver.badgeForFile("季度复盘报告.docx", mime))
    }

    @Test
    fun `resolves PDF Excel PPT Markdown and unknown binary cleanly`() {
        assertEquals(
            "application/pdf" to "pdf",
            AttachmentResolver.resolveMimeAndExtension("架构白皮书.pdf", "application/octet-stream")
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            AttachmentResolver.resolveMimeAndExtension(null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )
        assertEquals(
            "text/markdown" to "md",
            AttachmentResolver.resolveMimeAndExtension("notes.md", "text/plain")
        )
        // Unknown binary must never default to image/png
        assertEquals(
            "application/octet-stream" to "bin",
            AttachmentResolver.resolveMimeAndExtension(null, null)
        )
    }

    @Test
    fun `sanitizes traversal characters and bounds long Chinese filenames under POSIX byte limit`() {
        val traversal = AttachmentResolver.sanitizeFileName("../../etc/合同:草稿?.pdf", "att", "pdf")
        assertEquals("合同_草稿_.pdf", traversal)

        val longChinese = "测试超长中文文件名".repeat(20) + ".docx"
        val bounded = AttachmentResolver.sanitizeFileName(longChinese, "att", "docx")
        assertTrue(bounded.endsWith(".docx"))
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun `infers MEDIA vs FILE vs VOICE_IDEA itemType accurately`() {
        assertEquals("MEDIA", AttachmentResolver.inferItemType(listOf("image/jpeg", "image/png")))
        assertEquals(
            "FILE",
            AttachmentResolver.inferItemType(
                listOf("image/jpeg", "application/pdf")
            )
        )
        assertEquals(
            "FILE",
            AttachmentResolver.inferItemType(
                listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            )
        )
        assertEquals("VOICE_IDEA", AttachmentResolver.inferItemType(listOf("audio/mp4"), hasVoiceNote = true))
    }

    @Test
    fun `classifies screenshot and optional camera pill triggers`() {
        val (shotShow, isShot) = ScreenshotPillController.classifyNewMediaCapture(
            "Screenshot_2026-11-14.jpg",
            "DCIM/Screenshots/",
            cameraPillEnabled = false
        )
        assertTrue(shotShow)
        assertTrue(isShot)

        val (camEnabledShow, camIsShot) = ScreenshotPillController.classifyNewMediaCapture(
            "IMG_20261114_170000.jpg",
            "DCIM/Camera/",
            cameraPillEnabled = true
        )
        assertTrue(camEnabledShow)
        assertFalse(camIsShot)

        val (camDisabledShow, _) = ScreenshotPillController.classifyNewMediaCapture(
            "IMG_20261114_170000.jpg",
            "DCIM/Camera/",
            cameraPillEnabled = false
        )
        assertFalse(camDisabledShow)
    }
}
