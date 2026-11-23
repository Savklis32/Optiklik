package com.optiklik.app.ui.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.optiklik.app.domain.omr.OmrReadResult
import com.optiklik.app.ui.components.QuestionScoresGrid
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScannerScreen(
    viewModel: LiveScannerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Play beep + vibrate on successful save; keep banner visible if any warnings exist
    LaunchedEffect(uiState.feedbackTriggerId) {
        if (uiState.feedbackTriggerId > 0L) {
            triggerSuccessFeedback(context)
            val res = uiState.lastSavedResult
            val hasWarnings = uiState.lastSavedWarnings.isNotEmpty()
            if (res != null && !hasWarnings && !res.hasTotalMismatch && res.isTcValid && !res.needsManualReview) {
                kotlinx.coroutines.delay(1200L)
                viewModel.dismissSavedBanner()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.examTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            "Otomatik Tara",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = uiState.isAutoScanEnabled,
                            onCheckedChange = { viewModel.toggleAutoScan(it) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (!hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("Optik form okumak için kamera izni gereklidir.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Kamera İzni Ver")
                    }
                }
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(Unit) {
                onDispose { analysisExecutor.shutdown() }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        @Suppress("DEPRECATION")
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(android.util.Size(1920, 1080))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                    try {
                                        val bmp = imageProxy.toBitmap()
                                        viewModel.processFrame(bmp, imageProxy.imageInfo.rotationDegrees)
                                        bmp.recycle()
                                    } finally {
                                        imageProxy.close()
                                    }
                                }
                            }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (_: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            // Transparent OMR Guide Frame Overlay (Green when detected, Red otherwise)
            val guideColor = if (uiState.guidance.isReady) Color(0xFF22C55E) else Color(0xFFEF4444)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rectW = size.width * 0.94f
                val rectH = rectW / 2.85f
                val left = (size.width - rectW) / 2f
                val top = (size.height - rectH) / 2f

                // Dimmed surround
                drawRect(color = Color.Black.copy(alpha = 0.45f))
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(12f, 12f),
                    blendMode = BlendMode.Clear
                )
                drawRoundRect(
                    color = guideColor,
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 6f)
                )

                // Draw green target crosshairs (+) aligned to PreviewView FIT_CENTER 28%..72% band
                val frameAspect = uiState.frameAspectRatio.coerceIn(0.3f, 2.0f)
                val viewAspect = size.width / size.height.coerceAtLeast(1f)
                val (pvW, pvH) = if (frameAspect < viewAspect) {
                    (size.height * frameAspect) to size.height
                } else {
                    size.width to (size.width / frameAspect)
                }
                val pvLeft = (size.width - pvW) / 2f
                val pvTop = (size.height - pvH) / 2f
                val roiScreenW = pvW
                val roiScreenH = pvH * 0.64f
                val roiLeft = pvLeft
                val roiTop = pvTop + pvH * 0.18f

                uiState.markerPointsNormalized.forEach { (nx, ny) ->
                    val cx = roiLeft + nx * roiScreenW
                    val cy = roiTop + ny * roiScreenH
                    drawCircle(
                        color = Color(0xFF22C55E),
                        radius = 22f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 4f)
                    )
                    drawLine(
                        color = Color(0xFF22C55E),
                        start = Offset(cx - 16f, cy),
                        end = Offset(cx + 16f, cy),
                        strokeWidth = 4f
                    )
                    drawLine(
                        color = Color(0xFF22C55E),
                        start = Offset(cx, cy - 16f),
                        end = Offset(cx, cy + 16f),
                        strokeWidth = 4f
                    )
                }
            }

            // Real-time calibration guidance & yellow directory warning banners
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = if (uiState.guidance.isReady) {
                        Color(0xFF15803D).copy(alpha = 0.9f)
                    } else {
                        Color(0xFFB91C1C).copy(alpha = 0.9f)
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    val guidanceText = when {
                        uiState.guidance.isReady && uiState.isAutoScanEnabled ->
                            "4/4 Köşe Kilitlendi (${uiState.stableFrameCount}/${LiveScannerViewModel.REQUIRED_STABLE_FRAMES})"
                        uiState.guidance.isReady ->
                            "4/4 Köşe Kilitlendi • Tara'ya dokunun"
                        else -> uiState.guidance.message
                    }
                    Text(
                        text = guidanceText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                uiState.notInDirectoryWarning?.let { warningText ->
                    Surface(
                        color = Color(0xFFD97706).copy(alpha = 0.95f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = warningText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { viewModel.dismissNotInDirectoryWarning() },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Kapat",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Tap-to-Scan shutter button at bottom-center (instant scan from latest ImageAnalysis frame)
            if (!uiState.showSavedBanner) {
                LargeFloatingActionButton(
                    onClick = { viewModel.captureLatestFrameAndScan() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
                    shape = CircleShape,
                    containerColor = if (uiState.guidance.isReady) Color(0xFF16A34A) else Color(0xFF2563EB),
                    contentColor = Color.White
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Tara / Çek",
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Tara",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Compact auto-dismissing last saved summary card at bottom
            val savedRes = uiState.lastSavedResult
            if (uiState.showSavedBanner && savedRes != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Kayıt Başarılı!",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${savedRes.totalScore} Puan",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = { viewModel.dismissSavedBanner() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Kapat",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val studentLabel = uiState.lastSavedStudentName?.takeIf { it.isNotBlank() } ?: "Listede Yok"
                        val okulNoLabel = uiState.lastSavedOkulNo?.takeIf { it.isNotBlank() } ?: "-"
                        val tcLabel = uiState.lastSavedTc?.takeIf { it.isNotBlank() } ?: savedRes.tcKimlikNo.ifBlank { "-" }
                        Text(
                            "$studentLabel | Okul No: $okulNoLabel | T.C.: $tcLabel",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(6.dp))
                        QuestionScoresGrid(scores = savedRes.scores)
                        val bannerWarnings = if (uiState.lastSavedWarnings.isNotEmpty()) {
                            uiState.lastSavedWarnings
                        } else if (savedRes.hasTotalMismatch) {
                            listOf("TOPLAM uyuşmazlığı (Kodlanan: ${savedRes.codedTotalScore ?: "-"})")
                        } else {
                            emptyList()
                        }
                        if (bannerWarnings.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = bannerWarnings.joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB45309),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (savedRes.hasTotalMismatch || !savedRes.isTcValid || savedRes.needsManualReview) {
                            savedRes.debugBitmap?.takeIf { !it.isRecycled }?.let { dbg ->
                                Spacer(Modifier.height(6.dp))
                                Image(
                                    bitmap = dbg.asImageBitmap(),
                                    contentDescription = "Okunan Optik Form Önizleme",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Duplicate TC confirmation dialog
    uiState.duplicateCandidate?.let { (newRead, _) ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicate() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Mükerrer Öğrenci") },
            text = {
                Text(
                    "Bu öğrenci (${newRead.tcKimlikNo}) zaten okutuldu. Güncellensin mi?\n\n" +
                        "Yeni Puanlar: ${newRead.scores.joinToString(", ")} (Toplam: ${newRead.totalScore})"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmDuplicateUpdate() }) {
                    Text("Güncelle")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDuplicate() }) {
                    Text("Vazgeç")
                }
            }
        )
    }

    // Invalid TC correction dialog
    uiState.invalidTcCandidate?.let { candidate ->
        InvalidTcFixDialog(
            initialResult = candidate,
            onSave = { tc, scores -> viewModel.saveCorrectedInvalidTc(tc, scores) },
            onDismiss = { viewModel.dismissInvalidTc() }
        )
    }
}

@Composable
private fun InvalidTcFixDialog(
    initialResult: OmrReadResult,
    onSave: (String, List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var tcText by remember { mutableStateOf(initialResult.tcKimlikNo.replace("?", "")) }
    val scoreStates = remember {
        mutableStateListOf<String>().apply {
            (0 until 10).forEach { i -> add(initialResult.scores.getOrElse(i) { 0 }.toString()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eksik T.C. Hanesi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                initialResult.debugBitmap?.let { dbg ->
                    Image(
                        bitmap = dbg.asImageBitmap(),
                        contentDescription = "Okunan Optik Form Önizleme",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                    )
                }
                Text(
                    "T.C. Kimlik numarasında okunamayan hane var. Düzelterek kaydedebilirsiniz:",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = tcText,
                    onValueChange = { if (it.length <= 11) tcText = it.filter { c -> c.isDigit() } },
                    label = { Text("T.C. Kimlik No (11 Hane)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..4).forEach { idx ->
                        OutlinedTextField(
                            value = scoreStates[idx],
                            onValueChange = { scoreStates[idx] = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("S${idx + 1}") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (5..9).forEach { idx ->
                        OutlinedTextField(
                            value = scoreStates[idx],
                            onValueChange = { scoreStates[idx] = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("S${idx + 1}") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val scores = scoreStates.map { it.toIntOrNull() ?: 0 }
                    onSave(tcText, scores)
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Atla")
            }
        }
    )
}

private fun triggerSuccessFeedback(context: Context) {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { toneGen.release() } },
            250L
        )
    } catch (_: Exception) {
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (_: Exception) {
    }
}
