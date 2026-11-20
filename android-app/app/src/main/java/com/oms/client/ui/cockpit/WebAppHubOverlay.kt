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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import com.oms.client.ui.theme.omsBounceClick
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
 * In-App Embedded Web App Workspace (应用舱):
 * Lets users run web-capable apps inside OneMoreStep with persistent Cookie sessions,
 * custom pinned web apps, and 1-tap AI Page-Aware Companion (`✦ 问秘书` / `存尺素`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppHubModal(
    initialUrl: String? = null,
    initialTitle: String? = null,
    onDismiss: () -> Unit,
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

    var activeUrl by remember(initialUrl) { mutableStateOf(initialUrl?.trim().orEmpty()) }
    var activeTitle by remember(initialTitle) { mutableStateOf(initialTitle?.trim().orEmpty()) }
    var addressInput by remember(initialUrl) { mutableStateOf(initialUrl?.trim().orEmpty()) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var desktopMode by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var newAppName by remember { mutableStateOf("") }
    var newAppUrl by remember { mutableStateOf("") }

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
        val wv = webViewRef
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
                val t = obj.optString("title", activeTitle).ifBlank { activeUrl }
                val u = obj.optString("url", activeUrl)
                val b = obj.optString("body", "").trim()
                if (saveToVault) {
                    onSavePageToVault(t, u, b)
                } else {
                    onAskSecretaryWithPage(t, u, b)
                }
            }.onFailure {
                if (saveToVault) onSavePageToVault(activeTitle.ifBlank { activeUrl }, activeUrl, "")
                else onAskSecretaryWithPage(activeTitle.ifBlank { activeUrl }, activeUrl, "")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler {
            val wv = webViewRef
            when {
                activeUrl.isNotBlank() && wv != null && wv.canGoBack() -> wv.goBack()
                activeUrl.isNotBlank() -> {
                    activeUrl = ""
                    addressInput = ""
                }
                else -> onDismiss()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Compact Omnibar Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.topBar)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                OmsCircleIconButton(
                    icon = if (activeUrl.isNotBlank()) OmsIcons.ArrowLeft else OmsIcons.Close,
                    contentDescription = if (activeUrl.isNotBlank()) "返回应用舱" else "关闭应用舱",
                    size = 34.dp,
                    iconSize = 17.dp,
                    onClick = {
                        if (activeUrl.isNotBlank()) {
                            activeUrl = ""
                            addressInput = ""
                        } else {
                            onDismiss()
                        }
                    }
                )

                // URL / Search Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.subtleSurface)
                        .border(0.6.dp, palette.hairline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp)
                ) {
                    Icon(OmsIcons.Globe, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (addressInput.isEmpty()) {
                            Text("输入网址、网页应用或直接搜索…", color = palette.textTertiary, fontSize = 12.5.sp, maxLines = 1)
                        }
                        BasicTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
                            cursorBrush = SolidColor(palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val target = normalizeUrlOrSearch(addressInput)
                                    if (target.isNotBlank()) {
                                        activeUrl = target
                                        addressInput = target
                                        webViewRef?.loadUrl(target)
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (addressInput.isNotEmpty()) {
                        OmsCircleIconButton(
                            icon = OmsIcons.Close,
                            contentDescription = "清空",
                            size = 22.dp,
                            iconSize = 12.dp,
                            contentColor = palette.textTertiary,
                            onClick = { addressInput = "" }
                        )
                    }
                }

                if (activeUrl.isNotBlank()) {
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
                        size = 34.dp,
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

            if (activeUrl.isNotBlank() && loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    color = palette.accent,
                    trackColor = palette.hairline,
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
            }

            if (activeUrl.isBlank()) {
                // App Hub Dock Grid
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("青简·应用舱", color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "把常用网页版工具留在 App 内一站式使用，任意页面点右上角「问秘书」即可带全文总结、追问或入库。",
                            color = palette.textTertiary,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = allApps, key = { it.id }) { item ->
                            OmsSurfaceCard(
                                cornerRadius = 16.dp,
                                contentPadding = PaddingValues(13.dp),
                                onClick = {
                                    activeTitle = item.name
                                    activeUrl = item.url
                                    addressInput = item.url
                                },
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
                // Active Persistent Embedded WebView
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    if (target.startsWith("http://") || target.startsWith("https://")) {
                                        return false
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) {
                                        addressInput = url
                                        activeUrl = url
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        addressInput = url
                                        activeUrl = url
                                    }
                                    val t = view?.title?.trim().orEmpty()
                                    if (t.isNotBlank()) activeTitle = t
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) activeTitle = title
                                }
                            }
                            webViewRef = this
                            loadUrl(activeUrl)
                        }
                    },
                    update = { wv ->
                        webViewRef = wv
                        val targetUa = if (desktopMode) {
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        } else null
                        if (wv.settings.userAgentString != targetUa && desktopMode) {
                            wv.settings.userAgentString = targetUa
                        } else if (!desktopMode && wv.settings.userAgentString.contains("Macintosh")) {
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
                            onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() }
                        )
                        OmsCircleIconButton(
                            icon = OmsIcons.Refresh,
                            contentDescription = "刷新",
                            size = 32.dp,
                            iconSize = 16.dp,
                            onClick = { webViewRef?.reload() }
                        )
                        OmsCircleIconButton(
                            icon = OmsIcons.Laptop,
                            contentDescription = "切换桌面版排版",
                            size = 32.dp,
                            iconSize = 16.dp,
                            containerColor = if (desktopMode) palette.accentSoft else palette.subtleSurface,
                            contentColor = if (desktopMode) palette.accent else palette.textSecondary,
                            onClick = {
                                desktopMode = !desktopMode
                                webViewRef?.reload()
                                OmsFeedbackController.show(
                                    if (desktopMode) "已切换为桌面版网页视图" else "已切回移动版视图",
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
                                val u = activeUrl.trim()
                                val n = activeTitle.ifBlank { u }.take(24)
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
