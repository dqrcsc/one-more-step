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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.capability.VoiceRecorderHelper
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsThemeController
import com.oms.client.ui.theme.OmsThemeRoot
import com.oms.client.ui.theme.OmsTogglePill
import com.oms.client.ui.theme.omsBounceClick
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
 * App Launcher Shortcut, or System Share Sheet — unified with v3.0 Theme & Vector Design System.
 */
class QuickSendSheetActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TAG = "oms_initial_tag"
        const val EXTRA_AUTO_OPEN_PICKER = "oms_auto_open_picker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OmsThemeController.ensureLoaded(this)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val initialTag = ShareInputPolicy.resolveVoiceIdeaTag(
            intent?.getStringExtra(EXTRA_INITIAL_TAG) ?: "default_inbox"
        ).let { if (intent?.getStringExtra(EXTRA_INITIAL_TAG).isNullOrBlank()) "default_inbox" else it }
        val autoPicker = intent?.getStringExtra(EXTRA_AUTO_OPEN_PICKER)

        setContent {
            OmsThemeRoot {
                QuickSendSheetScreen(
                    headerTitle = "青简速发至 Mac",
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
                if (cursor.getInt(2) != 0) continue
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
    val palette = OmsTheme.colors
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
    ) { uris -> addSelection(uris) }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addSelection(uris) }

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
        }
    }

    LaunchedEffect(autoOpenPicker) {
        when (autoOpenPicker) {
            "album" -> photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        if (selectedUris.isEmpty() && cleanText.isEmpty() && audioFile == null) return

        val selectionError = AttachmentResolver.selectionError(context, selectedUris + listOfNotNull(audioFile?.let { Uri.fromFile(it) }))
        if (selectionError != null) {
            Toast.makeText(context, selectionError, Toast.LENGTH_LONG).show()
            return
        }
        val tc = OmsApplication.instance?.transportClient ?: return

        val metas = selectedUris.map { u -> AttachmentResolver.describeUri(context, u) }
        val itemType = AttachmentResolver.inferItemType(
            metas.map { it.mimeType },
            hasVoiceNote = audioFile != null
        )
        val computedTitle = when {
            !initialSubject.isNullOrBlank() -> initialSubject
            metas.size == 1 -> metas.first().safeFileName
            metas.size > 1 -> "${metas.first().safeFileName} 等 ${metas.size} 个附件"
            audioFile != null -> "手机语音闪念 (${recordedAudioSec}s)"
            else -> cleanText.take(28)
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
        Toast.makeText(context, "已加入加密投递队列", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    val backdropInteraction = remember { MutableInteractionSource() }
    val sheetInteraction = remember { MutableInteractionSource() }
    val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(
                interactionSource = backdropInteraction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 16.dp, shape = sheetShape, clip = false)
                .clip(sheetShape)
                .background(palette.composer)
                .border(0.6.dp, palette.strongBorder, sheetShape)
                .navigationBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = sheetInteraction,
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Drag Handle & Header
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(palette.hairline)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerTitle.replace("📤 ", "").replace("⚡ ", ""),
                    color = palette.textPrimary,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OmsCircleIconButton(
                    icon = OmsIcons.Close,
                    contentDescription = "关闭",
                    size = 32.dp,
                    iconSize = 16.dp,
                    containerColor = palette.subtleSurface,
                    onClick = onDismiss
                )
            }

            // Recent Album Strip
            if (showRecentAlbumStrip && recentImages.isNotEmpty()) {
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

            // Quick Action Row
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmsSecondaryButton(
                    label = "相册多选",
                    icon = OmsIcons.Image,
                    height = 35.dp,
                    onClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                OmsSecondaryButton(
                    label = "选择文件",
                    icon = OmsIcons.FileText,
                    height = 35.dp,
                    onClick = { documentPickerLauncher.launch(arrayOf("*/*")) }
                )
                OmsSecondaryButton(
                    label = "贴剪贴板",
                    icon = OmsIcons.Copy,
                    height = 35.dp,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipItem = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        val txt = clipItem?.coerceToText(context)?.toString()?.trim().orEmpty()
                        val clipUri = clipItem?.uri
                        if (clipUri != null && ShareInputPolicy.isSafeExternalStreamUri(clipUri, context.applicationInfo?.dataDir)) {
                            addSelection(listOf(clipUri))
                        } else if (txt.isNotEmpty()) {
                            noteText = if (noteText.isBlank()) txt else "$noteText\n$txt"
                        }
                    }
                )
                OmsSecondaryButton(
                    label = when {
                        isRecording -> String.format(Locale.US, "停止 (%02ds)", recordingSec)
                        recordedAudioFile != null -> "重录 (${recordedAudioSec}s)"
                        else -> "语音附言"
                    },
                    icon = if (isRecording) OmsIcons.StopSquare else OmsIcons.Mic,
                    contentColor = if (isRecording) palette.danger else palette.textPrimary,
                    height = 35.dp,
                    onClick = {
                        if (isRecording && VoiceRecorderHelper.isRecording()) {
                            val stopped = VoiceRecorderHelper.stopRecording()
                            isRecording = false
                            if (stopped != null) {
                                recordedAudioFile = stopped.first
                                recordedAudioSec = ((stopped.second + 500L) / 1000L).toInt().coerceAtLeast(1)
                            }
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (!hasPerm) audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else if (VoiceRecorderHelper.startRecording(context) != null) {
                                recordedAudioFile = null
                                recordingSec = 0
                                isRecording = true
                            }
                        }
                    }
                )
            }

            // Selected Attachment Pills
            if (selectedUris.isNotEmpty() || recordedAudioFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedUris.forEach { uri ->
                        val meta = remember(uri) { AttachmentResolver.describeUri(context, uri) }
                        OmsTogglePill(
                            label = "${meta.safeFileName.take(18)} ×",
                            active = true,
                            icon = OmsIcons.Paperclip,
                            onClick = { selectedUris.remove(uri) }
                        )
                    }
                    if (recordedAudioFile != null) {
                        OmsTogglePill(
                            label = "语音 (${recordedAudioSec}s) ×",
                            active = true,
                            icon = OmsIcons.Mic,
                            onClick = {
                                recordedAudioFile?.delete()
                                recordedAudioFile = null
                            }
                        )
                    }
                }
            }

            // Destination Intent Tags
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "default_inbox" to "默认收件箱",
                    "pkb_vault" to "存知识库",
                    "voice_idea" to "灵感闪念",
                    "ui_bug" to "界面修复"
                ).forEach { (tag, label) ->
                    OmsTogglePill(label, selectedTag == tag, onClick = { selectedTag = tag })
                }
            }

            // Sculptural Pill Input + Send Orb
            val inputShape = RoundedCornerShape(22.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(inputShape)
                    .background(palette.subtleSurface)
                    .border(0.6.dp, palette.hairline, inputShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    if (noteText.isEmpty()) {
                        Text("补充说明，或直接点右侧发送…", color = palette.textTertiary, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        maxLines = 3,
                        textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.5.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val canSend = selectedUris.isNotEmpty() || noteText.isNotBlank() || recordedAudioFile != null || isRecording
                OmsCircleIconButton(
                    icon = OmsIcons.ArrowUp,
                    contentDescription = "发送至 Mac",
                    enabled = canSend,
                    size = 36.dp,
                    iconSize = 18.dp,
                    containerColor = palette.sendButtonBg,
                    contentColor = palette.sendButtonFg,
                    onClick = { sendToMacNow() }
                )
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
    val palette = OmsTheme.colors
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

    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(66.dp)
            .omsBounceClick(scaleDown = 0.92f, shape = shape, onClick = onToggle)
            .background(palette.subtleSurface, shape)
            .border(
                width = if (selected) 2.dp else 0.6.dp,
                color = if (selected) palette.accent else palette.hairline,
                shape = shape
            )
    ) {
        bmp?.let { loaded ->
            Image(
                bitmap = loaded,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } ?: Spacer(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(19.dp)
                .background(if (selected) palette.accent else Color(0x77000000), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) OmsIcons.Check else OmsIcons.Plus,
                contentDescription = null,
                tint = if (selected) palette.onAccent else Color.White,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}
