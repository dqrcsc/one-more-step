package com.oms.client.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.R
import com.oms.client.capability.NotificationExecutor
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.data.StreamItemModel
import com.oms.client.otp.OtpExtractor
import com.oms.client.otp.OtpMatch
import com.oms.client.service.OmsCoreForegroundService
import com.oms.client.transport.OtaCheckResult
import com.oms.client.xiaomi.XiaomiAdaptationHelper
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Module 03 & 04: Jetpack Compose Main Timeline Stream, Remote Approval Card,
 * Commute TTS Reader, Smart OTA Version Check & Xiaomi HyperOS Permission Setup Wizard.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        OmsApplication.instance?.transportClient?.startAutoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsIfNeeded()
        startForegroundCoreService()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    OneMoreStepMainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OmsApplication.instance?.transportClient?.startAutoConnect()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startForegroundCoreService() {
        try {
            val svcIntent = Intent(this, OmsCoreForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (_: Throwable) {
        }
    }
}

private fun formatDateBucket(timestampMs: Long): String {
    val nowCal = Calendar.getInstance()
    val itemCal = Calendar.getInstance().apply { timeInMillis = timestampMs }

    val sameYear = nowCal.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR)
    val dayDiff = nowCal.get(Calendar.DAY_OF_YEAR) - itemCal.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && dayDiff == 0 -> "📅 今天"
        sameYear && dayDiff == 1 -> "📅 昨天"
        sameYear -> "📅 " + SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date(timestampMs))
        else -> "📅 " + SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestampMs))
    }
}

private fun formatTimeShort(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestampMs))
}

