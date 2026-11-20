package com.oms.client.ui.cockpit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.oms.client.OmsApplication
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.client.ui.theme.OmsCircleIconButton
import com.oms.client.ui.theme.OmsFeedbackController
import com.oms.client.ui.theme.OmsIcons
import com.oms.client.ui.theme.OmsPrimaryButton
import com.oms.client.ui.theme.OmsSecondaryButton
import com.oms.client.ui.theme.OmsSurfaceCard
import com.oms.client.ui.theme.OmsTheme
import com.oms.client.ui.theme.OmsToastKind
import com.oms.client.ui.theme.omsBounceClick
import com.oms.core.ai.FieldGuideSpecimenCard
import com.oms.core.ai.RealtimeBackendMode
import com.oms.core.ai.RealtimeSessionOptions
import com.oms.core.ai.RealtimeVoiceCatalog
import com.oms.core.ai.RealtimeVoicePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// 1. Botanical Glass Camera Viewfinder (Precision Reticle + Frosted Result Sheet)
// =============================================================================
@Composable
fun BotanicalGlassCameraModal(
    coordinator: CockpitRuntimeCoordinator,
    onDismiss: () -> Unit,
    onAskFollowUpInChat: (FieldGuideSpecimenCard) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPerm = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPerm) camPermLauncher.launch(Manifest.permission.CAMERA)
    }

    var questionInput by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var resultCard by remember { mutableStateOf<FieldGuideSpecimenCard?>(null) }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val albumPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isAnalyzing = true
                errorMsg = null
                try {
                    val (jpeg, path) = loadAndCompressUriToJpeg(context, uri)
                    lastSavedPath = path
                    resultCard = coordinator.identifyBotanicalSpecimen(jpeg, path, questionInput)
                } catch (e: Throwable) {
                    errorMsg = "识物未成功：${e.message}"
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080C))
    ) {
        if (hasCameraPerm) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            provider.unbindAll()
                            boundCamera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(OmsIcons.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(14.dp))
                Text("开启相机权限以使用博物之眼", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(14.dp))
                OmsPrimaryButton(
                    label = "授予相机权限",
                    onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
        }

        // Precision 4-Corner Lens Reticle Overlay (Google Lens / Leica style)
        if (resultCard == null) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(236.dp)
            ) {
                val stroke = 2.8.dp.toPx()
                val arm = 32.dp.toPx()
                val r = 22.dp.toPx()
                val w = size.width
                val h = size.height
                val tint = if (isAnalyzing) Color(0xFF34D399) else Color(0xCCFFFFFF)

                val p = Path().apply {
                    // Top-Left
                    moveTo(0f, arm); lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f); lineTo(arm, 0f)
                    // Top-Right
                    moveTo(w - arm, 0f); lineTo(w - r, 0f); quadraticBezierTo(w, 0f, w, r); lineTo(w, arm)
                    // Bottom-Right
                    moveTo(w, h - arm); lineTo(w, h - r); quadraticBezierTo(w, h, w - r, h); lineTo(w - arm, h)
                    // Bottom-Left
                    moveTo(arm, h); lineTo(r, h); quadraticBezierTo(0f, h, 0f, h - r); lineTo(0f, h - arm)
                }
                drawPath(p, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }

        // Top Floating Glass Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OmsCircleIconButton(
                icon = OmsIcons.Close,
                contentDescription = "关闭镜头",
                containerColor = Color(0x5918181B),
                contentColor = Color.White,
                onClick = onDismiss
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x5918181B))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(OmsIcons.Leaf, contentDescription = null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(15.dp))
                Text("博物之眼", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }

            OmsCircleIconButton(
                icon = OmsIcons.Flashlight,
                contentDescription = "补光灯",
                containerColor = if (torchEnabled) Color(0xCCF59E0B) else Color(0x5918181B),
                contentColor = if (torchEnabled) Color.Black else Color.White,
                onClick = {
                    torchEnabled = !torchEnabled
                    boundCamera?.cameraControl?.enableTorch(torchEnabled)
                }
            )
        }

        // Bottom Controls OR Specimen Sheet
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD9090B0E), Color(0xF5090B0E))
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            errorMsg?.let { err ->
                Text(err, color = Color(0xFFFCA5A5), fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            }

            if (resultCard != null) {
                var zoomCameraPhoto by remember { mutableStateOf(false) }
                if (zoomCameraPhoto && !lastSavedPath.isNullOrBlank()) {
                    OmsZoomableImageLightbox(
                        imagePath = lastSavedPath!!,
                        titleHint = resultCard!!.chineseName,
                        onDismiss = { zoomCameraPhoto = false }
                    )
                }
                BotanicalSpecimenCardView(
                    card = resultCard!!,
                    photoPath = lastSavedPath,
                    onPreviewPhoto = if (!lastSavedPath.isNullOrBlank()) { _ -> zoomCameraPhoto = true } else null,
                    onSaveVault = {
                        scope.launch {
                            val liveSaved = coordinator.promoteSpecimenToVault(resultCard!!, lastSavedPath)
                            OmsFeedbackController.show(
                                if (liveSaved) "已存入 Mac 知识库 FieldGuide" else "已加入同步队列，连上 Mac 自动归档",
                                OmsToastKind.SUCCESS
                            )
                        }
                    },
                    onFollowUp = { onAskFollowUpInChat(resultCard!!) },
                    onRetake = { resultCard = null }
                )
            } else {
                // Sleek Optional Context Input Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x8818181B))
                        .border(0.6.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    if (questionInput.isEmpty()) {
                        Text("可选：补充问题（如“适合室内北向阳台吗？”）", color = Color(0xAAFFFFFF), fontSize = 13.5.sp)
                    }
                    BasicTextField(
                        value = questionInput,
                        onValueChange = { questionInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(Color(0xFF34D399)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Album Picker
                    OmsCircleIconButton(
                        icon = OmsIcons.Image,
                        contentDescription = "相册选图",
                        size = 46.dp,
                        iconSize = 21.dp,
                        enabled = !isAnalyzing,
                        containerColor = Color(0x6618181B),
                        borderColor = Color(0x33FFFFFF),
                        contentColor = Color.White,
                        onClick = {
                            albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )

                    // Center: Sculptural Double-Ring Shutter Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(76.dp)
                            .omsBounceClick(enabled = !isAnalyzing && hasCameraPerm, scaleDown = 0.90f, shape = CircleShape) {
                                isAnalyzing = true
                                errorMsg = null
                                coordinator.vibrateTick()
                                val photoDir = File(context.filesDir, "field_guide").apply { mkdirs() }
                                val outFile = File(photoDir, "specimen_${System.currentTimeMillis()}.jpg")
                                val outputOpts = ImageCapture.OutputFileOptions.Builder(outFile).build()
                                imageCapture.takePicture(
                                    outputOpts,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            scope.launch {
                                                try {
                                                    val compressed = compressJpegFile(outFile, maxDim = 1440)
                                                    lastSavedPath = outFile.absolutePath
                                                    resultCard = coordinator.identifyBotanicalSpecimen(
                                                        compressed,
                                                        outFile.absolutePath,
                                                        questionInput
                                                    )
                                                } catch (e: Throwable) {
                                                    errorMsg = "识别出错：${e.message}"
                                                } finally {
                                                    isAnalyzing = false
                                                }
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            isAnalyzing = false
                                            errorMsg = "拍照失败：${exception.message}"
                                        }
                                    }
                                )
                            }
                            .border(3.dp, Color.White, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(if (isAnalyzing) Color(0xFF059669) else Color.White)
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = OmsIcons.Leaf,
                                contentDescription = "拍照识别",
                                tint = Color(0xFF064E3B),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // Symmetric Spacer
                    Spacer(modifier = Modifier.size(46.dp))
                }
            }
        }
    }
}

// =============================================================================
// Museum-Grade Botanical Field-Guide Plate Card (Theme-Aware for Light & Dark!)
// =============================================================================
@Composable
fun BotanicalSpecimenCardView(
    card: FieldGuideSpecimenCard,
    onSaveVault: () -> Unit,
    onFollowUp: (() -> Unit)? = null,
    onRetake: (() -> Unit)? = null,
    photoPath: String? = null,
    onPreviewPhoto: ((String) -> Unit)? = null,
    maxHeight: Dp = 460.dp
) {
    val palette = OmsTheme.colors
    OmsSurfaceCard(
        containerColor = palette.cardSurface,
        borderColor = palette.strongBorder,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!photoPath.isNullOrBlank()) {
                val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = photoPath) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(photoPath)
                            if (f.exists()) BitmapFactory.decodeFile(photoPath)?.asImageBitmap() else null
                        }.getOrNull()
                    }
                }
                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.6.dp, palette.hairline, RoundedCornerShape(14.dp))
                            .clickable(enabled = onPreviewPhoto != null) { onPreviewPhoto?.invoke(photoPath) }
                    ) {
                        Image(
                            bitmap = bmp!!,
                            contentDescription = card.chineseName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (onPreviewPhoto != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xAA090D16))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Icon(OmsIcons.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.5.dp))
                                Text("点击放大查看原图", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(OmsIcons.Leaf, contentDescription = null, tint = palette.success, modifier = Modifier.size(18.dp))
                        Text(
                            text = card.chineseName,
                            color = palette.textPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (card.latinName.isNotBlank()) {
                        Text(
                            text = card.latinName,
                            color = palette.textSecondary,
                            fontSize = 13.5.sp,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.successSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${card.confidencePercent}% 吻合",
                        color = palette.success,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (card.familyGenus.isNotBlank() || card.commonAliases.isNotBlank()) {
                Text(
                    text = listOfNotNull(
                        card.familyGenus.takeIf { it.isNotBlank() },
                        card.commonAliases.takeIf { it.isNotBlank() }?.let { "别名：$it" }
                    ).joinToString("  ·  "),
                    color = palette.textTertiary,
                    fontSize = 12.sp
                )
            }

            if (card.oneLineSummary.isNotBlank()) {
                Text(
                    text = card.oneLineSummary,
                    color = palette.textPrimary,
                    fontSize = 14.5.sp,
                    lineHeight = 22.sp
                )
            }

            if (card.keyMorphology.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
                Text("鉴定特征", color = palette.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                card.keyMorphology.forEach { m ->
                    Text("•  $m", color = palette.textSecondary, fontSize = 13.sp, lineHeight = 19.5.sp)
                }
            }

            if (card.lookalikeComparison.isNotBlank()) {
                Text("近似种辨析", color = palette.warning, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(card.lookalikeComparison, color = palette.textSecondary, fontSize = 13.sp, lineHeight = 19.5.sp)
            }

            if (card.bloomAndHabitat.isNotBlank()) {
                Text("花果期与生境", color = palette.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(card.bloomAndHabitat, color = palette.textSecondary, fontSize = 13.sp, lineHeight = 19.5.sp)
            }

            if (card.safetyOrCareNote.isNotBlank()) {
                Text("养护与提示：${card.safetyOrCareNote}", color = palette.textTertiary, fontSize = 12.5.sp, lineHeight = 19.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmsPrimaryButton(
                    label = "收藏图鉴",
                    icon = OmsIcons.Bookmark,
                    height = 36.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onSaveVault
                )
                if (onFollowUp != null) {
                    OmsSecondaryButton(
                        label = "继续追问",
                        icon = OmsIcons.Sparkles,
                        height = 36.dp,
                        modifier = Modifier.weight(1f),
                        onClick = onFollowUp
                    )
                }
                if (onRetake != null) {
                    OmsSecondaryButton(
                        label = "重拍",
                        height = 36.dp,
                        onClick = onRetake
                    )
                }
            }
        }
    }
}

/**
 * Full-screen Pinch-to-Zoom (`1x..4.5x`) + Double-Tap Zoom (`1x <-> 2.6x`) Lightbox for Specimen & Chat Images.
 */
@Composable
fun OmsZoomableImageLightbox(
    imagePath: String,
    titleHint: String = "",
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = imagePath) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(imagePath)
                    if (f.exists()) BitmapFactory.decodeFile(imagePath)?.asImageBitmap() else null
                }.getOrNull()
            }
        }
        var scale by remember(imagePath) { mutableFloatStateOf(1f) }
        var offsetX by remember(imagePath) { mutableFloatStateOf(0f) }
        var offsetY by remember(imagePath) { mutableFloatStateOf(0f) }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2050609))
                .pointerInput(imagePath) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.08f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.6f
                            }
                        },
                        onTap = {
                            if (scale <= 1.05f) onDismiss()
                        }
                    )
                }
                .pointerInput(imagePath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4.5f)
                        scale = nextScale
                        if (nextScale <= 1.02f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            val maxPanX = (size.width * (nextScale - 1f)) * 0.5f
                            val maxPanY = (size.height * (nextScale - 1f)) * 0.5f
                            offsetX = (offsetX + pan.x).coerceIn(-maxPanX, maxPanX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxPanY, maxPanY)
                        }
                    }
                }
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp!!,
                    contentDescription = titleHint.ifBlank { "放大预览" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                )
            } else {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }

            // Top Bar: Title + Reset Zoom + Close
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (titleHint.isNotBlank()) {
                        Text(titleHint, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = if (scale > 1.08f) "缩放 ${String.format("%.1fx", scale)} · 双击还原" else "双指捏合或双击可放大查看细部",
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp
                    )
                }
                OmsCircleIconButton(
                    icon = OmsIcons.Close,
                    contentDescription = "关闭预览",
                    containerColor = Color(0x44FFFFFF),
                    contentColor = Color.White,
                    onClick = onDismiss
                )
            }
        }
    }
}

