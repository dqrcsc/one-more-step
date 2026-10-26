package com.oms.client.capability

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StoragePathPolicyTest {
    @Test fun siblingPrefixAndTraversalCannotEscapeDirectory() {
        val root = File("/tmp/oms-policy/Download")
        assertTrue(StoragePathPolicy.isWithin(root, File(root, "nested/report.pdf")))
        assertFalse(StoragePathPolicy.isWithin(root, File("/tmp/oms-policy/DownloadPrivate/secret")))
        assertFalse(StoragePathPolicy.isWithin(root, File(root, "../secret")))
    }
    @Test fun mediaUriNeedsTheSameEnabledDirectoryAsFilePaths() {
        assertTrue(StoragePathPolicy.mediaPathAllowed("DCIM/Screenshots/", setOf("screenshots")))
        assertTrue(StoragePathPolicy.mediaPathAllowed("Pictures/Screenshots/", setOf("screenshots")))
        assertFalse(StoragePathPolicy.mediaPathAllowed("DCIM/Camera/", setOf("screenshots")))
        assertFalse(StoragePathPolicy.mediaPathAllowed("Pictures/ScreenshotsPrivate/", setOf("screenshots")))
        assertFalse(StoragePathPolicy.mediaPathAllowed("Download/../Private/", setOf("download")))
        assertTrue(StoragePathPolicy.mediaPathAllowed("Download/report/", setOf("downloads")))
        assertFalse(StoragePathPolicy.mediaPathAllowed("Download/", emptySet()))
    }
}
