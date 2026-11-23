package com.oms.client.ui.cockpit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.oms.client.OmsApplication
import com.oms.client.capability.PhoneToolExecutor
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.OmsTogglePill
import com.oms.core.ai.LlmTurnMessage
import com.oms.core.ai.MiniJson
import com.oms.core.ai.ProposedPhoneToolAction
import com.oms.core.ai.RouteIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume

data class PinnedWebAppItem(
    val id: String,
    val name: String,
    val url: String,
    val tag: String = "网页应用",
    val isBuiltin: Boolean = false
)

data class AutomationPlaybookItem(
    val id: String,
    val name: String,
    val startUrl: String,
    val instruction: String,
    val isBuiltin: Boolean = false
)

object BuiltinWebApps {
    fun defaultList(macLanUrl: String? = null): List<PinnedWebAppItem> {
        val list = mutableListOf(
            PinnedWebAppItem("youtube", "YouTube", "https://m.youtube.com", "免 VPN 视频", true),
            PinnedWebAppItem("google", "Google 搜索", "https://www.google.com", "免 VPN 全网检索", true),
            PinnedWebAppItem("gmail", "Gmail", "https://mail.google.com", "免 VPN 邮箱", true),
            PinnedWebAppItem("x_twitter", "X (Twitter)", "https://x.com", "全球科技动态", true),
            PinnedWebAppItem("github", "GitHub", "https://github.com", "代码仓库", true),
            PinnedWebAppItem("aistudio", "AI Studio", "https://aistudio.google.com", "模型工坊", true),
            PinnedWebAppItem("reddit", "Reddit", "https://www.reddit.com", "技术社区", true),
            PinnedWebAppItem("notion", "Notion", "https://www.notion.so", "笔记协作", true),
            PinnedWebAppItem("feishu", "飞书文档", "https://www.feishu.cn/drive/home/", "国内直连协作", true),
            PinnedWebAppItem("zhihu", "知乎", "https://www.zhihu.com", "深度问答", true)
        )
        if (!macLanUrl.isNullOrBlank()) {
            list.add(0, PinnedWebAppItem("mac_console", "Mac 咫尺总控", macLanUrl, "局域网直连", true))
        }
        return list
    }

    fun defaultPlaybooks(): List<AutomationPlaybookItem> = listOf(
        AutomationPlaybookItem(
            id = "pb_yt_digest",
            name = "YouTube 首页趋势速览",
            startUrl = "https://m.youtube.com",
            instruction = "向下滚动一屏并提取当前页面推荐视频标题与频道，整理出最值得看的 5 条技术或深度内容。",
            isBuiltin = true
        ),
        AutomationPlaybookItem(
            id = "pb_google_ai",
            name = "Google 检索今日 AI 进展",
            startUrl = "https://www.google.com",
            instruction = "在搜索框输入 AI Agent latest breakthroughs today 并提交搜索，提取前几条核心结果摘要。",
            isBuiltin = true
        ),
        AutomationPlaybookItem(
            id = "pb_github_trending",
            name = "GitHub Trending 热榜",
            startUrl = "https://github.com/trending",
            instruction = "提取今日 GitHub Trending 排名前 6 的开源仓库名称、语言与功能亮点。",
            isBuiltin = true
        )
    )
}

/**
 * Routes Android Chromium WebView traffic through our in-process `LoopbackMixedGateway` (`127.0.0.1:<port>`)
 * so YouTube, Google, Gmail, X, GitHub, etc. load directly WITHOUT installing any system VPN on the phone!
 */
object WebViewProxyControllerBridge {
    private val directExecutor = Executor { r -> r.run() }

    fun applyMode(mode: String, onApplied: ((Boolean, String) -> Unit)? = null) {
        val app = OmsApplication.instance ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onApplied?.invoke(false, "当前系统 WebView 组件不支持应用内代理覆盖")
            return
        }

        val proxyMgr = app.proxyManager
        if (mode == "DIRECT" || !proxyMgr.hasAvailableNodes()) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(directExecutor) {
                    val hint = if (mode == "DIRECT") "已切换为仅直连模式" else "暂无可用代理节点，当前直连"
                    onApplied?.invoke(false, hint)
                }
            }
            return
        }

        val port = proxyMgr.ensureWebViewGatewayStarted()
        val builder = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$port")
            .addBypassRule("<local>")
            .addBypassRule("localhost")
            .addBypassRule("127.0.0.1")
            .addBypassRule("10.0.0.0/8")
            .addBypassRule("172.16.0.0/12")
            .addBypassRule("192.168.0.0/16")

        if (mode == "SMART") {
            listOf(
                "*.cn",
                "*.feishu.cn",
                "*.zhihu.com",
                "*.qq.com",
                "*.weixin.qq.com",
                "*.baidu.com",
                "*.bilibili.com",
                "*.taobao.com",
                "*.jd.com",
                "*.eastmoney.com",
                "*.163.com"
            ).forEach { builder.addBypassRule(it) }
        }

        runCatching {
            ProxyController.getInstance().setProxyOverride(builder.build(), directExecutor) {
                val desc = proxyMgr.router.describeSelection()
                onApplied?.invoke(true, "免 VPN 代理已生效 · $desc")
            }
        }.onFailure { err ->
            onApplied?.invoke(false, "挂载内置代理失败：${err.message}")
        }
    }
}