// =============================================================================
// 2. Full-Duplex Live Voice & Google Glass Camera Cabin (Fluid Acoustic Orb)
// =============================================================================
@Composable
fun LiveVoiceAndGlassModal(
    coordinator: CockpitRuntimeCoordinator,
    initialGlassCameraMode: Boolean = false,
    onDismissAndArchiveSummary: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val liveState by coordinator.liveVoiceController.stateFlow.collectAsState()

    var selectedMode by remember {
        mutableStateOf(
            if (initialGlassCameraMode) RealtimeBackendMode.GEMINI_GLASS_LIVE
            else RealtimeBackendMode.OPENAI_WALK_TALK
        )
    }
    var englishOnly by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var openAiVoiceId by remember {
        mutableStateOf(
            OmsApplication.instance?.edgeStore?.loadLiveVoiceName(RealtimeBackendMode.OPENAI_WALK_TALK)
                ?: RealtimeVoiceCatalog.defaultVoiceFor(RealtimeBackendMode.OPENAI_WALK_TALK)
        )
    }
    var geminiVoiceId by remember {
        mutableStateOf(
            OmsApplication.instance?.edgeStore?.loadLiveVoiceName(RealtimeBackendMode.GEMINI_GLASS_LIVE)
                ?: RealtimeVoiceCatalog.defaultVoiceFor(RealtimeBackendMode.GEMINI_GLASS_LIVE)
        )
    }
    var showVoicePicker by remember { mutableStateOf(false) }

    val activeVoicePreset = remember(selectedMode, openAiVoiceId, geminiVoiceId) {
        val raw = if (selectedMode == RealtimeBackendMode.OPENAI_WALK_TALK) openAiVoiceId else geminiVoiceId
        RealtimeVoiceCatalog.resolveVoice(selectedMode, raw)
    }
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val currentSelectedMode by rememberUpdatedState(selectedMode)
    val currentEnglishOnly by rememberUpdatedState(englishOnly)
    val currentActiveVoiceId by rememberUpdatedState(activeVoicePreset.id)

    fun finishAndArchive() {
        val turns = liveState.transcriptTurns
        coordinator.liveVoiceController.stop()
        val summary = if (turns.isNotEmpty()) {
            buildString {
                appendLine("**Live 全双工语音纪要** (${liveState.modelLabel.ifBlank { selectedMode.name }})")
                turns.forEach { (who, txt) -> appendLine("- **$who**：$txt") }
            }
        } else null
        onDismissAndArchiveSummary(summary)
    }

    BackHandler { finishAndArchive() }

    // Bind hardware volume keys to STREAM_VOICE_CALL while Live modal is active so user volume adjustments directly control Live voice!
    DisposableEffect(context) {
        val act = context as? Activity
        val prevStream = act?.volumeControlStream ?: AudioManager.USE_DEFAULT_STREAM_TYPE
        act?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        onDispose {
            act?.volumeControlStream = prevStream
            coordinator.liveVoiceController.stop()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        hasCameraPerm = grantMap[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = grantMap[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            val capsule = OmsApplication.instance?.edgeStore?.loadContextCapsule()
            coordinator.liveVoiceController.start(
                RealtimeSessionOptions(
                    mode = currentSelectedMode,
                    englishOnlyFluencyMode = currentEnglishOnly,
                    voiceName = currentActiveVoiceId,
                    extraContextSummary = capsule?.nowSummary.orEmpty()
                )
            )
        }
    }

    LaunchedEffect(selectedMode, englishOnly, activeVoicePreset.id) {
        hasCameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            needed.add(Manifest.permission.CAMERA)
        }
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            val capsule = OmsApplication.instance?.edgeStore?.loadContextCapsule()
            coordinator.liveVoiceController.start(
                RealtimeSessionOptions(
                    mode = selectedMode,
                    englishOnlyFluencyMode = englishOnly,
                    voiceName = activeVoicePreset.id,
                    extraContextSummary = capsule?.nowSummary.orEmpty()
                )
            )
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07090F), Color(0xFF0D1322), Color(0xFF07090D))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Mode Switcher + English Only Pill
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmsCircleIconButton(
                        icon = OmsIcons.Close,
                        contentDescription = "结束返回",
                        containerColor = Color(0x26FFFFFF),
                        contentColor = Color.White,
                        onClick = { finishAndArchive() }
                    )

                    val pillShape = RoundedCornerShape(18.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Voice / Timbre Selector Pill (Works for both OpenAI Walk&Talk & Gemini Glass Live!)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .omsBounceClick(scaleDown = 0.94f, shape = pillShape) { showVoicePicker = true }
                                .background(Color(0x26FFFFFF), pillShape)
                                .border(0.6.dp, Color(0x40FFFFFF), pillShape)
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        ) {
                            Icon(OmsIcons.Music, contentDescription = null, tint = Color(0xFF93C5FD), modifier = Modifier.size(13.5.dp))
                            Text(
                                text = activeVoicePreset.displayName,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(OmsIcons.ChevronDown, contentDescription = null, tint = Color(0xB3FFFFFF), modifier = Modifier.size(12.dp))
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .omsBounceClick(scaleDown = 0.94f, shape = pillShape) { englishOnly = !englishOnly }
                                .background(if (englishOnly) Color(0xFF2563EB) else Color(0x22FFFFFF), pillShape)
                                .border(0.6.dp, Color(0x38FFFFFF), pillShape)
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        ) {
                            Icon(OmsIcons.Globe, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.5.dp))
                            Text(
                                text = if (englishOnly) "EN Only" else "双语",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Mode Switcher Capsule
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x1FFFFFFF))
                        .border(0.5.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isWalk = selectedMode == RealtimeBackendMode.OPENAI_WALK_TALK
                    LiveModeChip(
                        icon = OmsIcons.Waveform,
                        label = "散步语音",
                        selected = isWalk,
                        onClick = { selectedMode = RealtimeBackendMode.OPENAI_WALK_TALK }
                    )
                    LiveModeChip(
                        icon = OmsIcons.Camera,
                        label = "Glass 实时镜头",
                        selected = !isWalk,
                        onClick = { selectedMode = RealtimeBackendMode.GEMINI_GLASS_LIVE }
                    )
                }
            }

            // Center Stage: Optional Glass Viewfinder + Organic Fluid Acoustic Orb + Live Subtitles
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.weight(0.4f))

                if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE && hasCameraPerm) {
                    val lastFrameSentAt = remember { AtomicLong(0L) }
                    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
                    DisposableEffect(selectedMode) {
                        onDispose { runCatching { analysisExecutor.shutdown() } }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(226.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(24.dp))
                    ) {
                        androidx.compose.runtime.key(useFrontCamera) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    val pv = PreviewView(ctx)
                                    val fut = ProcessCameraProvider.getInstance(ctx)
                                    fut.addListener({
                                        runCatching {
                                            val provider = fut.get()
                                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                                            val analyzer = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build().also { ia ->
                                                    ia.setAnalyzer(analysisExecutor) { proxy ->
                                                        try {
                                                            val now = System.currentTimeMillis()
                                                            if (now - lastFrameSentAt.get() >= 900L) {
                                                                val jpeg = imageProxyToJpeg(proxy, maxDim = 768)
                                                                if (jpeg != null) {
                                                                    lastFrameSentAt.set(now)
                                                                    coordinator.liveVoiceController.sendCameraJpegFrameIfGlassActive(jpeg)
                                                                }
                                                            }
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            proxy.close()
                                                        }
                                                    }
                                                }
                                            val selector = if (useFrontCamera) {
                                                CameraSelector.DEFAULT_FRONT_CAMERA
                                            } else {
                                                CameraSelector.DEFAULT_BACK_CAMERA
                                            }
                                            provider.unbindAll()
                                            provider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                    pv
                                }
                            )
                        }

                        // Top-Left Live Vision Frame Sync Indicator Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x9907090F))
                                .border(0.5.dp, Color(0x38FFFFFF), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (liveState.videoFramesSent > 0) Color(0xFF34D399) else Color(0xFFFBBF24),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (liveState.videoFramesSent > 0) {
                                    "视觉流已连通 · 已同步 ${liveState.videoFramesSent} 帧"
                                } else {
                                    "正在连接视觉画面流…"
                                },
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Top-Right Camera Flip Button (Back / Front)
                        OmsCircleIconButton(
                            icon = OmsIcons.Refresh,
                            contentDescription = "切换前后摄像头",
                            size = 34.dp,
                            iconSize = 16.dp,
                            containerColor = Color(0x9907090F),
                            borderColor = Color(0x38FFFFFF),
                            contentColor = Color.White,
                            onClick = { useFrontCamera = !useFrontCamera },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        )
                    }
                }

                FluidAcousticOrbCanvas(
                    audioLevel = liveState.audioLevel,
                    assistantSpeaking = liveState.assistantSpeaking,
                    micMuted = liveState.micMuted,
                    isGlassMode = selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE,
                    sizeDp = if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) 128 else 210
                )

                Text(
                    text = when {
                        liveState.statusHint.isNotBlank() -> liveState.statusHint
                        liveState.isConnecting -> if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
                            "正在建立低延迟视觉语音通道…"
                        } else {
                            "正在建立低延迟语音通道…"
                        }
                        liveState.assistantSpeaking -> "讲述中 · 随时开口自然打断"
                        liveState.micMuted -> "麦克风已静音"
                        else -> "正在聆听 · 支持实时联网查询…"
                    },
                    color = if (liveState.statusHint.isNotBlank()) Color(0xFF7DD3FC) else Color(0xB3FFFFFF),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                liveState.errorText?.let { err ->
                    Text(err, color = Color(0xFFFCA5A5), fontSize = 12.5.sp, textAlign = TextAlign.Center)
                }

                val subtitle = liveState.latestAssistantTranscript.takeLast(160).ifBlank {
                    liveState.latestUserTranscript.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty()
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = Color(0xE6FFFFFF),
                        fontSize = 15.sp,
                        lineHeight = 23.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(0.6f))
            }

            // Bottom Call-Grade Tactile Controls (Mic Mute | End Session | Loudness Boost)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OmsCircleIconButton(
                    icon = if (liveState.micMuted) OmsIcons.MicOff else OmsIcons.Mic,
                    contentDescription = if (liveState.micMuted) "取消静音" else "静音",
                    size = 54.dp,
                    iconSize = 22.dp,
                    containerColor = if (liveState.micMuted) Color.White else Color(0x26FFFFFF),
                    contentColor = if (liveState.micMuted) Color.Black else Color.White,
                    onClick = { coordinator.liveVoiceController.setMicMuted(!liveState.micMuted) }
                )

                OmsCircleIconButton(
                    icon = OmsIcons.Close,
                    contentDescription = "结束并归档纪要",
                    size = 64.dp,
                    iconSize = 26.dp,
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                    onClick = { finishAndArchive() }
                )

                OmsCircleIconButton(
                    icon = OmsIcons.Volume,
                    contentDescription = if (liveState.speakerBoostEnabled) "响度增强已开启" else "标准音量",
                    size = 54.dp,
                    iconSize = 22.dp,
                    containerColor = if (liveState.speakerBoostEnabled) Color(0x4038BDF8) else Color(0x26FFFFFF),
                    borderColor = if (liveState.speakerBoostEnabled) Color(0x8838BDF8) else Color.Transparent,
                    contentColor = Color.White,
                    onClick = {
                        val next = !liveState.speakerBoostEnabled
                        coordinator.liveVoiceController.setSpeakerBoostEnabled(next)
                        OmsFeedbackController.show(
                            if (next) "已开启外放语音响度增强" else "已切换为原始标准响度",
                            com.oms.client.ui.theme.OmsToastKind.INFO
                        )
                    }
                )
            }
        }

        if (showVoicePicker) {
            LiveVoicePickerDialog(
                mode = selectedMode,
                selectedVoiceId = activeVoicePreset.id,
                onDismiss = { showVoicePicker = false },
                onSelectVoice = { preset ->
                    OmsApplication.instance?.edgeStore?.saveLiveVoiceName(selectedMode, preset.id)
                    if (selectedMode == RealtimeBackendMode.OPENAI_WALK_TALK) {
                        openAiVoiceId = preset.id
                    } else {
                        geminiVoiceId = preset.id
                    }
                    showVoicePicker = false
                    OmsFeedbackController.show(
                        "已切换音色：${preset.displayName}（${preset.toneTag}）",
                        OmsToastKind.SUCCESS
                    )
                }
            )
        }
    }
}

