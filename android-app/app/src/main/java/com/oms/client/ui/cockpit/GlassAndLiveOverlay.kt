package com.oms.client.ui.cockpit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.oms.client.cockpit.CockpitRuntimeCoordinator
import com.oms.core.ai.FieldGuideSpecimenCard
import com.oms.core.ai.RealtimeBackendMode
import com.oms.core.ai.RealtimeSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Milestone 4 (v2.0):
 *  1) `BotanicalGlassCameraModal` — CameraX Google Glass viewfinder -> `gemini-4-argon` + Search Grounding -> `🌿 博物图鉴卡`
 *  2) `LiveVoiceAndGlassModal` — Full-duplex Walk & Talk (`gpt-realtime-2.1` + `[🇬🇧 English Only]`) & `gemini-3.8-live` 1fps live camera!
 */
@Composable
fun BotanicalGlassCameraModal(
    coordinator: CockpitRuntimeCoordinator,
    onDismiss: () -> Unit,
    onAskFollowUpInChat: (FieldGuideSpecimenCard) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
                    errorMsg = "识物失败：${e.message}"
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050811))
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📷 需要相机权限以开启博物之眼镜头", color = Color.White, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予相机权限")
                    }
                }
            }
        }

        // Top Glass HUD Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC050811), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "✕ 返回对话",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x881E293B))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                text = "🌿 博物之眼 · Gemini 4 Argon",
                color = Color(0xFF6EE7B7),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (torchEnabled) "🔦 补光开" else "🔦 补光",
                color = if (torchEnabled) Color(0xFFFDE047) else Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x881E293B))
                    .clickable {
                        torchEnabled = !torchEnabled
                        boundCamera?.cameraControl?.enableTorch(torchEnabled)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Center Reticle Frame when waiting for capture
        if (resultCard == null && !isAnalyzing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(1.5.dp, Color(0x9934D399), RoundedCornerShape(24.dp))
            )
        }

        // Bottom Controls OR Specimen Result Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xEE0B1120), Color(0xFF0B1120))
                    )
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            errorMsg?.let { err ->
                Text(err, color = Color(0xFFFCA5A5), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (resultCard != null) {
                BotanicalSpecimenCardView(
                    card = resultCard!!,
                    onSaveVault = {
                        scope.launch {
                            val liveSaved = coordinator.promoteSpecimenToVault(resultCard!!, lastSavedPath)
                            Toast.makeText(
                                context,
                                if (liveSaved) "🌿 已存入 Mac 知识库 FieldGuide" else "🌿 已加入待同步队列，连上 Mac 自动归档",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onFollowUp = {
                        val c = resultCard!!
                        onAskFollowUpInChat(c)
                    },
                    onRetake = { resultCard = null }
                )
            } else {
                OutlinedTextField(
                    value = questionInput,
                    onValueChange = { questionInput = it },
                    placeholder = { Text("可选：补充提问（如“能养在室内北阳台吗？”）", color = Color(0xFF94A3B8), fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedContainerColor = Color(0xAA0F172A),
                        unfocusedContainerColor = Color(0xAA0F172A)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled = !isAnalyzing,
                        onClick = {
                            albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) {
                        Text("🖼️ 相册选图", color = Color.White)
                    }

                    // Shutter Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(if (isAnalyzing) Color(0xFF065F46) else Color(0xFF10B981))
                            .border(4.dp, Color.White, CircleShape)
                            .clickable(enabled = !isAnalyzing && hasCameraPerm) {
                                isAnalyzing = true
                                errorMsg = null
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
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                        } else {
                            Text("🌿", fontSize = 28.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(88.dp))
                }
            }
        }
    }
}

@Composable
fun BotanicalSpecimenCardView(
    card: FieldGuideSpecimenCard,
    onSaveVault: () -> Unit,
    onFollowUp: (() -> Unit)? = null,
    onRetake: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0F1F1C))
            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🌿 ${card.chineseName}",
                    color = Color(0xFFECFDF5),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (card.latinName.isNotBlank()) {
                    Text(
                        text = card.latinName,
                        color = Color(0xFF6EE7B7),
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF064E3B))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("${card.confidencePercent}% 吻合", color = Color(0xFFA7F3D0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (card.familyGenus.isNotBlank() || card.commonAliases.isNotBlank()) {
            Text(
                text = listOfNotNull(
                    card.familyGenus.takeIf { it.isNotBlank() },
                    card.commonAliases.takeIf { it.isNotBlank() }?.let { "俗名：$it" }
                ).joinToString(" ｜ "),
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }

        if (card.oneLineSummary.isNotBlank()) {
            Text(card.oneLineSummary, color = Color(0xFFD1FAE5), fontSize = 13.5.sp, lineHeight = 20.sp)
        }

        if (card.keyMorphology.isNotEmpty()) {
            Text("🔬 画面鉴定特征", color = Color(0xFF34D399), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            card.keyMorphology.forEach { m ->
                Text("• $m", color = Color(0xFFE2E8F0), fontSize = 12.5.sp, lineHeight = 18.sp)
            }
        }

        if (card.lookalikeComparison.isNotBlank()) {
            Text("⚖️ 易混种一眼区分", color = Color(0xFFFBBF24), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(card.lookalikeComparison, color = Color(0xFFFEF3C7), fontSize = 12.5.sp, lineHeight = 18.sp)
        }

        if (card.bloomAndHabitat.isNotBlank()) {
            Text("🌦️ 物候与背景：${card.bloomAndHabitat}", color = Color(0xFFCBD5E1), fontSize = 12.sp, lineHeight = 18.sp)
        }

        if (card.safetyOrCareNote.isNotBlank()) {
            Text("💡 养护/安全：${card.safetyOrCareNote}", color = Color(0xFF93C5FD), fontSize = 12.sp, lineHeight = 18.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveVault,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                modifier = Modifier.weight(1f)
            ) {
                Text("📚 收藏博物图鉴", fontSize = 12.sp)
            }
            if (onFollowUp != null) {
                OutlinedButton(onClick = onFollowUp, modifier = Modifier.weight(1f)) {
                    Text("💬 继续追问", color = Color(0xFF6EE7B7), fontSize = 12.sp)
                }
            }
            if (onRetake != null) {
                OutlinedButton(onClick = onRetake) {
                    Text("重拍", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

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

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val capsule = com.oms.client.OmsApplication.instance?.edgeStore?.loadContextCapsule()
        coordinator.liveVoiceController.start(
            RealtimeSessionOptions(
                mode = selectedMode,
                englishOnlyFluencyMode = englishOnly,
                extraContextSummary = capsule?.nowSummary.orEmpty()
            )
        )
    }

    LaunchedEffect(selectedMode, englishOnly) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
            needed.add(Manifest.permission.CAMERA)
        }
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            val capsule = com.oms.client.OmsApplication.instance?.edgeStore?.loadContextCapsule()
            coordinator.liveVoiceController.start(
                RealtimeSessionOptions(
                    mode = selectedMode,
                    englishOnlyFluencyMode = englishOnly,
                    extraContextSummary = capsule?.nowSummary.orEmpty()
                )
            )
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coordinator.liveVoiceController.stop()
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "orb")
    val basePulse by pulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )
    val dynamicScale = basePulse + (liveState.audioLevel * 0.28f) + (if (liveState.assistantSpeaking) 0.10f else 0f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070B19), Color(0xFF0F172A), Color(0xFF090D16))
                )
            )
    ) {
        // Optional 1fps Google Glass CameraX feed when GEMINI_GLASS_LIVE is active!
        if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            val lastFrameSentAt = remember { AtomicLong(0L) }
            val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(selectedMode) {
                onDispose { runCatching { analysisExecutor.shutdown() } }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(horizontal = 16.dp, vertical = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp)),
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
                                        val now = System.currentTimeMillis()
                                        if (now - lastFrameSentAt.get() >= 1000L) {
                                            lastFrameSentAt.set(now)
                                            runCatching {
                                                imageProxyToJpeg(proxy, maxWidth = 640)?.let { jpeg ->
                                                    coordinator.liveVoiceController.sendCameraJpegFrameIfGlassActive(jpeg)
                                                }
                                            }
                                        }
                                        proxy.close()
                                    }
                                }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Mode Switcher + `[🇬🇧 English Only]` Pill
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎧 咫尺 Live 舱",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (englishOnly) Color(0xFF1D4ED8) else Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(16.dp))
                            .clickable { englishOnly = !englishOnly }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (englishOnly) "🇬🇧 English Only · ON" else "🇬🇧 English Only",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF131C31))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isWalk = selectedMode == RealtimeBackendMode.OPENAI_WALK_TALK
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isWalk) Color(0xFF0284C7) else Color.Transparent)
                            .clickable { selectedMode = RealtimeBackendMode.OPENAI_WALK_TALK }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("🚶 散步对谈 (GPT Realtime 2.1)", color = Color.White, fontSize = 12.sp)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!isWalk) Color(0xFF059669) else Color.Transparent)
                            .clickable { selectedMode = RealtimeBackendMode.GEMINI_GLASS_LIVE }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("👓 Glass 实时镜头 (Gemini 3.8 Live)", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // Center Breathing Acoustic Orb
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) 180.dp else 0.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) 110.dp else 168.dp)
                        .scale(dynamicScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                if (liveState.assistantSpeaking) {
                                    listOf(Color(0xFF38BDF8), Color(0xFF1D4ED8), Color(0x220284C7))
                                } else if (selectedMode == RealtimeBackendMode.GEMINI_GLASS_LIVE) {
                                    listOf(Color(0xFF34D399), Color(0xFF059669), Color(0x22047857))
                                } else {
                                    listOf(Color(0xFF818CF8), Color(0xFF4338CA), Color(0x22312E81))
                                }
                            )
                        )
                ) {
                    Text(
                        text = when {
                            liveState.isConnecting -> "连接中…"
                            liveState.assistantSpeaking -> "🔊 讲述中\n(开口即打断)"
                            liveState.micMuted -> "🔇 已静音"
                            else -> "🎙️ 聆听中"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                liveState.errorText?.let { err ->
                    Text(err, color = Color(0xFFFCA5A5), fontSize = 12.sp)
                }
                if (liveState.latestAssistantTranscript.isNotBlank()) {
                    Text(
                        text = liveState.latestAssistantTranscript.takeLast(180),
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else if (liveState.latestUserTranscript.isNotBlank()) {
                    Text(
                        text = "“${liveState.latestUserTranscript}”",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }
            }

            // Bottom Hangup & Mute Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { coordinator.liveVoiceController.setMicMuted(!liveState.micMuted) }
                ) {
                    Text(if (liveState.micMuted) "🎙️ 恢复麦克风" else "🔇 暂时静音", color = Color.White)
                }

                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    onClick = {
                        val turns = liveState.transcriptTurns
                        coordinator.liveVoiceController.stop()
                        val summary = if (turns.isNotEmpty()) {
                            buildString {
                                appendLine("🎧 **Live 散步/识物对谈纪要** (${liveState.modelLabel.ifBlank { selectedMode.name }})")
                                turns.forEach { (who, txt) -> appendLine("- **$who**：$txt") }
                            }
                        } else null
                        onDismissAndArchiveSummary(summary)
                    }
                ) {
                    Text("⏹ 结束并归档纪要", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
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

private fun imageProxyToJpeg(image: ImageProxy, maxWidth: Int): ByteArray? {
    if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
    val yBuf = image.planes[0].buffer
    val uBuf = image.planes[1].buffer
    val vBuf = image.planes[2].buffer
    val ySize = yBuf.remaining()
    val uSize = uBuf.remaining()
    val vSize = vBuf.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuf.get(nv21, 0, ySize)
    vBuf.get(nv21, ySize, vSize)
    uBuf.get(nv21, ySize + vSize, uSize)

    val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val rawBos = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 72, rawBos)
    val rawJpeg = rawBos.toByteArray()
    val bmp = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
    val rot = image.imageInfo.rotationDegrees
    val transformed = if (rot != 0) {
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    } else bmp
    val scaled = scaleDownBitmap(transformed, maxWidth)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
    return out.toByteArray()
}
