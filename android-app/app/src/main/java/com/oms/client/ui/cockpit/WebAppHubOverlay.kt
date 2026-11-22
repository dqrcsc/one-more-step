package com.oms.client.ui.cockpit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oms.client.OmsApplication
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import org.json.JSONObject

data class PinnedWebAppItem(
    val id: String,
    val name: String,
    val url: String,
    val tag: String = "网页应用",
    val isBuiltin: Boolean = false
)

object BuiltinWebApps {
    fun defaultList(macLanUrl: String? = null): List<PinnedWebAppItem> {
        val list = mutableListOf(
            PinnedWebAppItem("github", "GitHub", "https://github.com", "代码仓库", true),
            PinnedWebAppItem("aistudio", "AI Studio", "https://aistudio.google.com", "模型工坊", true),
            PinnedWebAppItem("gmail", "Gmail", "https://mail.google.com", "邮箱", true),
            PinnedWebAppItem("feishu", "飞书文档", "https://www.feishu.cn/drive/home/", "协作文档", true),
            PinnedWebAppItem("notion", "Notion", "https://www.notion.so", "笔记协作", true),
            PinnedWebAppItem("zhihu", "知乎", "https://www.zhihu.com", "深度问答", true),
            PinnedWebAppItem("eastmoney", "财经行情", "https://wap.eastmoney.com", "实时市场", true),
            PinnedWebAppItem("google", "Google 搜索", "https://www.google.com", "全网检索", true)
        )
        if (!macLanUrl.isNullOrBlank()) {
            list.add(0, PinnedWebAppItem("mac_console", "Mac 咫尺总控", macLanUrl, "局域网直连", true))
        }
        return list
    }
}

/**
 * Persistent state holder for Bottom Tab 4 (`应用舱`), preserving active URL and live WebView
 * across top-level tab switches without destroying login sessions or scroll position.
 */
@Stable
class WebAppHubState(
    initialUrl: String = "",
    initialTitle: String = ""
) {
    var activeUrl by mutableStateOf(initialUrl)
    var activeTitle by mutableStateOf(initialTitle)
    var addressInput by mutableStateOf(initialUrl)
    var loadProgress by mutableIntStateOf(0)
    var desktopMode by mutableStateOf(false)
    var webViewRef: WebView? = null
    var lastLoadedUrl: String = ""

    val isBrowsing: Boolean
        get() = activeUrl.isNotBlank()

    fun openUrl(url: String, title: String = "") {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        activeUrl = trimmed
        addressInput = trimmed
        if (title.isNotBlank()) activeTitle = title.trim()
        val wv = webViewRef
        if (wv != null && lastLoadedUrl != trimmed) {
            lastLoadedUrl = trimmed
            wv.loadUrl(trimmed)
        }
    }

    fun exitToDock() {
        activeUrl = ""
        addressInput = ""
        loadProgress = 0
    }

    fun destroyWebView() {
        val wv = webViewRef ?: return
        webViewRef = null
        lastLoadedUrl = ""
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
    }
}