@Composable
private fun LiveVoicePickerDialog(
    mode: RealtimeBackendMode,
    selectedVoiceId: String,
    onDismiss: () -> Unit,
    onSelectVoice: (RealtimeVoicePreset) -> Unit
) {
    val voices = remember(mode) { RealtimeVoiceCatalog.voicesFor(mode) }
    val engineTitle = if (mode == RealtimeBackendMode.OPENAI_WALK_TALK) {
        "散步实时语音 · 音色选择"
    } else {
        "Glass 实时视频语音 · 音色选择"
    }
    val engineSubtitle = if (mode == RealtimeBackendMode.OPENAI_WALK_TALK) {
        "OpenAI Realtime 原生音色（切换后自动无感重连生效）"
    } else {
        "Gemini 3.8 Live 演播室音色（切换后自动无感重连生效）"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF5111624))
                .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(engineTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(engineSubtitle, color = Color(0x99FFFFFF), fontSize = 11.5.sp)
                }
                OmsCircleIconButton(
                    icon = OmsIcons.Close,
                    contentDescription = "关闭",
                    size = 32.dp,
                    iconSize = 15.dp,
                    containerColor = Color(0x22FFFFFF),
                    contentColor = Color.White,
                    onClick = onDismiss
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                voices.forEach { preset ->
                    val isSelected = preset.id.equals(selectedVoiceId, ignoreCase = true)
                    val rowShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .omsBounceClick(scaleDown = 0.97f, shape = rowShape) { onSelectVoice(preset) }
                            .background(
                                if (isSelected) Color(0x3838BDF8) else Color(0x14FFFFFF),
                                rowShape
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.5.dp,
                                color = if (isSelected) Color(0xAA38BDF8) else Color(0x22FFFFFF),
                                shape = rowShape
                            )
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFF38BDF8) else Color(0x22FFFFFF))
                            ) {
                                Icon(
                                    imageVector = OmsIcons.Waveform,
                                    contentDescription = null,
                                    tint = if (isSelected) Color(0xFF07090F) else Color(0xCCFFFFFF),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = preset.displayName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = preset.toneTag,
                                    color = if (isSelected) Color(0xFFBAE6FD) else Color(0x99FFFFFF),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = OmsIcons.Check,
                                contentDescription = "当前音色",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(19.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .omsBounceClick(scaleDown = 0.95f, shape = shape, onClick = onClick)
            .background(if (selected) Color.White else Color.Transparent, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF090D16) else Color(0xCCFFFFFF),
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = if (selected) Color(0xFF090D16) else Color(0xCCFFFFFF),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 120fps Organic Multi-Lobe Fluid Acoustic Orb drawn directly on Compose Canvas.
 */
@Composable
private fun FluidAcousticOrbCanvas(
    audioLevel: Float,
    assistantSpeaking: Boolean,
    micMuted: Boolean,
    isGlassMode: Boolean,
    sizeDp: Int
) {
    val infinite = rememberInfiniteTransition(label = "fluidOrb")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "orbPhase"
    )
    val smoothAmp by animateFloatAsState(
        targetValue = if (micMuted) 0.02f else (audioLevel.coerceIn(0f, 1f) * 0.35f + if (assistantSpeaking) 0.18f else 0.06f),
        animationSpec = tween(90),
        label = "orbAmp"
    )

    val primaryCore = when {
        micMuted -> Color(0xFF64748B)
        assistantSpeaking -> Color(0xFF38BDF8)
        isGlassMode -> Color(0xFF34D399)
        else -> Color(0xFFA5B4FC)
    }
    val secondaryGlow = when {
        micMuted -> Color(0xFF334155)
        assistantSpeaking -> Color(0xFF4F46E5)
        isGlassMode -> Color(0xFF0D9488)
        else -> Color(0xFF6366F1)
    }

    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = size.minDimension * 0.31f

        // Outer Ambient Halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryGlow.copy(alpha = 0.36f), Color.Transparent),
                center = Offset(cx, cy),
                radius = baseR * (1.65f + smoothAmp * 0.9f)
            ),
            radius = baseR * (1.65f + smoothAmp * 0.9f),
            center = Offset(cx, cy)
        )

        // Two Counter-Rotating Organic Harmonic Lobes
        for (layer in 0..1) {
            val path = Path()
            val points = 64
            val dir = if (layer == 0) 1f else -1.3f
            val rScale = if (layer == 0) 1.12f else 1.04f
            for (i in 0..points) {
                val theta = (i.toFloat() / points) * (2f * PI.toFloat())
                val wave = sin(theta * 3f + phase * dir) * 0.08f +
                    cos(theta * 5f - phase * 1.6f * dir) * (smoothAmp * 0.45f)
                val r = baseR * (rScale + wave + smoothAmp * 0.35f)
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(
                path = path,
                color = if (layer == 0) secondaryGlow.copy(alpha = 0.42f) else primaryCore.copy(alpha = 0.50f)
            )
        }

        // Luminous Core Sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.92f), primaryCore, secondaryGlow),
                center = Offset(cx - baseR * 0.22f, cy - baseR * 0.22f),
                radius = baseR * (1.05f + smoothAmp * 0.25f)
            ),
            radius = baseR * (0.94f + smoothAmp * 0.22f),
            center = Offset(cx, cy)
        )
    }
}