private fun sanitizeStandardChromeUserAgent(rawUa: String): String {
    // Strip `; wv` and `Version/4.0 ` so Google Accounts / OAuth (`accounts.google.com`) allow normal sign-in!
    return rawUa
        .replace("; wv)", ")")
        .replace("; wv;", ";")
        .replace(Regex("""\bVersion/\d+\.\d+\s*"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}

/**
 * Custom WebView that calls `requestDisallowInterceptTouchEvent(true)` on every touch gesture
 * so Compose parents (ModalNavigationDrawer / Scrollable containers) NEVER steal horizontal video
 * progress-bar scrubbing, carousel swipes, or vertical scrolling!
 */
private class TouchUnimpededWebView(context: android.content.Context) : WebView(context) {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

@Stable
class BrowserTabSession(
    val tabId: String,
    initialUrl: String,
    initialTitle: String = ""
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var loadProgress by mutableIntStateOf(0)
    var desktopMode by mutableStateOf(false)
    var webViewRef: WebView? = null
    var lastLoadedUrl: String = ""
}

internal fun extractRegistrableHostKey(rawUrl: String): String {
    return runCatching {
        val host = (java.net.URI(rawUrl.trim()).host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
        host
    }.getOrDefault("")
}

/**
 * Persistent Multi-Tab state holder for Bottom Tab 4 (`应用舱`), preserving multiple unclosed tabs,
 * live `WebView` instances (video progress & scroll offset), Cookie/DOM login sessions, and fullscreen video!
 * Tabs stay alive until the user EXPLICITLY taps Close (`✕`).
 */
@Stable
class WebAppHubState(
    initialUrl: String = "",
    initialTitle: String = "",
    private val maxLiveWebViews: Int = 6,
    private val onPersistTabs: ((String) -> Unit)? = null
) {
    val tabs = mutableStateListOf<BrowserTabSession>()
    var activeTabId by mutableStateOf<String?>(null)
    var showDockOverlay by mutableStateOf(initialUrl.isBlank())
    var addressInput by mutableStateOf(initialUrl)
    var customFullscreenView by mutableStateOf<View?>(null)
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var multiTabHostFrame: FrameLayout? = null

    init {
        if (initialUrl.isNotBlank()) {
            val first = BrowserTabSession(
                tabId = "tab_${System.nanoTime()}",
                initialUrl = initialUrl.trim(),
                initialTitle = initialTitle.trim()
            )
            tabs.add(first)
            activeTabId = first.tabId
            showDockOverlay = false
        }
    }

    val hasOpenTabs: Boolean
        get() = tabs.isNotEmpty()

    val activeTab: BrowserTabSession?
        get() = tabs.firstOrNull { it.tabId == activeTabId } ?: tabs.lastOrNull()

    val isBrowsing: Boolean
        get() = activeTab != null && !showDockOverlay

    var activeUrl: String
        get() = activeTab?.url.orEmpty()
        set(value) {
            activeTab?.url = value
        }

    var activeTitle: String
        get() = activeTab?.title.orEmpty()
        set(value) {
            activeTab?.title = value
            persistTabsSnapshot()
        }

    var loadProgress: Int
        get() = activeTab?.loadProgress ?: 0
        set(value) {
            activeTab?.loadProgress = value
        }

    var desktopMode: Boolean
        get() = activeTab?.desktopMode ?: false
        set(value) {
            activeTab?.desktopMode = value
        }

    var webViewRef: WebView?
        get() = activeTab?.webViewRef
        set(value) {
            activeTab?.webViewRef = value
        }

    var lastLoadedUrl: String
        get() = activeTab?.lastLoadedUrl.orEmpty()
        set(value) {
            activeTab?.lastLoadedUrl = value
        }

    /**
     * Opens a URL:
     * - When called from the App Hub Dock (`showDockOverlay == true` or `forceNewTab == true`):
     *   reuses an existing open tab of the same host (e.g. `youtube.com`) so unclosed videos resume immediately,
     *   or spawns a new preserved tab alongside existing tabs!
     * - When called from the Omnibar inside an active tab (`isBrowsing == true` and `!forceNewTab`):
     *   navigates the current tab to `url`.
     */
    fun openUrl(url: String, title: String = "", forceNewTab: Boolean = false) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        if (!forceNewTab && isBrowsing) {
            val cur = activeTab ?: return
            cur.url = trimmed
            if (title.isNotBlank()) cur.title = title.trim()
            addressInput = trimmed
            val wv = cur.webViewRef
            if (wv != null && cur.lastLoadedUrl != trimmed) {
                cur.lastLoadedUrl = trimmed
                wv.loadUrl(trimmed)
            }
            persistTabsSnapshot()
            return
        }

        if (!forceNewTab) {
            val targetHost = extractRegistrableHostKey(trimmed)
            val existingSameHost = if (targetHost.isNotEmpty()) {
                tabs.lastOrNull { extractRegistrableHostKey(it.url) == targetHost }
            } else null
            if (existingSameHost != null) {
                resumeTab(existingSameHost.tabId)
                return
            }
        }

        // Enforce gentle cap on live WebViews by evicting oldest background WebView instance (keeping tab entry!)
        val liveCount = tabs.count { it.webViewRef != null }
        if (liveCount >= maxLiveWebViews) {
            val victim = tabs.firstOrNull { it.tabId != activeTabId && it.webViewRef != null }
            if (victim != null) {
                destroySingleTabWebView(victim)
            }
        }

        val created = BrowserTabSession(
            tabId = "tab_${System.currentTimeMillis()}_${tabs.size}",
            initialUrl = trimmed,
            initialTitle = title.trim()
        )
        tabs.add(created)
        activeTabId = created.tabId
        addressInput = trimmed
        showDockOverlay = false
        persistTabsSnapshot()
    }

    fun resumeTab(tabId: String) {
        val target = tabs.firstOrNull { it.tabId == tabId } ?: return
        activeTabId = target.tabId
        addressInput = target.url
        showDockOverlay = false
        persistTabsSnapshot()
    }

    fun pauseActiveVideosForJumpOut() {
        exitFullscreenVideo()
        tabs.forEach { tab ->
            runCatching {
                tab.webViewRef?.evaluateJavascript(
                    "(function(){ document.querySelectorAll('video').forEach(function(v){ if(!v.paused) v.pause(); }); })();",
                    null
                )
            }
        }
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * Minimizes back to App Hub Dock WITHOUT closing or destroying open tabs!
     */
    fun exitToDock() {
        pauseActiveVideosForJumpOut()
        showDockOverlay = true
        addressInput = ""
        persistTabsSnapshot()
    }

    /**
     * Explicitly closes a single tab (`✕`), stopping any playing video/audio and freeing its WebView.
     */
    fun closeTab(tabId: String) {
        exitFullscreenVideo()
        val idx = tabs.indexOfFirst { it.tabId == tabId }
        if (idx < 0) return
        val removed = tabs.removeAt(idx)
        destroySingleTabWebView(removed)
        runCatching { CookieManager.getInstance().flush() }

        if (tabs.isEmpty()) {
            activeTabId = null
            addressInput = ""
            showDockOverlay = true
        } else if (activeTabId == tabId) {
            val next = tabs[idx.coerceAtMost(tabs.lastIndex)]
            activeTabId = next.tabId
            if (!showDockOverlay) {
                addressInput = next.url
            }
        }
        persistTabsSnapshot()
    }

    fun closeAllTabs() {
        exitFullscreenVideo()
        val copy = tabs.toList()
        tabs.clear()
        copy.forEach { destroySingleTabWebView(it) }
        activeTabId = null
        addressInput = ""
        showDockOverlay = true
        runCatching { CookieManager.getInstance().flush() }
        persistTabsSnapshot()
    }

    private fun destroySingleTabWebView(tab: BrowserTabSession) {
        val wv = tab.webViewRef ?: return
        tab.webViewRef = null
        tab.lastLoadedUrl = ""
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    fun exitFullscreenVideo() {
        runCatching { customViewCallback?.onCustomViewHidden() }
        customViewCallback = null
        customFullscreenView = null
    }

    fun destroyWebView() {
        exitFullscreenVideo()
        runCatching { CookieManager.getInstance().flush() }
        tabs.forEach { destroySingleTabWebView(it) }
        multiTabHostFrame?.removeAllViews()
        multiTabHostFrame = null
    }

    fun serializeToJson(): String {
        val tabMaps = tabs.take(12).mapNotNull { t ->
            if (t.url.isBlank()) null
            else linkedMapOf<String, Any?>(
                "tabId" to t.tabId,
                "url" to t.url,
                "title" to t.title
            )
        }
        return MiniJson.stringify(
            linkedMapOf<String, Any?>(
                "activeTabId" to (activeTabId ?: ""),
                "showDockOverlay" to showDockOverlay,
                "tabs" to tabMaps
            )
        )
    }

    fun restoreFromJson(rawJson: String?) {
        if (rawJson.isNullOrBlank() || tabs.isNotEmpty()) return
        runCatching {
            val root = MiniJson.parseObject(rawJson)
            val arr = root["tabs"] as? List<*> ?: return
            arr.forEachIndexed { idx, rawItem ->
                val o = rawItem as? Map<*, *> ?: return@forEachIndexed
                val u = o["url"]?.toString()?.trim().orEmpty()
                if (u.isEmpty()) return@forEachIndexed
                tabs.add(
                    BrowserTabSession(
                        tabId = o["tabId"]?.toString()?.takeIf { it.isNotBlank() } ?: "tab_restored_$idx",
                        initialUrl = u,
                        initialTitle = o["title"]?.toString().orEmpty()
                    )
                )
            }
            if (tabs.isNotEmpty()) {
                val savedActive = root["activeTabId"]?.toString().orEmpty()
                activeTabId = tabs.firstOrNull { it.tabId == savedActive }?.tabId ?: tabs.last().tabId
                showDockOverlay = root["showDockOverlay"] == true
                if (!showDockOverlay) {
                    addressInput = activeTab?.url.orEmpty()
                }
            }
        }
    }

    fun persistTabsSnapshot() {
        onPersistTabs?.invoke(serializeToJson())
    }
}

private suspend fun evaluateJsSuspend(webView: WebView, script: String): String =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { raw ->
                if (!cont.isActive) return@evaluateJavascript
                val unquoted = if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                    runCatching { JSONObject("{\"v\":$raw}").optString("v", "") }.getOrDefault(raw)
                } else {
                    raw ?: ""
                }
                cont.resume(unquoted)
            }
        }
    }

/**
 * Pure URL normalizer & detail-page filter for Browser Companion Auto-Curate (`伴读自动入库`).
 * Filters out home feeds, search result listings, login/inbox screens, and private IPs so ONLY
 * genuine content pages (YouTube videos/shorts, articles, posts, papers, repos) trigger auto-curation.
 */
object BrowserCompanionUrlJudge {
    fun canonicalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val uri = java.net.URI(trimmed)
            val scheme = (uri.scheme ?: "").lowercase()
            if (scheme != "http" && scheme != "https") return@runCatching ""
            val host = (uri.host ?: "").lowercase()
            val path = uri.rawPath ?: ""
            if (host == "youtu.be") {
                val vid = path.trim('/').split('/').firstOrNull()?.trim().orEmpty()
                if (vid.length in 6..20) return@runCatching "https://www.youtube.com/watch?v=$vid"
            }
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                if (path == "/watch") {
                    val query = uri.rawQuery.orEmpty()
                    val vParam = query.split('&')
                        .mapNotNull { kv ->
                            val idx = kv.indexOf('=')
                            if (idx > 0 && kv.substring(0, idx) == "v") kv.substring(idx + 1) else null
                        }
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (vParam.length in 6..20) return@runCatching "https://www.youtube.com/watch?v=$vParam"
                }
                val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
                if (segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "live")) {
                    return@runCatching "https://www.youtube.com/${segs[0]}/${segs[1]}"
                }
            }
            java.net.URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null).toString()
        }.getOrDefault(trimmed)
    }

    fun isCuratableDetailPage(rawUrl: String): Boolean {
        val clean = canonicalize(rawUrl)
        if (clean.isEmpty()) return false
        return runCatching {
            val uri = java.net.URI(clean)
            val host = (uri.host ?: "").lowercase()
            val path = (uri.path ?: "/").ifEmpty { "/" }
            val segs = path.trim('/').split('/').filter { it.isNotEmpty() }

            // Ignore localhost & LAN consoles
            if (host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.") || host.startsWith("10.")) {
                return@runCatching false
            }
            // Ignore auth, mail, studio & search engines
            if (
                host.contains("accounts.google.") ||
                host.startsWith("mail.google.") ||
                host.startsWith("aistudio.google.") ||
                (host.contains("google.") && (path == "/" || path.startsWith("/search") || path.startsWith("/url"))) ||
                (host.contains("baidu.com") && (path == "/" || path.startsWith("/s")))
            ) {
                return@runCatching false
            }
            // YouTube: ONLY watch / shorts / live video pages
            if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                if (path == "/watch" && (uri.rawQuery ?: "").contains("v=")) return@runCatching true
                if (segs.size >= 2 && (segs[0] == "shorts" || segs[0] == "live")) return@runCatching true
                return@runCatching false
            }
            // X / Twitter: ONLY status threads or longform articles
            if (host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")) {
                return@runCatching (segs.size >= 3 && segs[1] == "status") || (segs.size >= 2 && segs[0] == "i" && segs[1] == "articles")
            }
            // GitHub: repo or issue/PR/blob/discussion, skip root/trending/notifications
            if (host == "github.com") {
                val blockedFirst = setOf("trending", "notifications", "login", "settings", "search", "explore", "pulls", "issues")
                return@runCatching segs.size >= 2 && segs[0] !in blockedFirst
            }
            // Zhihu: question/answer or zhuanlan article
            if (host == "zhihu.com" || host.endsWith(".zhihu.com")) {
                return@runCatching path.startsWith("/question/") || path.startsWith("/p/")
            }
            // Reddit: specific post comments
            if (host == "reddit.com" || host.endsWith(".reddit.com")) {
                return@runCatching segs.contains("comments")
            }
            // Feishu / Notion workspace homepages
            if ((host.endsWith("feishu.cn") && path.startsWith("/drive/home")) || (host.endsWith("notion.so") && segs.isEmpty())) {
                return@runCatching false
            }
            // General websites/blogs/WeChat/Bilibili/Medium/ArXiv: require non-root content path
            if (segs.isEmpty()) return@runCatching false
            val firstLower = segs[0].lowercase()
            if (firstLower in setOf("login", "signin", "signup", "auth", "search", "feed", "explore", "home")) {
                return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppHubWorkspace(
    state: WebAppHubState,
    isForegroundVisible: Boolean = true,
    openAddDialogNonce: Long = 0L,
    isModalMode: Boolean = false,
    onDismissModal: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onSwitchToAssistant: () -> Unit = {},
    onAskSecretaryWithPage: (title: String, url: String, extractedBody: String) -> Unit,
    onSavePageToVault: (title: String, url: String, extractedBody: String) -> Unit = { _, _, _ -> },
    onCuratePageToKnowledgeBase: ((
        title: String,
        url: String,
        author: String,
        extractedBody: String,
        mode: String,
        onDone: (Boolean, String) -> Unit
    ) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = OmsTheme.colors
    val scope = rememberCoroutineScope()
    val app = OmsApplication.instance

    val macLanUrl = remember {
        val cfg = app?.repository?.getPairedMac()
        val ip = cfg?.lanIps?.firstOrNull()
        val port = cfg?.lanPort ?: 19527
        if (!ip.isNullOrBlank()) "http://$ip:$port/inbox" else null
    }

    var proxyMode by remember {
        mutableStateOf(app?.edgeStore?.getWebAppProxyMode() ?: "SMART")
    }
    var proxyStatusBanner by remember { mutableStateOf("正在初始化免 VPN 内置代理…") }
    var proxyActive by remember { mutableStateOf(false) }

    var autoCurateEnabled by remember {
        mutableStateOf(app?.edgeStore?.isBrowserAutoCurateEnabled() ?: false)
    }
    val autoCurateDwellSec = remember { app?.edgeStore?.getBrowserAutoCurateDwellSec() ?: 15 }
    var curatedUrls by remember {
        mutableStateOf(app?.edgeStore?.loadCuratedBrowserUrls() ?: emptySet())
    }
    var skippedSessionUrls by remember { mutableStateOf(setOf<String>()) }
    var dwellRemainingSec by remember { mutableIntStateOf(0) }
    var isCuratingNow by remember { mutableStateOf(false) }
    var curatingTargetUrl by remember { mutableStateOf("") }
    var justCuratedBanner by remember { mutableStateOf("") }
    var lastBackGestureMs by remember { mutableLongStateOf(0L) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showTabSwitcherSheet by remember { mutableStateOf(false) }
    var backQuickExitNonce by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.isBrowsing, isForegroundVisible) {
        if (!state.isBrowsing || !isForegroundVisible) {
            backQuickExitNonce = 0L
        }
    }

    LaunchedEffect(backQuickExitNonce) {
        if (backQuickExitNonce > 0L) {
            val captured = backQuickExitNonce
            delay(3600L)
            if (backQuickExitNonce == captured) backQuickExitNonce = 0L
        }
    }

    val canonicalActiveUrl = remember(state.activeUrl) {
        BrowserCompanionUrlJudge.canonicalize(state.activeUrl)
    }
    val isCurrentUrlCurated = remember(canonicalActiveUrl, curatedUrls) {
        canonicalActiveUrl.isNotBlank() && curatedUrls.contains(canonicalActiveUrl)
    }

    // Apply built-in proxy override immediately on enter & whenever proxyMode changes!
    LaunchedEffect(proxyMode) {
        WebViewProxyControllerBridge.applyMode(proxyMode) { active, msg ->
            proxyActive = active
            proxyStatusBanner = msg
        }
    }

    // Lightweight SPA URL & Title tracker (essential for YouTube mobile SPA `watch?v=...` switches!)
    LaunchedEffect(state.isBrowsing, isForegroundVisible, state.activeTabId) {
        while (state.isBrowsing && isForegroundVisible) {
            val wv = state.webViewRef
            if (wv != null) {
                val currentHref = wv.url?.trim().orEmpty()
                if (currentHref.isNotBlank() && currentHref != state.activeUrl) {
                    state.activeUrl = currentHref
                    state.addressInput = currentHref
                    state.lastLoadedUrl = currentHref
                    state.persistTabsSnapshot()
                }
                val currentTitle = wv.title?.trim().orEmpty()
                if (currentTitle.isNotBlank() && currentTitle != state.activeTitle) {
                    state.activeTitle = currentTitle
                }
            }
            delay(1100L)
        }
    }

    // Flush cookies reliably whenever Activity pauses or stops so Google/YouTube login sessions persist!
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                runCatching { CookieManager.getInstance().flush() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    var customApps by remember {
        mutableStateOf(app?.edgeStore?.loadPinnedWebApps() ?: emptyList())
    }
    val allApps = remember(customApps, macLanUrl) {
        BuiltinWebApps.defaultList(macLanUrl) + customApps
    }

    var customPlaybooks by remember {
        mutableStateOf(loadCustomPlaybooks(app?.edgeStore?.loadAutomationPlaybooksJson()))
    }
    val allPlaybooks = remember(customPlaybooks) {
        BuiltinWebApps.defaultPlaybooks() + customPlaybooks
    }

    var showAddAppDialog by remember { mutableStateOf(false) }
    var newAppName by remember { mutableStateOf("") }
    var newAppUrl by remember { mutableStateOf("") }

    var showAutomationSheet by remember { mutableStateOf(false) }
    var automationGoalInput by remember { mutableStateOf("") }
    var isAutomationRunning by remember { mutableStateOf(false) }
    val automationLogs = remember { mutableStateListOf<String>() }

    LaunchedEffect(openAddDialogNonce) {
        if (openAddDialogNonce > 0L) {
            newAppName = ""
            newAppUrl = "https://"
            showAddAppDialog = true
        }
    }

    fun normalizeUrlOrSearch(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(trimmed)}"
        }
    }

    fun extractPageAndDispatch(saveToVault: Boolean, captureMode: String = "manual") {
        val wv = state.webViewRef
        if (wv == null) {
            if (captureMode == "manual") {
                OmsFeedbackController.show("请先打开一个网页或视频", OmsToastKind.WARNING)
            }
            return
        }
        val targetCanonical = BrowserCompanionUrlJudge.canonicalize(wv.url ?: state.activeUrl)
        if (saveToVault && isCuratingNow && curatingTargetUrl == targetCanonical) return

        val js = """
            (function() {
              var title = document.title || '';
              var url = location.href || '';
              var author = '';
              var authorEl = document.querySelector('ytm-slim-owner-renderer .slim-owner-icon-and-title, #owner #channel-name, [itemprop="author"], meta[name="author"], [data-testid="User-Name"]');
              if (authorEl) {
                author = (authorEl.content || authorEl.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 80);
              }
              var metaDesc = '';
              var descMeta = document.querySelector('meta[name="description"], meta[property="og:description"]');
              if (descMeta && descMeta.content) metaDesc = descMeta.content.trim();
              var body = (document.body && document.body.innerText) ? document.body.innerText : '';
              body = body.replace(/\s+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, 16000);
              if (metaDesc && body.indexOf(metaDesc.slice(0, 40)) < 0) {
                body = '【页面简介】：' + metaDesc + '\n\n' + body;
              }
              return JSON.stringify({title: title, url: url, author: author, body: body});
            })();
        """.trimIndent()

        if (saveToVault) {
            isCuratingNow = true
            curatingTargetUrl = targetCanonical
            dwellRemainingSec = 0
        }

        wv.evaluateJavascript(js) { rawResult ->
            var t = state.activeTitle.ifBlank { state.activeUrl }
            var u = state.activeUrl
            var a = ""
            var b = ""
            runCatching {
                val unquoted = if (rawResult != null && rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                    JSONObject("{\"v\":$rawResult}").optString("v", "{}")
                } else rawResult ?: "{}"
                val obj = JSONObject(unquoted)
                t = obj.optString("title", t).ifBlank { u }
                u = obj.optString("url", u).ifBlank { state.activeUrl }
                a = obj.optString("author", "").trim()
                b = obj.optString("body", "").trim()
            }

            if (!saveToVault) {
                onAskSecretaryWithPage(t, u, b)
                return@evaluateJavascript
            }

            val canon = BrowserCompanionUrlJudge.canonicalize(u)
            if (onCuratePageToKnowledgeBase != null) {
                onCuratePageToKnowledgeBase(t, canon.ifBlank { u }, a, b, captureMode) { ok, msg ->
                    isCuratingNow = false
                    curatingTargetUrl = ""
                    if (ok) {
                        if (canon.isNotBlank()) {
                            curatedUrls = curatedUrls + canon
                        }
                        justCuratedBanner = msg
                        scope.launch {
                            delay(4200L)
                            if (justCuratedBanner == msg) justCuratedBanner = ""
                        }
                        if (captureMode == "manual") {
                            OmsFeedbackController.show(msg, OmsToastKind.SUCCESS)
                        }
                    } else if (captureMode == "manual") {
                        OmsFeedbackController.show(msg, OmsToastKind.WARNING)
                    }
                }
            } else {
                isCuratingNow = false
                curatingTargetUrl = ""
                onSavePageToVault(t, u, b)
            }
        }
    }

    // Smart Value-Dwell Auto-Curate Coroutine:
    // Only triggers when toggle is ON + URL is a real video/article detail page + not yet curated/skipped!
    LaunchedEffect(autoCurateEnabled, isForegroundVisible, state.isBrowsing, canonicalActiveUrl, isCurrentUrlCurated) {
        dwellRemainingSec = 0
        if (!autoCurateEnabled || !isForegroundVisible || !state.isBrowsing || canonicalActiveUrl.isBlank()) return@LaunchedEffect
        if (isCurrentUrlCurated || skippedSessionUrls.contains(canonicalActiveUrl)) return@LaunchedEffect
        if (!BrowserCompanionUrlJudge.isCuratableDetailPage(canonicalActiveUrl)) return@LaunchedEffect

        // Wait briefly for SPA title/DOM to settle
        delay(1500L)
        for (rem in autoCurateDwellSec downTo 1) {
            if (BrowserCompanionUrlJudge.canonicalize(state.activeUrl) != canonicalActiveUrl) {
                dwellRemainingSec = 0
                return@LaunchedEffect
            }
            if (skippedSessionUrls.contains(canonicalActiveUrl) || curatedUrls.contains(canonicalActiveUrl)) {
                dwellRemainingSec = 0
                return@LaunchedEffect
            }
            dwellRemainingSec = rem
            delay(1000L)
        }
        dwellRemainingSec = 0
        if (
            autoCurateEnabled &&
            state.isBrowsing &&
            BrowserCompanionUrlJudge.canonicalize(state.activeUrl) == canonicalActiveUrl &&
            !curatedUrls.contains(canonicalActiveUrl) &&
            !skippedSessionUrls.contains(canonicalActiveUrl)
        ) {
            extractPageAndDispatch(saveToVault = true, captureMode = "auto_dwell")
        }
    }

    fun runWebAndAppAutomation(goal: String, optionalStartUrl: String = "") {
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty() || isAutomationRunning) return
        val coordinator = app?.cockpitCoordinator ?: return
        isAutomationRunning = true
        automationLogs.clear()
        automationLogs.add("🎯 目标：$cleanGoal")

        scope.launch {
            try {
                if (optionalStartUrl.isNotBlank() && state.activeUrl != optionalStartUrl) {
                    automationLogs.add("🌐 正在打开页面：$optionalStartUrl")
                    state.openUrl(optionalStartUrl)
                    delay(2200L)
                }

                for (step in 1..4) {
                    val wv = state.webViewRef
                    if (wv == null) {
                        automationLogs.add("⚠️ 网页视图尚未就绪，等待加载…")
                        delay(1200L)
                        continue
                    }

                    val domSnapshotJs = """
                        (function() {
                          var els = Array.from(document.querySelectorAll('a[href], button, input, textarea, [role="button"]'));
                          var items = [];
                          for (var i = 0; i < els.length && items.length < 40; i++) {
                            var el = els[i];
                            var rect = el.getBoundingClientRect();
                            if (rect.width < 6 || rect.height < 6) continue;
                            el.setAttribute('data-oms-auto-id', String(items.length));
                            var txt = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').replace(/\s+/g, ' ').trim().slice(0, 60);
                            var tag = el.tagName.toLowerCase();
                            var type = el.getAttribute('type') || '';
                            if (!txt && tag !== 'input' && tag !== 'textarea') continue;
                            items.push({id: items.length, tag: tag, type: type, label: txt});
                          }
                          var bodyPreview = (document.body && document.body.innerText ? document.body.innerText : '').replace(/\s+/g, ' ').trim().slice(0, 2600);
                          return JSON.stringify({url: location.href, title: document.title, controls: items, textPreview: bodyPreview});
                        })();
                    """.trimIndent()

                    val snapRaw = evaluateJsSuspend(wv, domSnapshotJs)
                    val sysPrompt = """
                        你是应用舱内置的网页与手机自动化执行代理。请根据用户目标与当前网页可交互控件快照，决定下一步动作，严格只输出 JSON：
                        - 点击网页元素：`{"action":"click","id":3,"reason":"点击搜索按钮"}`
                        - 输入并可选回车：`{"action":"fill","id":1,"text":"搜索词","submit":true,"reason":"填写搜索框"}`
                        - 滚动页面：`{"action":"scroll","direction":"down","reason":"加载更多内容"}`
                        - 唤起手机原生对应 App：`{"action":"launch_native_app","app":"YouTube或小红书等","reason":"切换到原生客户端"}`
                        - 任务完成并输出提取/执行结果：`{"action":"done","result":"清晰的 Markdown 结果摘要"}`
                    """.trimIndent()

                    val llmResp = withContext(Dispatchers.IO) {
                        coordinator.llmEngine.completeSync(
                            intent = RouteIntent.AUTO,
                            developerPrompt = sysPrompt,
                            turns = listOf(
                                LlmTurnMessage(
                                    "user",
                                    "【用户自动化目标】：$cleanGoal\n【第 $step/4 步网页快照】：\n$snapRaw\n【已执行日志】：${automationLogs.joinToString(" -> ")}"
                                )
                            ),
                            jsonMode = true
                        )
                    }

                    val parsed = MiniJson.parseObject(MiniJson.extractJsonObject(llmResp.fullText))
                    val act = parsed["action"]?.toString()?.trim().orEmpty()
                    val reason = parsed["reason"]?.toString()?.trim().orEmpty()

                    when (act) {
                        "click" -> {
                            val id = (parsed["id"] as? Number)?.toInt() ?: parsed["id"]?.toString()?.toIntOrNull() ?: -1
                            automationLogs.add("👆 第 $step 步：点击 #$id ($reason)")
                            evaluateJsSuspend(
                                wv,
                                "(function(){ var el = document.querySelector('[data-oms-auto-id=\"$id\"]'); if(el){ el.scrollIntoView({block:'center'}); el.click(); return 'ok'; } return 'miss'; })();"
                            )
                            delay(1800L)
                        }
                        "fill" -> {
                            val id = (parsed["id"] as? Number)?.toInt() ?: parsed["id"]?.toString()?.toIntOrNull() ?: -1
                            val txt = parsed["text"]?.toString().orEmpty()
                            val submit = parsed["submit"] == true || parsed["submit"]?.toString() == "true"
                            val safeTxt = JSONObject.quote(txt)
                            automationLogs.add("⌨️ 第 $step 步：输入「$txt」($reason)")
                            evaluateJsSuspend(
                                wv,
                                """
                                (function(){
                                  var el = document.querySelector('[data-oms-auto-id="$id"]');
                                  if(!el) return 'miss';
                                  el.focus();
                                  el.value = $safeTxt;
                                  el.dispatchEvent(new Event('input', {bubbles: true}));
                                  el.dispatchEvent(new Event('change', {bubbles: true}));
                                  if ($submit) {
                                    if (el.form) el.form.submit();
                                    else el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true}));
                                  }
                                  return 'ok';
                                })();
                                """.trimIndent()
                            )
                            delay(2000L)
                        }
                        "scroll" -> {
                            val dir = parsed["direction"]?.toString() ?: "down"
                            val dy = if (dir == "up") -650 else 750
                            automationLogs.add("📜 第 $step 步：页面滚动 ($reason)")
                            evaluateJsSuspend(wv, "window.scrollBy({top: $dy, behavior: 'smooth'}); 'ok';")
                            delay(1400L)
                        }
                        "launch_native_app" -> {
                            val appName = parsed["app"]?.toString().orEmpty()
                            automationLogs.add("📱 正在唤起手机原生应用：$appName")
                            val r = PhoneToolExecutor.execute(
                                context,
                                ProposedPhoneToolAction(tool = "launch_app", title = appName, target = appName)
                            )
                            automationLogs.add(if (r.ok) "✅ ${r.message}" else "⚠️ ${r.message}")
                            break
                        }
                        "done" -> {
                            val resText = parsed["result"]?.toString()?.trim().orEmpty()
                            automationLogs.add("✅ 已完成：\n$resText")
                            break
                        }
                        else -> {
                            automationLogs.add("ℹ️ 执行结束：${llmResp.fullText.take(240)}")
                            break
                        }
                    }
                }
            } catch (err: Throwable) {
                automationLogs.add("❌ 自动化中断：${err.message}")
            } finally {
                isAutomationRunning = false
            }
        }
    }

    BackHandler(enabled = isForegroundVisible && (state.customFullscreenView != null || state.isBrowsing || isModalMode)) {
        val wv = state.webViewRef
        val now = System.currentTimeMillis()
        when {
            state.customFullscreenView != null -> state.exitFullscreenVideo()
            state.isBrowsing && wv != null && wv.canGoBack() -> {
                if (now - lastBackGestureMs in 1..900L) {
                    lastBackGestureMs = 0L
                    backQuickExitNonce = 0L
                    state.exitToDock()
                } else {
                    lastBackGestureMs = now
                    wv.goBack()
                    backQuickExitNonce = System.nanoTime()
                }
            }
            state.isBrowsing -> state.exitToDock()
            isModalMode -> onDismissModal()
        }
    }

    // Fullscreen Video Overlay (e.g. YouTube fullscreen player!)
    if (isForegroundVisible && state.customFullscreenView != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val v = state.customFullscreenView
                        if (v != null) {
                            (v.parent as? ViewGroup)?.removeView(v)
                            addView(
                                v,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    }
                }
            )
        }
        return
    }

    // Helper that creates or syncs live `TouchUnimpededWebView` children inside `multiTabHostFrame`
    // WITHOUT ever detaching open tabs when switching tabs or minimizing to Dock / Assistant!
    fun syncMultiTabFrame(frame: FrameLayout, ctx: android.content.Context) {
        val activeId = state.activeTab?.tabId
        val showBrowserNow = isForegroundVisible && state.isBrowsing
        frame.visibility = if (showBrowserNow) View.VISIBLE else View.GONE

        for (tab in state.tabs) {
            val isThisActive = showBrowserNow && tab.tabId == activeId
            var wv = tab.webViewRef
            if (wv == null && (isThisActive || tab.tabId == activeId)) {
                wv = TouchUnimpededWebView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Disable pinch-zoom gesture detector in mobile mode so YouTube progress-bar scrubbing has 0ms touch-slop!
                    settings.setSupportZoom(tab.desktopMode)
                    settings.builtInZoomControls = tab.desktopMode
                    settings.displayZoomControls = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = sanitizeStandardChromeUserAgent(
                        WebSettings.getDefaultUserAgent(ctx)
                    )
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            if (target.startsWith("http://") || target.startsWith("https://")) {
                                return false
                            }
                            runCatching {
                                val intent = android.content.Intent.parseUri(target, android.content.Intent.URI_INTENT_SCHEME)
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!url.isNullOrBlank()) {
                                tab.url = url
                                tab.lastLoadedUrl = url
                                if (state.activeTab?.tabId == tab.tabId && state.isBrowsing) {
                                    state.addressInput = url
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            if (!url.isNullOrBlank()) {
                                tab.url = url
                                tab.lastLoadedUrl = url
                                if (state.activeTab?.tabId == tab.tabId && state.isBrowsing) {
                                    state.addressInput = url
                                }
                                state.persistTabsSnapshot()
                            }
                            runCatching { CookieManager.getInstance().flush() }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) {
                                tab.url = url
                                tab.lastLoadedUrl = url
                                if (state.activeTab?.tabId == tab.tabId && state.isBrowsing) {
                                    state.addressInput = url
                                }
                            }
                            val t = view?.title?.trim().orEmpty()
                            if (t.isNotBlank()) {
                                tab.title = t
                            }
                            state.persistTabsSnapshot()
                            runCatching { CookieManager.getInstance().flush() }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            tab.loadProgress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                tab.title = title.trim()
                                state.persistTabsSnapshot()
                            }
                        }

                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view != null) {
                                state.customFullscreenView = view
                                state.customViewCallback = callback
                            }
                        }

                        override fun onHideCustomView() {
                            state.exitFullscreenVideo()
                        }
                    }
                }
                tab.webViewRef = wv
            }

            if (wv != null) {
                if (wv.parent !== frame) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    frame.addView(wv)
                }
                val desktopUa = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                val mobileUa = sanitizeStandardChromeUserAgent(WebSettings.getDefaultUserAgent(wv.context))
                val expectedUa = if (tab.desktopMode) desktopUa else mobileUa
                if (wv.settings.userAgentString != expectedUa) {
                    wv.settings.userAgentString = expectedUa
                }
                if (wv.settings.builtInZoomControls != tab.desktopMode) {
                    wv.settings.setSupportZoom(tab.desktopMode)
                    wv.settings.builtInZoomControls = tab.desktopMode
                }
                if (tab.lastLoadedUrl != tab.url && tab.url.isNotBlank()) {
                    tab.lastLoadedUrl = tab.url
                    wv.loadUrl(tab.url)
                }
                wv.visibility = if (isThisActive) View.VISIBLE else View.GONE
            }
        }
    }

    Column(
        modifier = if (isForegroundVisible) {
            Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .then(if (isModalMode) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
        } else {
            // Keep WebView host alive in background with 0dp footprint when user jumps out to Assistant/Chisu/Mission!
            Modifier.size(0.dp)
        }
    ) {
        if (isForegroundVisible) {
            // Rock-Steady Ultra-Slim Top Bar (never shifts height mid-scroll -> 100% smooth touch & video seekbar!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (state.isBrowsing || isModalMode) palette.topBar else palette.canvas)
                    .padding(horizontal = 10.dp, vertical = if (state.isBrowsing) 5.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (state.isBrowsing && !isModalMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .height(31.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.accentSoft)
                            .clickable { onSwitchToAssistant() }
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = OmsIcons.MessageCircle,
                            contentDescription = "临时切回随身助理（保留当前网页标签）",
                            tint = palette.accent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "助理",
                            color = palette.accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (state.isBrowsing || isModalMode) {
                    OmsCircleIconButton(
                        icon = if (state.isBrowsing) OmsIcons.LayoutGrid else OmsIcons.Close,
                        contentDescription = if (state.isBrowsing) "回应用舱首页（保留标签）" else "关闭应用舱",
                        size = 31.dp,
                        iconSize = 15.dp,
                        containerColor = palette.subtleSurface,
                        onClick = {
                            if (state.isBrowsing) {
                                state.exitToDock()
                            } else {
                                onDismissModal()
                            }
                        }
                    )
                }

                // URL / Google Search Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (state.isBrowsing) 32.dp else 37.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.subtleSurface)
                        .border(0.6.dp, palette.hairline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 10.dp)
                ) {
                    Icon(
                        OmsIcons.Globe,
                        contentDescription = null,
                        tint = if (proxyActive) palette.success else palette.textTertiary,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (state.addressInput.isEmpty()) {
                            Text("输入网址或免 VPN Google 搜索…", color = palette.textTertiary, fontSize = 12.2.sp, maxLines = 1)
                        }
                        BasicTextField(
                            value = state.addressInput,
                            onValueChange = { state.addressInput = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 12.5.sp),
                            cursorBrush = SolidColor(palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val target = normalizeUrlOrSearch(state.addressInput)
                                    if (target.isNotBlank()) {
                                        state.openUrl(target, forceNewTab = !state.isBrowsing)
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.isBrowsing) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Refresh,
                            contentDescription = "刷新网页",
                            size = 21.dp,
                            iconSize = 12.5.dp,
                            contentColor = palette.textSecondary,
                            onClick = { state.webViewRef?.reload() }
                        )
                    } else if (state.addressInput.isNotEmpty()) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Close,
                            contentDescription = "清空",
                            size = 22.dp,
                            iconSize = 12.dp,
                            contentColor = palette.textTertiary,
                            onClick = { state.addressInput = "" }
                        )
                    }
                }

                // Multi-Tab Switcher Badge (`[2]`) whenever >= 1 tab is alive
                if (state.tabs.isNotEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(31.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.subtleSurface)
                            .border(0.8.dp, palette.accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .clickable { showTabSwitcherSheet = true }
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "${state.tabs.size}",
                            color = palette.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (state.isBrowsing) {
                    OmsTogglePill(
                        label = if (autoCurateEnabled) "伴读✓" else "伴读",
                        active = autoCurateEnabled,
                        onClick = {
                            val next = !autoCurateEnabled
                            autoCurateEnabled = next
                            app?.edgeStore?.setBrowserAutoCurateEnabled(next)
                            OmsFeedbackController.show(
                                if (next) "已开启伴读自动入库（停留满 ${autoCurateDwellSec}s 自动提炼同步全端 AI）"
                                else "已关闭伴读自动入库",
                                OmsToastKind.INFO
                            )
                        }
                    )
                    Box {
                        OmsCircleIconButton(
                            icon = OmsIcons.MoreHorizontal,
                            contentDescription = "网页伴读与标签菜单",
                            size = 31.dp,
                            iconSize = 15.5.dp,
                            containerColor = palette.subtleSurface,
                            onClick = { showMoreMenu = true }
                        )
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            modifier = Modifier.background(palette.composer)
                        ) {
                            DropdownMenuItem(
                                text = { Text("✦ 问助理（提炼当前页）", color = palette.textPrimary, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    extractPageAndDispatch(saveToVault = false)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isCurrentUrlCurated) "✓ 已入知识库（再次整理）" else "📥 立即整理入知识库",
                                        color = palette.textPrimary,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    extractPageAndDispatch(saveToVault = true, captureMode = "manual")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("⚡ AI 网页与跨应用自动化", color = palette.textPrimary, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    showAutomationSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.desktopMode) "📱 切回手机版网页视图" else "🖥 切换桌面版网页视图",
                                        color = palette.textPrimary,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    state.desktopMode = !state.desktopMode
                                    state.webViewRef?.reload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📌 固定当前网页到应用舱", color = palette.textPrimary, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    val u = state.activeUrl.trim()
                                    val n = state.activeTitle.ifBlank { u }.take(24)
                                    if (u.isNotBlank() && allApps.none { it.url == u }) {
                                        val next = customApps + PinnedWebAppItem(
                                            id = "web_${System.currentTimeMillis()}",
                                            name = n,
                                            url = u,
                                            tag = "已收藏"
                                        )
                                        customApps = next
                                        app?.edgeStore?.savePinnedWebApps(next)
                                        OmsFeedbackController.show("已将「$n」固定到应用舱", OmsToastKind.SUCCESS)
                                    } else {
                                        OmsFeedbackController.show("该网页已在应用舱中", OmsToastKind.INFO)
                                    }
                                }
                            )
                            if (!isModalMode) {
                                DropdownMenuItem(
                                    text = { Text("☰ 打开侧边栏（历史对话/搜索）", color = palette.textPrimary, fontSize = 13.sp) },
                                    onClick = {
                                        showMoreMenu = false
                                        onOpenDrawer()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("✕ 显式关闭当前标签页", color = palette.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                                onClick = {
                                    showMoreMenu = false
                                    state.activeTab?.tabId?.let { state.closeTab(it) }
                                }
                            )
                        }
                    }
                } else {
                    OmsCircleIconButton(
                        icon = OmsIcons.Plus,
                        contentDescription = "添加网页应用",
                        size = 36.dp,
                        iconSize = 17.dp,
                        containerColor = palette.accentSoft,
                        contentColor = palette.accent,
                        onClick = {
                            newAppName = ""
                            newAppUrl = "https://"
                            showAddAppDialog = true
                        }
                    )
                }
            }

            if (state.isBrowsing && state.loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { state.loadProgress / 100f },
                    color = palette.accent,
                    trackColor = palette.hairline,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else if (state.isBrowsing) {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
            }
        }

        // Unified Stage Box: keeps `multiTabHostFrame` ALWAYS mounted so open tabs never detach or lose playback!
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isForegroundVisible && state.isBrowsing && !isModalMode) Modifier.navigationBarsPadding() else Modifier)
        ) {
            if (state.hasOpenTabs) {
                val activeIdKey = state.activeTab?.tabId
                val activeUrlKey = state.activeUrl
                val activeDesktopKey = state.desktopMode
                val tabsCountKey = state.tabs.size
                AndroidView(
                    modifier = if (isForegroundVisible && state.isBrowsing) Modifier.fillMaxSize() else Modifier.size(0.dp),
                    factory = { ctx ->
                        val frame = state.multiTabHostFrame ?: FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        (frame.parent as? ViewGroup)?.removeView(frame)
                        state.multiTabHostFrame = frame
                        syncMultiTabFrame(frame, ctx)
                        frame
                    },
                    update = { frame ->
                        // Read reactive keys so Compose triggers `update` whenever tabs/visibility/URL change
                        val _keepAlive = "$activeIdKey|$activeUrlKey|$activeDesktopKey|$tabsCountKey|$isForegroundVisible|${state.isBrowsing}"
                        if (_keepAlive.isNotEmpty()) {
                            syncMultiTabFrame(frame, frame.context)
                        }
                    }
                )
            }

            if (isForegroundVisible && !state.isBrowsing) {
                // App Hub Dock: Open Tabs Resume Strip + Proxy & Companion Card + App Grid
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Preserved Unclosed Browser Tabs Strip (Resume in 0ms or Explicitly Close with ✕!)
                    if (state.tabs.isNotEmpty()) {
                        OmsSurfaceCard(
                            cornerRadius = 15.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "正在保留的标签页 (${state.tabs.size} · 点击继续观看)",
                                    color = palette.textPrimary,
                                    fontSize = 12.2.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "管理 / 全部关闭",
                                    color = palette.accent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { showTabSwitcherSheet = true }
                                )
                            }
                            Spacer(modifier = Modifier.height(7.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                state.tabs.forEach { tab ->
                                    val isYt = tab.url.contains("youtube.com") || tab.url.contains("youtu.be")
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(palette.subtleSurface)
                                            .border(
                                                0.7.dp,
                                                if (tab.tabId == state.activeTabId) palette.accent.copy(alpha = 0.6f) else palette.hairline,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable { state.resumeTab(tab.tabId) }
                                            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isYt) OmsIcons.Video else OmsIcons.Globe,
                                            contentDescription = null,
                                            tint = palette.accent,
                                            modifier = Modifier.size(13.5.dp)
                                        )
                                        Text(
                                            text = tab.title.ifBlank { tab.url }.take(20),
                                            color = palette.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        OmsCircleIconButton(
                                            icon = OmsIcons.Close,
                                            contentDescription = "显式关闭此标签",
                                            size = 22.dp,
                                            iconSize = 11.5.dp,
                                            contentColor = palette.textTertiary,
                                            onClick = { state.closeTab(tab.tabId) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Built-in VPN-Free Proxy Status & Auto-Curate Companion Card
                    OmsSurfaceCard(
                        cornerRadius = 15.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (proxyActive) palette.success else palette.warning)
                                )
                                Text(
                                    text = proxyStatusBanner,
                                    color = palette.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OmsSecondaryButton(
                                label = "自动化",
                                icon = OmsIcons.Bolt,
                                height = 28.dp,
                                onClick = { showAutomationSheet = true }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OmsTogglePill(
                                label = "智能免VPN分流",
                                active = proxyMode == "SMART",
                                onClick = {
                                    proxyMode = "SMART"
                                    app?.edgeStore?.setWebAppProxyMode("SMART")
                                }
                            )
                            OmsTogglePill(
                                label = "全局走代理",
                                active = proxyMode == "GLOBAL",
                                onClick = {
                                    proxyMode = "GLOBAL"
                                    app?.edgeStore?.setWebAppProxyMode("GLOBAL")
                                }
                            )
                            OmsTogglePill(
                                label = "仅直连",
                                active = proxyMode == "DIRECT",
                                onClick = {
                                    proxyMode = "DIRECT"
                                    app?.edgeStore?.setWebAppProxyMode("DIRECT")
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(9.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(
                                        imageVector = OmsIcons.BookOpen,
                                        contentDescription = null,
                                        tint = if (autoCurateEnabled) palette.accent else palette.textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "伴读自动整理入知识库",
                                        color = palette.textPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = if (autoCurateEnabled) "已开启：浏览 YouTube 视频或文章满 ${autoCurateDwellSec}s 自动提炼入库，全端 AI 上下文实时一致"
                                    else "开启后，驻留观看有价值的视频/文章将自动提炼入知识库并同步全端 AI",
                                    color = palette.textTertiary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            OmsTogglePill(
                                label = if (autoCurateEnabled) "已开启" else "未开启",
                                active = autoCurateEnabled,
                                onClick = {
                                    val next = !autoCurateEnabled
                                    autoCurateEnabled = next
                                    app?.edgeStore?.setBrowserAutoCurateEnabled(next)
                                }
                            )
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = allApps, key = { it.id }) { item ->
                            val hasAliveSameHost = state.tabs.any {
                                val h = extractRegistrableHostKey(item.url)
                                h.isNotEmpty() && extractRegistrableHostKey(it.url) == h
                            }
                            OmsSurfaceCard(
                                cornerRadius = 16.dp,
                                contentPadding = PaddingValues(13.dp),
                                onClick = { state.openUrl(item.url, item.name) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.accentSoft)
                                    ) {
                                        Icon(
                                            imageVector = if (item.id == "youtube") OmsIcons.Video else OmsIcons.Globe,
                                            contentDescription = null,
                                            tint = palette.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    if (hasAliveSameHost) {
                                        Text(
                                            text = "继续看",
                                            color = palette.success,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(palette.success.copy(alpha = 0.14f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    } else if (!item.isBuiltin) {
                                        OmsCircleIconButton(
                                            icon = OmsIcons.Trash,
                                            contentDescription = "移除",
                                            size = 24.dp,
                                            iconSize = 13.dp,
                                            contentColor = palette.textTertiary,
                                            onClick = {
                                                val updated = customApps.filterNot { it.id == item.id }
                                                customApps = updated
                                                app?.edgeStore?.savePinnedWebApps(updated)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = item.name,
                                    color = palette.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (hasAliveSameHost) "标签页保留中 · 点击无缝续看" else item.tag,
                                    color = if (hasAliveSameHost) palette.accent else palette.textTertiary,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Floating Overlay Pills when actively browsing (0dp layout space!)
            if (isForegroundVisible && state.isBrowsing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (isCuratingNow || dwellRemainingSec > 0 || justCuratedBanner.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(palette.composer.copy(alpha = 0.95f))
                                .border(0.8.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            when {
                                isCuratingNow -> {
                                    CircularProgressIndicator(
                                        strokeWidth = 1.8.dp,
                                        color = palette.accent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "助理正在整理内容并同步知识库…",
                                        color = palette.textPrimary,
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                dwellRemainingSec > 0 -> {
                                    Icon(
                                        imageVector = OmsIcons.BookOpen,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(13.5.dp)
                                    )
                                    Text(
                                        text = "伴读感知 · ${dwellRemainingSec}s 后自动入库",
                                        color = palette.textPrimary,
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "立即入库",
                                        color = palette.accent,
                                        fontSize = 11.8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            extractPageAndDispatch(saveToVault = true, captureMode = "manual")
                                        }
                                    )
                                    Text(
                                        text = "跳过",
                                        color = palette.textTertiary,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.clickable {
                                            if (canonicalActiveUrl.isNotBlank()) {
                                                skippedSessionUrls = skippedSessionUrls + canonicalActiveUrl
                                            }
                                            dwellRemainingSec = 0
                                        }
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = OmsIcons.Check,
                                        contentDescription = null,
                                        tint = palette.success,
                                        modifier = Modifier.size(13.5.dp)
                                    )
                                    Text(
                                        text = justCuratedBanner,
                                        color = palette.textPrimary,
                                        fontSize = 11.8.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = backQuickExitNonce > 0L,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(150))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(palette.composer.copy(alpha = 0.96f))
                                .border(0.7.dp, palette.hairline, RoundedCornerShape(20.dp))
                                .padding(horizontal = 13.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "← 已后退",
                                color = palette.textTertiary,
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = "收起回舱首页",
                                color = palette.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    backQuickExitNonce = 0L
                                    state.exitToDock()
                                }
                            )
                            if (!isModalMode) {
                                Text(
                                    text = "切回助理",
                                    color = palette.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable {
                                        backQuickExitNonce = 0L
                                        onSwitchToAssistant()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Multi-Tab Switcher Bottom Sheet (`[N]`) — Switch Tabs in 0ms, Open New Tab, or Explicitly Close (`✕`)!
    if (showTabSwitcherSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabSwitcherSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "已保留的网页标签 (${state.tabs.size})",
                            color = palette.textPrimary,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "不点 ✕ 关闭将持续保留观看进度，随时无缝切回续看",
                            color = palette.textTertiary,
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OmsSecondaryButton(
                            label = "新标签",
                            icon = OmsIcons.Plus,
                            height = 30.dp,
                            onClick = {
                                showTabSwitcherSheet = false
                                state.openUrl("https://www.google.com", "Google 搜索", forceNewTab = true)
                            }
                        )
                        if (state.tabs.isNotEmpty()) {
                            OmsSecondaryButton(
                                label = "全部关闭",
                                icon = OmsIcons.Trash,
                                height = 30.dp,
                                onClick = {
                                    state.closeAllTabs()
                                    showTabSwitcherSheet = false
                                }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = state.tabs, key = { it.tabId }) { tab ->
                        val isCurrent = tab.tabId == state.activeTab?.tabId && state.isBrowsing
                        OmsSurfaceCard(
                            cornerRadius = 14.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            onClick = {
                                state.resumeTab(tab.tabId)
                                showTabSwitcherSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = (if (isCurrent) "● " else "") + tab.title.ifBlank { tab.url },
                                        color = if (isCurrent) palette.accent else palette.textPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = tab.url,
                                        color = palette.textTertiary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OmsCircleIconButton(
                                    icon = OmsIcons.Close,
                                    contentDescription = "显式关闭标签页",
                                    size = 28.dp,
                                    iconSize = 14.dp,
                                    containerColor = palette.subtleSurface,
                                    contentColor = palette.textSecondary,
                                    onClick = {
                                        state.closeTab(tab.tabId)
                                        if (state.tabs.isEmpty()) showTabSwitcherSheet = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    // Web DOM & Native App Automation Half-Sheet
    if (showAutomationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAutomationSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.composer,
            contentColor = palette.textPrimary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("应用舱 AI 自动化操控", color = palette.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (state.isBrowsing) "基于已登录状态自动点击、输入、翻页提取或衔接原生 App" else "选择预设自动化配方，或输入任意跨网页/App 动作指令",
                            color = palette.textTertiary,
                            fontSize = 12.sp
                        )
                    }
                    if (isAutomationRunning) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = palette.accent, modifier = Modifier.size(18.dp))
                    }
                }

                // Input Goal Box + Run / Save Playbook
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 11.dp)
                    ) {
                        if (automationGoalInput.isEmpty()) {
                            Text(
                                text = "例如：搜索 Claude MCP 并提取前 5 条视频标题…",
                                color = palette.textTertiary,
                                fontSize = 13.sp
                            )
                        }
                        BasicTextField(
                            value = automationGoalInput,
                            onValueChange = { automationGoalInput = it },
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OmsPrimaryButton(
                        label = if (isAutomationRunning) "执行中" else "执行",
                        icon = OmsIcons.Bolt,
                        enabled = !isAutomationRunning && automationGoalInput.isNotBlank(),
                        height = 40.dp,
                        onClick = { runWebAndAppAutomation(automationGoalInput) }
                    )
                }

                if (automationGoalInput.isNotBlank() && state.activeUrl.isNotBlank()) {
                    Text(
                        text = "＋ 将此指令保存为常用自动化配方",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val item = AutomationPlaybookItem(
                                id = "pb_${System.currentTimeMillis()}",
                                name = automationGoalInput.take(18),
                                startUrl = state.activeUrl,
                                instruction = automationGoalInput.trim()
                            )
                            val updated = customPlaybooks + item
                            customPlaybooks = updated
                            app?.edgeStore?.saveAutomationPlaybooksJson(serializeCustomPlaybooks(updated))
                            OmsFeedbackController.show("已保存自动化配方", OmsToastKind.SUCCESS)
                        }
                    )
                }

                if (automationLogs.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(automationLogs) { line ->
                            Text(line, color = palette.textPrimary, fontSize = 12.2.sp, lineHeight = 18.sp)
                        }
                    }
                }

                Text("一键自动化配方", color = palette.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(items = allPlaybooks, key = { it.id }) { pb ->
                        OmsSurfaceCard(
                            cornerRadius = 13.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            onClick = {
                                automationGoalInput = pb.instruction
                                runWebAndAppAutomation(pb.instruction, pb.startUrl)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pb.name, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                if (!pb.isBuiltin) {
                                    OmsCircleIconButton(
                                        icon = OmsIcons.Trash,
                                        contentDescription = "删除配方",
                                        size = 24.dp,
                                        iconSize = 12.5.dp,
                                        onClick = {
                                            val updated = customPlaybooks.filterNot { it.id == pb.id }
                                            customPlaybooks = updated
                                            app?.edgeStore?.saveAutomationPlaybooksJson(serializeCustomPlaybooks(updated))
                                        }
                                    )
                                }
                            }
                            Text(pb.instruction, color = palette.textTertiary, fontSize = 11.8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    if (showAddAppDialog) {
        AlertDialog(
            onDismissRequest = { showAddAppDialog = false },
            containerColor = palette.composer,
            title = { Text("添加自定义网页应用", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(11.dp)
                    ) {
                        if (newAppName.isEmpty()) Text("名称（如：YouTube Studio / Linear / 内部系统）", color = palette.textTertiary, fontSize = 13.sp)
                        BasicTextField(
                            value = newAppName,
                            onValueChange = { newAppName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(11.dp)
                    ) {
                        if (newAppUrl.isEmpty()) Text("https://...", color = palette.textTertiary, fontSize = 13.sp)
                        BasicTextField(
                            value = newAppUrl,
                            onValueChange = { newAppUrl = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.5.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = normalizeUrlOrSearch(newAppUrl)
                    val n = newAppName.trim().ifBlank { u.removePrefix("https://").removePrefix("http://").substringBefore("/") }
                    if (u.isNotBlank()) {
                        val next = customApps + PinnedWebAppItem(
                            id = "web_${System.currentTimeMillis()}",
                            name = n,
                            url = u,
                            tag = "自定义应用"
                        )
                        customApps = next
                        app?.edgeStore?.savePinnedWebApps(next)
                        showAddAppDialog = false
                    }
                }) { Text("添加", color = palette.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddAppDialog = false }) { Text("取消", color = palette.textTertiary) }
            }
        )
    }
}

private fun loadCustomPlaybooks(rawJson: String?): List<AutomationPlaybookItem> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(rawJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val inst = o.optString("instruction", "").trim()
            if (inst.isEmpty()) return@mapNotNull null
            AutomationPlaybookItem(
                id = o.optString("id", "pb_$i"),
                name = o.optString("name", inst.take(18)),
                startUrl = o.optString("startUrl", ""),
                instruction = inst,
                isBuiltin = false
            )
        }
    }.getOrDefault(emptyList())
}

private fun serializeCustomPlaybooks(items: List<AutomationPlaybookItem>): String {
    val arr = JSONArray()
    items.filterNot { it.isBuiltin }.take(20).forEach { item ->
        arr.put(
            JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("startUrl", item.startUrl)
                .put("instruction", item.instruction)
        )
    }
    return arr.toString()
}

@Composable
fun WebAppHubModal(
    initialUrl: String? = null,
    initialTitle: String? = null,
    onDismiss: () -> Unit,
    onAskSecretaryWithPage: (title: String, url: String, extractedBody: String) -> Unit,
    onSavePageToVault: (title: String, url: String, extractedBody: String) -> Unit
) {
    val state = remember(initialUrl, initialTitle) {
        WebAppHubState(
            initialUrl = initialUrl?.trim().orEmpty(),
            initialTitle = initialTitle?.trim().orEmpty()
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        WebAppHubWorkspace(
            state = state,
            isModalMode = true,
            onDismissModal = onDismiss,
            onAskSecretaryWithPage = onAskSecretaryWithPage,
            onSavePageToVault = onSavePageToVault
        )
    }
}
