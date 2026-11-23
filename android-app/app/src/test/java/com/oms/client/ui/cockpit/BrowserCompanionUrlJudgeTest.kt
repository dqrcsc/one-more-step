package com.oms.client.ui.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCompanionUrlJudgeTest {

    @Test
    fun canonicalizesMobileAndShortYouTubeUrls() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            BrowserCompanionUrlJudge.canonicalize("https://m.youtube.com/watch?v=dQw4w9WgXcQ&pp=ygUIAQ%3D%3D")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            BrowserCompanionUrlJudge.canonicalize("https://youtu.be/dQw4w9WgXcQ?si=abc")
        )
        assertEquals(
            "https://www.youtube.com/shorts/xyz987654",
            BrowserCompanionUrlJudge.canonicalize("https://m.youtube.com/shorts/xyz987654#bottom")
        )
    }

    @Test
    fun filtersFeedAndSearchNoiseWhileMatchingValuableDetailPages() {
        // Noise pages that must NOT auto-curate
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://m.youtube.com"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://m.youtube.com/results?search_query=agent"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://www.google.com/search?q=claude+mcp"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://mail.google.com/mail/u/0/"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://x.com/home"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("https://github.com/trending"))
        assertFalse(BrowserCompanionUrlJudge.isCuratableDetailPage("http://192.168.1.8:19527/inbox"))

        // Genuine content pages that SHOULD trigger dwell auto-curate
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://www.youtube.com/shorts/xyz987654"))
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://x.com/karpathy/status/1899999999"))
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://github.com/anthropics/claude-code"))
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://zhuanlan.zhihu.com/p/689123456"))
        assertTrue(BrowserCompanionUrlJudge.isCuratableDetailPage("https://mp.weixin.qq.com/s/abcdefg12345"))
    }

    @Test
    fun multiTabStatePreservesUnclosedTabsAcrossMinimizeAndSwitches() {
        var persistedJson = ""
        val state = WebAppHubState(onPersistTabs = { persistedJson = it })

        // 1. Open YouTube from Dock -> creates Tab 1
        state.openUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ", "YouTube 视频")
        assertEquals(1, state.tabs.size)
        assertTrue(state.isBrowsing)

        // 2. Minimize to Dock (jump out without closing) -> Tab 1 MUST stay preserved!
        state.exitToDock()
        assertFalse(state.isBrowsing)
        assertTrue(state.hasOpenTabs)
        assertEquals(1, state.tabs.size)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", state.activeUrl)

        // 3. Open GitHub from Dock -> creates Tab 2 without overwriting Tab 1!
        state.openUrl("https://github.com/anthropics/claude-code", "GitHub")
        assertEquals(2, state.tabs.size)
        assertTrue(state.isBrowsing)

        // 4. Minimize again & tap YouTube card -> reuses existing YouTube Tab 1 instead of duplicating!
        state.exitToDock()
        state.openUrl("https://www.youtube.com", "YouTube")
        assertEquals(2, state.tabs.size)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", state.activeUrl)
        assertTrue(state.isBrowsing)

        // 5. Cold-start restoration preserves both unclosed tabs
        val restored = WebAppHubState().apply { restoreFromJson(persistedJson) }
        assertEquals(2, restored.tabs.size)

        // 6. Explicit close (✕) removes only the closed tab and switches cleanly to remaining tab
        val ytId = state.activeTab!!.tabId
        state.closeTab(ytId)
        assertEquals(1, state.tabs.size)
        assertEquals("https://github.com/anthropics/claude-code", state.activeUrl)

        state.closeAllTabs()
        assertEquals(0, state.tabs.size)
        assertFalse(state.hasOpenTabs)
    }
}
