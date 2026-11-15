package com.oms.client.share

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.capability.VoiceRecorderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class RecentMediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String
)

/**
 * Translucent half-screen Quick-Send Sheet launched from Xiaomi Control Center Tile,
 * App Launcher Shortcut, or System Share Sheet.
 * Supports: Recent Album Strip + Multi-Photo Picker + Document Picker (PDF/Word/Excel/etc.)
 * + Clipboard Paste + Voice Note + Intent Tag Selection.
 */
class QuickSendSheetActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TAG = "oms_initial_tag"
        const val EXTRA_AUTO_OPEN_PICKER = "oms_auto_open_picker" // "album" | "file"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val initialTag = ShareInputPolicy.resolveVoiceIdeaTag(
            intent?.getStringExtra(EXTRA_INITIAL_TAG) ?: "default_inbox"
        ).let { if (intent?.getStringExtra(EXTRA_INITIAL_TAG).isNullOrBlank()) "default_inbox" else it }
        val autoPicker = intent?.getStringExtra(EXTRA_AUTO_OPEN_PICKER)

        setContent {
            MaterialTheme {
                QuickSendSheetScreen(
                    headerTitle = "⚡ 青简·咫尺 速发面板",
                    initialUris = emptyList(),
                    initialText = "",
                    initialSubject = null,
                    initialTag = initialTag,
                    showRecentAlbumStrip = true,
                    autoOpenPicker = autoPicker,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

private fun hasMediaReadPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private fun loadRecentAlbumImages(context: Context, limit: Int = 10): List<RecentMediaImage> {
    if (!hasMediaReadPermission(context)) return emptyList()
    val result = mutableListOf<RecentMediaImage>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.IS_PENDING
    )
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < limit) {
                val isPending = cursor.getInt(2)
                if (isPending != 0) continue
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "IMG_$id.jpg"
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                result.add(RecentMediaImage(id = id, uri = uri, displayName = name))
            }
        }
    }
    return result
}