/**
 * First-class Bottom Tab Workspace (`应用舱`):
 * - Dock Mode (`!state.isBrowsing`): shows omnibar + pinned Web App grid with Bottom Tab Bar visible.
 * - Immersive Browser Mode (`state.isBrowsing`): hides Bottom Tab Bar & Shell Top Bar, keeps live WebView
 *   plus 1-tap AI Page-Aware Companion (`问秘书` / `存入知识库` / `收藏到舱`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppHubWorkspace(
    state: WebAppHubState,
    openAddDialogNonce: Long = 0L,
    isModalMode: Boolean = false,
    onDismissModal: () -> Unit = {},
    onAskSecretaryWithPage: (title: String, url: String, extractedBody: String) -> Unit,
    onSavePageToVault: (title: String, url: String, extractedBody: String) -> Unit
) {
    val palette = OmsTheme.colors
    val app = OmsApplication.instance
    val macLanUrl = remember {
        val cfg = app?.repository?.getPairedMac()
        val ip = cfg?.lanIps?.firstOrNull()
        val port = cfg?.lanPort ?: 19527
        if (!ip.isNullOrBlank()) "http://$ip:$port/inbox" else null
    }

    var customApps by remember {
        mutableStateOf(app?.edgeStore?.loadPinnedWebApps() ?: emptyList())
    }
    val allApps = remember(customApps, macLanUrl) {
        BuiltinWebApps.defaultList(macLanUrl) + customApps
    }

    var showAddAppDialog by remember { mutableStateOf(false) }
    var newAppName by remember { mutableStateOf("") }
    var newAppUrl by remember { mutableStateOf("") }

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

    fun extractPageAndDispatch(saveToVault: Boolean) {
        val wv = state.webViewRef
        if (wv == null) {
            OmsFeedbackController.show("请先打开一个网页应用", OmsToastKind.WARNING)
            return
        }
        val js = """
            (function() {
              var title = document.title || '';
              var url = location.href || '';
              var body = (document.body && document.body.innerText) ? document.body.innerText : '';
              body = body.replace(/\s+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, 6000);
              return JSON.stringify({title: title, url: url, body: body});
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { rawResult ->
            runCatching {
                val unquoted = if (rawResult != null && rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                    JSONObject("{\"v\":$rawResult}").optString("v", "{}")
                } else rawResult ?: "{}"
                val obj = JSONObject(unquoted)
                val t = obj.optString("title", state.activeTitle).ifBlank { state.activeUrl }
                val u = obj.optString("url", state.activeUrl)
                val b = obj.optString("body", "").trim()
                if (saveToVault) {
                    onSavePageToVault(t, u, b)
                } else {
                    onAskSecretaryWithPage(t, u, b)
                }
            }.onFailure {
                if (saveToVault) onSavePageToVault(state.activeTitle.ifBlank { state.activeUrl }, state.activeUrl, "")
                else onAskSecretaryWithPage(state.activeTitle.ifBlank { state.activeUrl }, state.activeUrl, "")
            }
        }
    }

    BackHandler(enabled = state.isBrowsing || isModalMode) {
        val wv = state.webViewRef
        when {
            state.isBrowsing && wv != null && wv.canGoBack() -> wv.goBack()
            state.isBrowsing -> state.exitToDock()
            isModalMode -> onDismissModal()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .then(if (isModalMode) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
    ) {
        // Top Compact Omnibar Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (state.isBrowsing || isModalMode) palette.topBar else palette.canvas)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (state.isBrowsing || isModalMode) {
                OmsCircleIconButton(
                    icon = if (state.isBrowsing) OmsIcons.ArrowLeft else OmsIcons.Close,
                    contentDescription = if (state.isBrowsing) "返回应用舱" else "关闭应用舱",
                    size = 34.dp,
                    iconSize = 17.dp,
                    onClick = {
                        if (state.isBrowsing) {
                            state.exitToDock()
                        } else {
                            onDismissModal()
                        }
                    }
                )
            }

            // URL / Search Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(palette.subtleSurface)
                    .border(0.6.dp, palette.hairline, RoundedCornerShape(19.dp))
                    .padding(horizontal = 12.dp)
            ) {
                Icon(OmsIcons.Globe, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.5.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (state.addressInput.isEmpty()) {
                        Text("输入网址、网页应用或直接搜索…", color = palette.textTertiary, fontSize = 12.8.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = state.addressInput,
                        onValueChange = { state.addressInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = normalizeUrlOrSearch(state.addressInput)
                                if (target.isNotBlank()) {
                                    state.openUrl(target)
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.addressInput.isNotEmpty()) {
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

            if (state.isBrowsing) {
                OmsPrimaryButton(
                    label = "问秘书",
                    icon = OmsIcons.Sparkles,
                    height = 34.dp,
                    onClick = { extractPageAndDispatch(saveToVault = false) }
                )
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

        if (!state.isBrowsing) {
            // App Hub Dock Grid
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "常用网页版工具留在 App 内常驻切换，任意页面点右上角「问秘书」即可带全文追问或入库。",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(items = allApps, key = { it.id }) { item ->
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
                                    Icon(OmsIcons.Globe, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                                }
                                if (!item.isBuiltin) {
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
                                text = item.tag,
                                color = palette.textTertiary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            // Active Persistent Embedded WebView (Reuses live WebView across tab switches!)
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    val existing = state.webViewRef
                    val wv = if (existing != null) {
                        (existing.parent as? ViewGroup)?.removeView(existing)
                        existing
                    } else {
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            return !(target.startsWith("http://") || target.startsWith("https://"))
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!url.isNullOrBlank()) {
                                state.addressInput = url
                                state.activeUrl = url
                                state.lastLoadedUrl = url
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) {
                                state.addressInput = url
                                state.activeUrl = url
                                state.lastLoadedUrl = url
                            }
                            val t = view?.title?.trim().orEmpty()
                            if (t.isNotBlank()) state.activeTitle = t
                        }
                    }
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            state.loadProgress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) state.activeTitle = title
                        }
                    }
                    state.webViewRef = wv
                    if (state.lastLoadedUrl != state.activeUrl && state.activeUrl.isNotBlank()) {
                        state.lastLoadedUrl = state.activeUrl
                        wv.loadUrl(state.activeUrl)
                    }
                    wv
                },
                update = { wv ->
                    state.webViewRef = wv
                    val targetUa = if (state.desktopMode) {
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    } else null
                    if (wv.settings.userAgentString != targetUa && state.desktopMode) {
                        wv.settings.userAgentString = targetUa
                    } else if (!state.desktopMode && wv.settings.userAgentString.contains("Macintosh")) {
                        wv.settings.userAgentString = WebSettings.getDefaultUserAgent(wv.context)
                    }
                }
            )

            // Compact Bottom Browser & Secretary Toolbar
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .then(if (!isModalMode) Modifier.navigationBarsPadding() else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OmsCircleIconButton(
                        icon = OmsIcons.ArrowLeft,
                        contentDescription = "后退",
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = { if (state.webViewRef?.canGoBack() == true) state.webViewRef?.goBack() }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Refresh,
                        contentDescription = "刷新",
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = { state.webViewRef?.reload() }
                    )
                    OmsCircleIconButton(
                        icon = OmsIcons.Laptop,
                        contentDescription = "切换桌面版排版",
                        size = 32.dp,
                        iconSize = 16.dp,
                        containerColor = if (state.desktopMode) palette.accentSoft else palette.subtleSurface,
                        contentColor = if (state.desktopMode) palette.accent else palette.textSecondary,
                        onClick = {
                            state.desktopMode = !state.desktopMode
                            state.webViewRef?.reload()
                            OmsFeedbackController.show(
                                if (state.desktopMode) "已切换为桌面版网页视图" else "已切回移动版视图",
                                OmsToastKind.INFO
                            )
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OmsSecondaryButton(
                        label = "存入知识库",
                        icon = OmsIcons.Bookmark,
                        height = 32.dp,
                        onClick = { extractPageAndDispatch(saveToVault = true) }
                    )
                    OmsSecondaryButton(
                        label = "收藏到舱",
                        icon = OmsIcons.Pin,
                        height = 32.dp,
                        onClick = {
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
                }
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
                        if (newAppName.isEmpty()) Text("名称（如：公司OA / Linear / 掘金）", color = palette.textTertiary, fontSize = 13.sp)
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

/**
 * Compatibility Modal wrapper (retained for direct overlay callers if needed).
 */
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