private suspend fun loadAndCompressUriToJpeg(context: Context, uri: Uri): Pair<ByteArray, String?> =
    withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选图片")
        val dir = File(context.filesDir, "field_guide").apply { mkdirs() }
        val saved = File(dir, "picked_${System.currentTimeMillis()}.jpg")
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: error("图片解码失败")
        val scaled = scaleDownBitmap(bmp, 1440)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, bos)
        val out = bos.toByteArray()
        saved.writeBytes(out)
        out to saved.absolutePath
    }

private suspend fun compressJpegFile(file: File, maxDim: Int): ByteArray = withContext(Dispatchers.IO) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext file.readBytes()
    val scaled = scaleDownBitmap(bmp, maxDim)
    val bos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 86, bos)
    val bytes = bos.toByteArray()
    file.writeBytes(bytes)
    bytes
}

private fun scaleDownBitmap(src: Bitmap, maxDim: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longest = maxOf(w, h)
    if (longest <= maxDim) return src
    val ratio = maxDim.toFloat() / longest.toFloat()
    return Bitmap.createScaledBitmap(src, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
}

/**
 * Converts CameraX `ImageProxy` (`YUV_420_888` or `RGBA_8888`) into an upright, scaled JPEG frame
 * using CameraX 1.3's stride-safe `toBitmap()` + `imageInfo.rotationDegrees` rotation matrix.
 */
private fun imageProxyToJpeg(image: ImageProxy, maxDim: Int): ByteArray? {
    val rawBitmap = image.toBitmap()
    val rot = image.imageInfo.rotationDegrees
    val oriented = if (rot != 0) {
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, m, true).also {
            if (it !== rawBitmap) rawBitmap.recycle()
        }
    } else {
        rawBitmap
    }
    val scaled = scaleDownBitmap(oriented, maxDim)
    if (scaled !== oriented) {
        oriented.recycle()
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    scaled.recycle()
    return out.toByteArray().takeIf { it.isNotEmpty() }
}