@Composable
fun QuickSendSheetScreen(
    headerTitle: String,
    initialUris: List<Uri>,
    initialText: String,
    initialSubject: String?,
    initialTag: String,
    showRecentAlbumStrip: Boolean,
    autoOpenPicker: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val selectedUris = remember { mutableStateListOf<Uri>().apply { addAll(initialUris) } }
    var selectedTag by remember { mutableStateOf(initialTag) }
    var noteText by remember { mutableStateOf(initialText) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSec by remember { mutableStateOf(0) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }
    var recordedAudioSec by remember { mutableStateOf(0) }
    var recentImages by remember { mutableStateOf<List<RecentMediaImage>>(emptyList()) }

    DisposableEffect(Unit) {
        onDispose {
            if (VoiceRecorderHelper.isRecording()) {
                VoiceRecorderHelper.cancelRecording()
            }
        }
    }

    LaunchedEffect(showRecentAlbumStrip) {
        if (showRecentAlbumStrip) {
            recentImages = withContext(Dispatchers.IO) {
                loadRecentAlbumImages(context, limit = 10)
            }
        }
    }

    LaunchedEffect(isRecording) {
        val startedMs = System.currentTimeMillis()
        while (isRecording && VoiceRecorderHelper.isRecording()) {
            delay(300L)
            recordingSec = ((System.currentTimeMillis() - startedMs + 400L) / 1000L).toInt()
        }
    }

    fun addSelection(uris: List<Uri>): Boolean {
        val next = (selectedUris + uris).distinct()
        val error = AttachmentResolver.selectionError(context, next + listOfNotNull(recordedAudioFile?.let { Uri.fromFile(it) }))
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return false
        }
        selectedUris.clear()
        selectedUris.addAll(next)
        return true
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentBudget.MAX_COUNT)
    ) { uris ->
        addSelection(uris)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        addSelection(uris)
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val started = VoiceRecorderHelper.startRecording(context)
            if (started != null) {
                recordedAudioFile = null
                recordingSec = 0
                isRecording = true
            }
        } else {
            Toast.makeText(context, "需要麦克风权限才能录制语音附言", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(autoOpenPicker) {
        when (autoOpenPicker) {
            "album" -> photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            "file" -> documentPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    fun sendToMacNow() {
        if (isRecording && VoiceRecorderHelper.isRecording()) {
            val stopped = VoiceRecorderHelper.stopRecording()
            isRecording = false
            if (stopped != null) {
                recordedAudioFile = stopped.first
                recordedAudioSec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
            }
        }
        val cleanText = noteText.trim()
        val audioFile = recordedAudioFile
        if (selectedUris.isEmpty() && cleanText.isEmpty() && audioFile == null) {
            Toast.makeText(context, "请先选择图片/文件、粘贴剪贴板或输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        val selectionError = AttachmentResolver.selectionError(context, selectedUris + listOfNotNull(audioFile?.let { Uri.fromFile(it) }))
        if (selectionError != null) {
            Toast.makeText(context, selectionError, Toast.LENGTH_LONG).show()
            return
        }
        val tc = OmsApplication.instance?.transportClient
        if (tc == null) {
            Toast.makeText(context, "传输服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }

        val metas = selectedUris.map { u -> AttachmentResolver.describeUri(context, u) }
        val itemType = AttachmentResolver.inferItemType(
            metas.map { it.mimeType },
            hasVoiceNote = audioFile != null
        )
        val computedTitle = when {
            !initialSubject.isNullOrBlank() -> initialSubject
            metas.size == 1 -> {
                val m = metas.first()
                "${AttachmentResolver.badgeForFile(m.safeFileName, m.mimeType)} ${m.safeFileName}"
            }
            metas.size > 1 -> {
                val first = metas.first()
                "${AttachmentResolver.badgeForFile(first.safeFileName, first.mimeType)} ${first.safeFileName} 等 ${metas.size} 个附件"
            }
            audioFile != null -> "🎙️ 手机语音闪念 (${recordedAudioSec}秒 · #$selectedTag)"
            else -> "手机速发消息 (#$selectedTag)"
        }

        val primaryUri = selectedUris.firstOrNull() ?: audioFile?.let { Uri.fromFile(it) }
        val extraFiles = if (selectedUris.size > 1) selectedUris.drop(1) else emptyList()
        val extraAudio = if (selectedUris.isNotEmpty() && audioFile != null) Uri.fromFile(audioFile) else null

        tc.submitItemToMacOrQueue(
            intentTag = selectedTag,
            itemType = itemType,
            title = computedTitle,
            textContent = cleanText.ifEmpty { null },
            voiceTranscript = null,
            fileUri = primaryUri,
            extraAudioUri = extraAudio,
            additionalFileUris = extraFiles
        )
        val summary = when {
            metas.isNotEmpty() -> "正在保存 ${metas.size} 个附件，投递状态请查看首页"
            audioFile != null -> "正在保存语音，投递状态请查看首页"
            else -> "内容已加入发送队列"
        }
        Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    val backdropInteraction = remember { MutableInteractionSource() }
    val sheetInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99020617))
            .clickable(
                interactionSource = backdropInteraction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = sheetInteraction,
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = headerTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("关闭 ✕", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }

                // Recent Album Images Strip (instant 1-tap toggle without opening gallery)
                if (showRecentAlbumStrip && recentImages.isNotEmpty()) {
                    Text(
                        text = "最近相册 / 截屏（点选可直接多张合并发 Mac）：",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentImages, key = { it.id }) { img ->
                            val isChosen = img.uri in selectedUris
                            RecentThumbTile(
                                item = img,
                                selected = isChosen,
                                onToggle = {
                                    if (isChosen) selectedUris.remove(img.uri) else addSelection(listOf(img.uri))
                                }
                            )
                        }
                    }
                }

                // 4 Quick Action Buttons: Album, Files (PDF/Word), Clipboard, Voice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("🖼️ 选相册图", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("📎 选文件(PDF/Word)", color = Color(0xFF4ADE80), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipItem = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val txt = clipItem?.coerceToText(context)?.toString()?.trim().orEmpty()
                            val clipUri = clipItem?.uri
                            if (clipUri != null && ShareInputPolicy.isSafeExternalStreamUri(clipUri, context.applicationInfo?.dataDir)) {
                                if (addSelection(listOf(clipUri))) {
                                    Toast.makeText(context, "📋 已添加剪贴板文件附件", Toast.LENGTH_SHORT).show()
                                }
                            } else if (txt.isNotEmpty()) {
                                noteText = if (noteText.isBlank()) txt else "$noteText\n$txt"
                                Toast.makeText(context, "📋 已读取剪贴板文本", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "⚠️ 当前剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("📋 贴剪贴板", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (isRecording && VoiceRecorderHelper.isRecording()) {
                                val stopped = VoiceRecorderHelper.stopRecording()
                                isRecording = false
                                if (stopped != null) {
                                    recordedAudioFile = stopped.first
                                    recordedAudioSec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
                                }
                            } else {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    val started = VoiceRecorderHelper.startRecording(context)
                                    if (started != null) {
                                        recordedAudioFile = null
                                        recordingSec = 0
                                        isRecording = true
                                    }
                                }
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isRecording) Color(0xFF7F1D1D) else Color(0xFF1E293B)
                        )
                    ) {
                        val label = when {
                            isRecording -> String.format(Locale.US, "⏹ 停止(%02ds)", recordingSec)
                            recordedAudioFile != null -> "🎙️ 重录(${recordedAudioSec}s)"
                            else -> "🎙️ 语音附言"
                        }
                        Text(label, color = Color(0xFFFBBF24), fontSize = 12.sp)
                    }
                }

                // Selected Attachments Chips
                if (selectedUris.isNotEmpty() || recordedAudioFile != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        selectedUris.forEach { uri ->
                            val meta = remember(uri) { AttachmentResolver.describeUri(context, uri) }
                            val badge = AttachmentResolver.badgeForFile(meta.safeFileName, meta.mimeType)
                            val sizeStr = AttachmentResolver.formatSizeShort(meta.sizeBytes)
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$badge ${meta.safeFileName}${if (sizeStr.isNotEmpty()) " ($sizeStr)" else ""}",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "✕",
                                    color = Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { selectedUris.remove(uri) }
                                        .padding(start = 4.dp)
                                )
                            }
                        }
                        if (recordedAudioFile != null) {
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFFBBF24), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("🎙️ 语音附言 (${recordedAudioSec}s)", color = Color(0xFFFBBF24), fontSize = 11.sp)
                                Text(
                                    text = "✕",
                                    color = Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        recordedAudioFile?.delete()
                                        recordedAudioFile = null
                                    }
                                )
                            }
                        }
                    }
                }

                // Intent Tags
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
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
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) Color(0xFF0284C7) else Color(0xFF1E293B)
                            )
                        ) {
                            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Note Input + Send Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = {
                            Text("可选：补充一句说明或直接点右侧发送...", color = Color(0xFF64748B), fontSize = 12.sp)
                        },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF38BDF8)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { sendToMacNow() },
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                    ) {
                        val cnt = selectedUris.size + (if (recordedAudioFile != null) 1 else 0)
                        Text(
                            text = if (cnt > 0) "🚀 发Mac($cnt)" else "🚀 发Mac",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentThumbTile(
    item: RecentMediaImage,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                var w = bounds.outWidth
                var h = bounds.outHeight
                while (w > 220 || h > 220) {
                    w /= 2
                    h /= 2
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                context.contentResolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) Color(0xFF38BDF8) else Color(0xFF334155),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onToggle)
    ) {
        val loaded = bmp
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Spacer(modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(
                    if (selected) Color(0xFF0284C7) else Color(0x880F172A),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "+",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