@Composable
fun OneMoreStepMainScreen() {
    val context = LocalContext.current
    val app = OmsApplication.instance ?: return
    val activeTier by app.transportClient.activeTierFlow.collectAsState()
    val streamItems by app.repository.itemsFlow.collectAsState()

    var showXiaomiWizard by remember { mutableStateOf(false) }
    var showPairSheet by remember { mutableStateOf(app.repository.getPairedMac() == null) }
    var pairUriInput by remember { mutableStateOf("") }
    var composerText by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("default_inbox") }

    // Filter, Search, Voice Recording & OTA states
    var filterTab by remember { mutableStateOf("ALL") } // "ALL" | "MAC_TO_PHONE" | "PHONE_TO_MAC" | "PENDING_APPROVAL"
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var checkingOta by remember { mutableStateOf(false) }
    var pendingOtaDialog by remember { mutableStateOf<OtaCheckResult?>(null) }

    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingSec by remember { mutableStateOf(0) }
    var recordingBars by remember { mutableStateOf("▂") }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val f = VoiceRecorderHelper.startRecording(context)
            if (f != null) {
                recordingSec = 0
                isRecordingVoice = true
            } else {
                Toast.makeText(context, "⚠️ 无法启动麦克风录音", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "⚠️ 请授予麦克风录音权限以发送语音", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isRecordingVoice) {
        val startMs = System.currentTimeMillis()
        while (isRecordingVoice && VoiceRecorderHelper.isRecording()) {
            recordingSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
            val amp = VoiceRecorderHelper.getMaxAmplitude()
            recordingBars = when {
                amp > 12000 -> "▂▅▇█"
                amp > 6000 -> "▂▅▇"
                amp > 2000 -> "▂▅"
                else -> "▂"
            }
            delay(200L)
        }
    }

    val installedVerName = remember { app.transportClient.getInstalledApkInfo().first }

    val macCount = remember(streamItems) { streamItems.count { it.direction == "MAC_TO_PHONE" } }
    val phoneCount = remember(streamItems) { streamItems.count { it.direction == "PHONE_TO_MAC" } }
    val approvalCount = remember(streamItems) {
        streamItems.count { it.itemType == "APPROVAL" && it.syncStatus == "PENDING_APPROVAL" }
    }

    val filteredItems = remember(streamItems, filterTab, searchQuery) {
        val q = searchQuery.trim().lowercase()
        streamItems.filter { item ->
            val tabMatch = when (filterTab) {
                "MAC_TO_PHONE" -> item.direction == "MAC_TO_PHONE"
                "PHONE_TO_MAC" -> item.direction == "PHONE_TO_MAC"
                "PENDING_APPROVAL" -> item.itemType == "APPROVAL" && item.syncStatus == "PENDING_APPROVAL"
                else -> true
            }
            if (!tabMatch) return@filter false
            if (q.isEmpty()) return@filter true
            val hay = listOfNotNull(
                item.title,
                item.textContent,
                item.voiceTranscript,
                item.intentTag,
                item.macSavedPath
            ).joinToString(" ").lowercase()
            hay.contains(q)
        }
    }

    val groupedByDate = remember(filteredItems) {
        filteredItems.groupBy { formatDateBucket(it.createdAtMs) }
    }

    // OTA Update Confirmation Dialog (only shown when a newer/changed APK exists on Mac)
    pendingOtaDialog?.let { ota ->
        val sizeMb = String.format(Locale.US, "%.1f MB", ota.remoteSizeBytes / 1048576.0)
        AlertDialog(
            onDismissRequest = { pendingOtaDialog = null },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE2E8F0),
            title = {
                Text("🎉 发现新版本 APK", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前已装：v${ota.localVersionName} (#${ota.localVersionCode} · ${ota.localSha256.take(8)})", fontSize = 13.sp)
                    Text("Mac 最新：v${ota.remoteVersionName} (#${ota.remoteVersionCode} · ${ota.remoteSha256.take(8)})", fontSize = 13.sp, color = Color(0xFF4ADE80))
                    Text("安装包大小：$sizeMb", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    if (ota.remoteUpdatedAtIso.isNotBlank()) {
                        Text("构建时间：${ota.remoteUpdatedAtIso.take(19).replace('T', ' ')}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = ota.downloadUrl
                        pendingOtaDialog = null
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Throwable) {
                            Toast.makeText(context, "打开下载链接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Text("立即下载安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOtaDialog = null }) {
                    Text("暂不更新", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 1. Top Connection & Control Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val brandIconBitmap = remember {
                    try {
                        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)?.asImageBitmap()
                    } catch (_: Throwable) {
                        null
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (brandIconBitmap != null) {
                        Image(
                            bitmap = brandIconBitmap,
                            contentDescription = "青简·咫尺",
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "青简·咫尺 · OneMoreStep v$installedVerName",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                        val tierDesc = when (activeTier) {
                            "LAN_WS" -> "🟢 局域网高速直连 (Tier 1)"
                            "BT_RFCOMM" -> "🔵 近场蓝牙连接 (Tier 2)"
                            "WAN_WEBRTC" -> "🟣 跨网远程在线 (Tier 3)"
                            else -> "⚪ 离线队列模式 (点此扫描重连)"
                        }
                        Text(
                            text = tierDesc,
                            color = if (activeTier == "DISCONNECTED") Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.clickable {
                                Toast.makeText(context, "🔍 正在扫描局域网 Mac 并重连...", Toast.LENGTH_SHORT).show()
                                app.transportClient.reconnectNow { _, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !checkingOta,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        onClick = {
                            checkingOta = true
                            app.transportClient.checkOtaUpdate { res ->
                                checkingOta = false
                                if (!res.ok) {
                                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                } else if (!res.hasUpdate) {
                                    Toast.makeText(
                                        context,
                                        "✅ 当前已是最新版本 (v${res.localVersionName})",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    pendingOtaDialog = res
                                }
                            }
                        }
                    ) {
                        Text(
                            if (checkingOta) "⏳检查" else "🔄更新",
                            color = Color(0xFF4ADE80),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        onClick = { showPairSheet = !showPairSheet }
                    ) {
                        Text("绑定Mac", color = Color(0xFF38BDF8), fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        onClick = { showXiaomiWizard = !showXiaomiWizard }
                    ) {
                        Text("小米权限", color = Color(0xFFFBBF24), fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // 2. Collapsible Pairing Card
        if (showPairSheet) {
            val currentPaired = app.repository.getPairedMac()
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🔐 v2 加密配对与重连",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    if (currentPaired != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (currentPaired.pairingVersion == 2) "当前 v2 绑定：${currentPaired.macName} (${currentPaired.macId.takeLast(8)})" else "旧绑定需重新配对：${currentPaired.macName}（数据保留）",
                            color = Color(0xFF4ADE80),
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pairUriInput,
                        onValueChange = { pairUriInput = it },
                        placeholder = { Text("粘贴从 Mac 本地取得的完整 v2 oms://pair... 链接", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF38BDF8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(onClick = {
                            Toast.makeText(context, "🔍 正在重连已绑定的 Mac...", Toast.LENGTH_SHORT).show()
                            app.transportClient.reconnectNow { ok, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                if (ok) showPairSheet = false
                            }
                        }) {
                            Text("🔍 重连已绑Mac", color = Color(0xFF38BDF8), fontSize = 12.sp)
                        }
                        Button(onClick = {
                            app.transportClient.pairWithUri(pairUriInput.trim()) { ok, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                if (ok) {
                                    pairUriInput = ""
                                    showPairSheet = false
                                }
                            }
                        }) {
                            Text("🔐 验证并绑定", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. Collapsible Xiaomi HyperOS / MIUI Permission Wizard
        if (showXiaomiWizard) {
            Spacer(modifier = Modifier.height(8.dp))
            XiaomiPermissionWizardCard(context = context)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Filter Tabs, Search Toggle & History Cleanup Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = buildList {
                    add("ALL" to "全部 (${streamItems.size})")
                    add("MAC_TO_PHONE" to "🤖 Mac推来 ($macCount)")
                    add("PHONE_TO_MAC" to "📱 我发出的 ($phoneCount)")
                    if (approvalCount > 0 || filterTab == "PENDING_APPROVAL") {
                        add("PENDING_APPROVAL" to "⚠️ 待审批 ($approvalCount)")
                    }
                }
                tabs.forEach { (key, label) ->
                    val active = filterTab == key
                    OutlinedButton(
                        onClick = { filterTab = key },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (active) Color(0xFF0284C7) else Color(0xFF1E293B)
                        )
                    ) {
                        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    onClick = {
                        showSearchBar = !showSearchBar
                        if (!showSearchBar) searchQuery = ""
                    }
                ) {
                    Text(if (showSearchBar) "收起🔍" else "🔍搜索", color = Color(0xFF38BDF8), fontSize = 11.sp)
                }
                TextButton(
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    onClick = {
                        val removed = app.repository.clearDeliveredHistory()
                        Toast.makeText(context, "🧹 已清理 $removed 条已同步记录", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("🧹清理", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }

        if (showSearchBar) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索标题、正文、语音转写或标签...", color = Color.Gray, fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF38BDF8)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Bidirectional Grouped & Collapsible Stream Timeline
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📭 当前分类暂无记录\n底部可直接发消息，或截屏/分享直达 Mac",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groupedByDate.forEach { (dateHeader, dayItems) ->
                    item(key = "header_$dateHeader") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$dateHeader · ${dayItems.size} 条",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "点击卡片展开/折叠",
                                color = Color(0xFF475569),
                                fontSize = 10.sp
                            )
                        }
                    }
                    items(dayItems, key = { it.itemId }) { item ->
                        StreamTimelineCard(item = item)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 6. Bottom Quick Send & Tag Selector Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "default_inbox" to "📥 默认Inbox",
                        "pkb_vault" to "📚 存知识库",
                        "voice_idea" to "💡 我有想法",
                        "ui_bug" to "🐛 修UI报错"
                    ).forEach { (tag, label) ->
                        val active = selectedTag == tag
                        OutlinedButton(
                            onClick = { selectedTag = tag },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) Color(0xFF0284C7) else Color.Transparent
                            )
                        ) {
                            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (isRecordingVoice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF3F1D2E), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🔴 正在录音 ${String.format(Locale.US, "%02d:%02d", recordingSec / 60, recordingSec % 60)} $recordingBars",
                                color = Color(0xFFF87171),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Mac 收到后自动调用 SenseVoice 离线转文字",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    VoiceRecorderHelper.cancelRecording()
                                    isRecordingVoice = false
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("取消", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    val stopped = VoiceRecorderHelper.stopRecording()
                                    isRecordingVoice = false
                                    if (stopped == null) {
                                        Toast.makeText(context, "⚠️ 录音时间过短，请重试", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val (audioFile, durMs) = stopped
                                        val sec = ((durMs + 500L) / 1000L).toInt().coerceAtLeast(1)
                                        val noteTxt = composerText.trim()
                                        val effectiveTag = if (selectedTag == "default_inbox") "voice_idea" else selectedTag
                                        app.transportClient.submitItemToMacOrQueue(
                                            intentTag = effectiveTag,
                                            itemType = "VOICE_IDEA",
                                            title = "🎙️ 手机语音消息 (${sec}秒 · #$effectiveTag)",
                                            textContent = noteTxt.ifEmpty { null },
                                            voiceTranscript = null,
                                            fileUri = Uri.fromFile(audioFile)
                                        )
                                        composerText = ""
                                        Toast.makeText(context, "🚀 语音 (${sec}秒) 已发送至 Mac！", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("🚀 发送语音", fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { composerText = it },
                            placeholder = { Text("发消息、链接，或点右侧🎙️录语音...", color = Color.Gray, fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    val f = VoiceRecorderHelper.startRecording(context)
                                    if (f != null) {
                                        recordingSec = 0
                                        isRecordingVoice = true
                                    } else {
                                        Toast.makeText(context, "⚠️ 无法启动麦克风录音", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF0F172A)
                            )
                        ) {
                            Text("🎙️语音", color = Color(0xFFFBBF24), fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val txt = composerText.trim()
                                if (txt.isNotEmpty()) {
                                    app.transportClient.submitItemToMacOrQueue(
                                        intentTag = selectedTag,
                                        itemType = if (selectedTag == "voice_idea") "VOICE_IDEA" else "TEXT",
                                        title = "手机直接投递 (#$selectedTag)",
                                        textContent = txt,
                                        voiceTranscript = null,
                                        fileUri = null
                                    )
                                    composerText = ""
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text("发送", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun XiaomiPermissionWizardCard(context: Context) {
    val app = OmsApplication.instance
    var refreshTick by remember { mutableStateOf(0) }
    var autoOtpEnabled by remember {
        mutableStateOf(app?.repository?.isAutoSyncOtpEnabled() ?: true)
    }
    val health = remember(refreshTick) { XiaomiAdaptationHelper.inspectAll(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚡ 小米 HyperOS / MIUI 满血权限自检向导",
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                TextButton(onClick = { refreshTick++ }) {
                    Text("刷新状态", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
            }
            XiaomiCheckRow(
                label = "1. 小米「后台弹出界面」与「锁屏显示」(OpCode 10021/10008)",
                ok = health.backgroundPopupAllowed && health.lockScreenDisplayAllowed,
                onClick = { XiaomiAdaptationHelper.openXiaomiPermissionEditor(context) }
            )
            XiaomiCheckRow(
                label = "2. 电池省电策略设为「无限制」",
                ok = health.batteryUnrestricted,
                onClick = { XiaomiAdaptationHelper.openBatteryUnrestrictedPrompt(context) }
            )
            XiaomiCheckRow(
                label = "3. 悬浮窗权限 (截屏秒传胶囊 & AI 急停按钮)",
                ok = health.overlayAllowed,
                onClick = { XiaomiAdaptationHelper.openOverlayPermissionSettings(context) }
            )
            XiaomiCheckRow(
                label = "4. 无障碍服务 (GUI 屏幕自动化与静默截屏)",
                ok = health.accessibilityEnabled,
                onClick = { XiaomiAdaptationHelper.openAccessibilitySettings(context) }
            )
            XiaomiCheckRow(
                label = "5. 麦克风录音权限 (发送语音闪念与语音附言)",
                ok = health.recordAudioGranted,
                onClick = { XiaomiAdaptationHelper.openXiaomiPermissionEditor(context) }
            )
            XiaomiCheckRow(
                label = "6. 通知使用权 (锁屏实时同步短信/微信/飞书验证码至 Mac)",
                ok = health.notificationListenerEnabled,
                onClick = { XiaomiAdaptationHelper.openNotificationListenerSettings(context) }
            )
            XiaomiCheckRow(
                label = "7. 短信与小米「通知类短信」(OpCode 10018 · 穿透锁屏脱敏)",
                ok = health.smsReadGranted && health.notificationSmsAllowed,
                onClick = { XiaomiAdaptationHelper.openXiaomiPermissionEditor(context) }
            )
            XiaomiCheckRow(
                label = "8. 小米应用「自启动」开关",
                ok = true,
                onClick = { XiaomiAdaptationHelper.openXiaomiAutostartSettings(context) }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (autoOtpEnabled) "🔑 验证码实时同步 Mac：已开启" else "🔑 验证码实时同步 Mac：已暂停",
                    color = if (autoOtpEnabled) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val next = !autoOtpEnabled
                            autoOtpEnabled = next
                            app?.repository?.setAutoSyncOtpEnabled(next)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (autoOtpEnabled) "暂停同步" else "开启同步", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            val demoCode = (100000..999999).random().toString()
                            app?.transportClient?.submitOtpToMacOrQueue(
                                OtpMatch(
                                    code = demoCode,
                                    sender = "青简测试",
                                    fullText = "【青简测试】您的登录验证码为 $demoCode，5分钟内有效，切勿泄露。",
                                    sourceApp = "com.oms.client"
                                )
                            )
                            Toast.makeText(
                                context,
                                "🚀 已向 Mac 推送测试验证码 $demoCode (可直接 ⌘V)",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                    ) {
                        Text("⚡ 测试验证码", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun XiaomiCheckRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${if (ok) "✅" else "⚠️"} $label",
            color = if (ok) Color(0xFF4ADE80) else Color(0xFFF87171),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClick) {
            Text("去开启", color = Color(0xFF38BDF8), fontSize = 12.sp)
        }
    }
}

@Composable
fun StreamTimelineCard(item: StreamItemModel) {
    val context = LocalContext.current
    val app = OmsApplication.instance ?: return
    val isFromPhone = item.direction == "PHONE_TO_MAC"
    val isPendingApproval = item.itemType == "APPROVAL" && item.syncStatus == "PENDING_APPROVAL"

    var expanded by remember(item.itemId, isPendingApproval) {
        mutableStateOf(isPendingApproval)
    }

    val isAudioAttachment = remember(item.localFileUri, item.itemType) {
        val u = item.localFileUri?.lowercase() ?: ""
        u.endsWith(".m4a") || u.endsWith(".mp3") || u.endsWith(".wav") || u.endsWith(".ogg") || u.endsWith(".aac")
    }

    var isPlayingAudio by remember(item.itemId) {
        mutableStateOf(VoiceRecorderHelper.playingItemId == item.itemId)
    }

    val thumbnailBitmap = remember(item.localFileUri, isAudioAttachment) {
        if (isAudioAttachment) return@remember null
        val uriStr = item.localFileUri ?: return@remember null
        try {
            val uri = Uri.parse(uriStr)
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (item.itemType == "APPROVAL") {
                Color(0xFF312E81)
            } else if (isFromPhone) {
                Color(0xFF0F2942)
            } else {
                Color(0xFF1E293B)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            // 1. Compact Header Row: Direction + Time + Status + Expand/Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isFromPhone) "📱 手机➔Mac (#${item.intentTag})" else "🤖 Mac➔手机",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatTimeShort(item.createdAtMs),
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusLabel = when (item.syncStatus) {
                        "DELIVERED" -> "✓ 已送达"
                        "QUEUED" -> "⏳ 待发送"
                        "EXPIRED" -> "审批已过期"
                        "PENDING_APPROVAL" -> "⚠️ 待审批"
                        "APPROVED" -> "✅ 已批准"
                        "REJECTED" -> "❌ 已拒绝"
                        else -> item.syncStatus
                    }
                    Text(
                        text = statusLabel,
                        color = if (item.syncStatus == "PENDING_APPROVAL") Color(0xFFFBBF24) else Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Text(
                        text = if (expanded) "▴收起" else "▾展开",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 2. Body Row (Compact 2-line preview with optional image thumbnail vs Full Expanded View)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (thumbnailBitmap != null && !expanded) {
                    Image(
                        bitmap = thumbnailBitmap,
                        contentDescription = "缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (!item.title.isNullOrBlank()) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = if (expanded) 4 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val summaryText = item.textContent?.takeIf { it.isNotBlank() }
                        ?: item.voiceTranscript?.let { "🎙️ \"$it\"" }
                        ?: (if (isAudioAttachment) "🎙️ 语音录音附件 (展开可回放)" else if (item.localFileUri != null) "📎 包含图片/文件附件" else "")

                    if (summaryText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (expanded) summaryText else summaryText.replace('\n', ' '),
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            maxLines = if (expanded) 60 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Expanded Details & Interactive Buttons
            if (expanded) {
                if (thumbnailBitmap != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = thumbnailBitmap,
                        contentDescription = "图片预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    )
                }

                if (!item.voiceTranscript.isNullOrBlank() && !item.textContent.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "🎙️ 语音附言: \"${item.voiceTranscript}\"",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp
                    )
                }

                if (!item.macSavedPath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "💻 Mac 存储路径: ${item.macSavedPath}",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }

                if (item.itemType == "APPROVAL" && item.syncStatus == "PENDING_APPROVAL") {
                    val metadata = remember(item.metadataJson) { runCatching { JSONObject(item.metadataJson ?: "{}") }.getOrDefault(JSONObject()) }
                    val expiresAtMs = metadata.optLong("expiresAtMs", 0L)
                    var nowMs by remember(item.itemId) { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(item.itemId, expiresAtMs) {
                        while (nowMs < expiresAtMs) {
                            delay(1000)
                            nowMs = System.currentTimeMillis()
                        }
                    }
                    val expired = expiresAtMs <= nowMs
                    val options = metadata.optJSONArray("options")
                    Spacer(modifier = Modifier.height(8.dp))
                    if (expired) Text("审批已过期，请在 Mac 重新发起", color = Color(0xFFFBBF24), fontSize = 12.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (options != null) {
                            for (index in 0 until options.length()) {
                                val option = options.optString(index)
                                Button(
                                    enabled = !expired,
                                    onClick = {
                                        app.transportClient.sendApprovalDecision(
                                            itemId = item.itemId,
                                            approvalId = metadata.optString("approvalId", item.itemId),
                                            approved = index == 0,
                                            selectedOption = option,
                                            userComment = null
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (index == 0) Color(0xFF16A34A) else Color(0xFFDC2626))
                                ) { Text(option, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                // Action Row: TTS / Copy / Delete
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isAudioAttachment && item.localFileUri != null) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    if (isPlayingAudio) {
                                        VoiceRecorderHelper.stopPlayback()
                                        isPlayingAudio = false
                                    } else {
                                        isPlayingAudio = VoiceRecorderHelper.playAudio(
                                            context,
                                            item.itemId,
                                            item.localFileUri
                                        ) {
                                            isPlayingAudio = false
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    if (isPlayingAudio) "⏹停止语音" else "▶️播放语音",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (!isFromPhone && !item.textContent.isNullOrBlank()) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    NotificationExecutor.speakText(
                                        context,
                                        "${item.title ?: ""}。${item.textContent}",
                                        useAlarmStream = false,
                                        speechRate = 1.2f
                                    )
                                }
                            ) {
                                Text("🎧 TTS朗读", color = Color(0xFF4ADE80), fontSize = 11.sp)
                            }
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = { NotificationExecutor.stopSpeaking() }
                            ) {
                                Text("⏹停止", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }
                        val otpCode = remember(item.metadataJson, item.intentTag, item.textContent) {
                            try {
                                item.metadataJson?.let { JSONObject(it).optString("otpCode", "") }
                                    ?.takeIf { it.isNotBlank() }
                            } catch (_: Throwable) {
                                null
                            } ?: if (item.intentTag == "sms_otp") {
                                OtpExtractor.extractOtp(item.textContent, item.title)?.code
                            } else {
                                null
                            }
                        }
                        if (!otpCode.isNullOrBlank()) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("OMS_OTP", otpCode))
                                    Toast.makeText(context, "🔑 已复制验证码: $otpCode", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("🔑复制验证码($otpCode)", color = Color(0xFF4ADE80), fontSize = 11.sp)
                            }
                        }
                        val copyable = item.textContent ?: item.voiceTranscript ?: item.macSavedPath
                        if (!copyable.isNullOrBlank()) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("OMS", copyable))
                                    Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("📋复制", color = Color(0xFF38BDF8), fontSize = 11.sp)
                            }
                        }
                    }
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        onClick = {
                            app.repository.deleteStreamItem(item.itemId)
                            Toast.makeText(context, "🗑️ 已删除该条记录", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("🗑️删除", color = Color(0xFFF87171), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
