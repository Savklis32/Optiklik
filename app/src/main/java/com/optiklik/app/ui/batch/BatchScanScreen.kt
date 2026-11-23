package com.optiklik.app.ui.batch

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.optiklik.app.service.BatchPageReport
import com.optiklik.app.ui.components.QuestionScoresGrid
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScanScreen(
    viewModel: BatchScanViewModel,
    onBack: () -> Unit,
    onViewResults: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingEntry by remember { mutableStateOf<Pair<Int, BatchPageReport>?>(null) }
    var debugImagePathToView by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.startBatchScan(uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dosyadan Toplu Tarama", fontWeight = FontWeight.Bold)
                        Text(
                            uiState.examTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "JPG, PNG veya Çok Sayfalı PDF Seçin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Çok sayfalı PDF dosyalarındaki her sayfa otomatik olarak yüksek çözünürlüklü görüntüye dönüştürülüp OMR motorundan geçirilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                filePicker.launch(arrayOf("image/jpeg", "image/png", "application/pdf"))
                            },
                            enabled = !uiState.isScanning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (uiState.isScanning) "Taranıyor..." else "Dosya / PDF Seç")
                        }
                        OutlinedButton(
                            onClick = onViewResults,
                            enabled = !uiState.isScanning
                        ) {
                            Text("Sonuçlar")
                        }
                    }
                }
            }

            uiState.progress?.let { progress ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val fraction = if (progress.totalCount > 0) {
                            progress.processedCount.toFloat() / progress.totalCount.toFloat()
                        } else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "İlerleme: ${progress.processedCount} / ${progress.totalCount}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Başarılı: ${progress.successCount} | Hatalı: ${progress.failureCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(progress.reports) { idx, report ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when {
                                            !report.success -> Icons.Default.Error
                                            report.needsManualReview -> Icons.Default.Warning
                                            else -> Icons.Default.CheckCircle
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            !report.success -> MaterialTheme.colorScheme.error
                                            report.needsManualReview -> Color(0xFFD97706)
                                            else -> Color(0xFF16A34A)
                                        }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(report.label, fontWeight = FontWeight.SemiBold)
                                            report.readResult?.let { read ->
                                                Text(
                                                    "${read.totalScore} Puan",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            report.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        report.readResult?.let { read ->
                                            Spacer(Modifier.height(6.dp))
                                            QuestionScoresGrid(scores = read.scores)
                                        }
                                    }
                                }
                                if (report.debugImagePath != null ||
                                    ((!report.success || report.needsManualReview) && report.readResult != null)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        if (report.debugImagePath != null) {
                                            val debugFileExists = remember(report.debugImagePath) {
                                                File(report.debugImagePath).exists()
                                            }
                                            if (debugFileExists) {
                                                TextButton(onClick = { debugImagePathToView = report.debugImagePath }) {
                                                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Debug Görseli", maxLines = 1)
                                                }
                                            } else {
                                                Text(
                                                    text = "Görüntü silindi",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                                                )
                                            }
                                        }
                                        if ((!report.success || report.needsManualReview) && report.readResult != null) {
                                            TextButton(onClick = { editingEntry = idx to report }) {
                                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Düzenle", maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    debugImagePathToView?.let { path ->
        val bmp = remember(path) { BitmapFactory.decodeFile(path) }
        AlertDialog(
            onDismissRequest = { debugImagePathToView = null },
            title = { Text("OMR Hata Ayıklama Görseli") },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "OMR Debug",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Hata ayıklama görüntüsü yüklenemedi.")
                }
            },
            confirmButton = {
                TextButton(onClick = { debugImagePathToView = null }) {
                    Text("Kapat")
                }
            }
        )
    }

    editingEntry?.let { (idx, report) ->
        val read = report.readResult ?: return@let
        var tc by remember(report) { mutableStateOf(read.tcKimlikNo.filter { it.isDigit() }) }
        var okulNo by remember(report) { mutableStateOf(read.okulNo.filter { it.isDigit() }) }
        val scoreStates = remember(report) {
            mutableStateListOf<String>().apply {
                (0 until 10).forEach { i -> add(read.scores.getOrElse(i) { 0 }.toString()) }
            }
        }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text("Kaydı Elle Düzelt") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "T.C. veya Okul No'yu düzelttiğinizde öğrenci rehberden otomatik eşleştirilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tc,
                            onValueChange = { if (it.length <= 11) tc = it.filter { c -> c.isDigit() } },
                            label = { Text("T.C. Kimlik No") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1.6f)
                        )
                        OutlinedTextField(
                            value = okulNo,
                            onValueChange = { if (it.length <= 5) okulNo = it.filter { c -> c.isDigit() } },
                            label = { Text("Okul No") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..4).forEach { q ->
                            OutlinedTextField(
                                value = scoreStates[q],
                                onValueChange = { scoreStates[q] = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("S${q + 1}") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (5..9).forEach { q ->
                            OutlinedTextField(
                                value = scoreStates[q],
                                onValueChange = { scoreStates[q] = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("S${q + 1}") },
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
                        viewModel.saveManualCorrection(
                            reportIndex = idx,
                            report = report,
                            newTc = tc,
                            newOkulNo = okulNo,
                            scores = scoreStates.map { it.toIntOrNull() ?: 0 }
                        )
                        editingEntry = null
                    }
                ) {
                    Text("Kaydet ve Hesapla")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) {
                    Text("İptal")
                }
            }
        )
    }
}
