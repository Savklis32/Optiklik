package com.optiklik.app.ui.results

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.optiklik.app.data.local.dao.ExamResultWithStudent
import com.optiklik.app.data.local.entity.ReviewReason
import com.optiklik.app.ui.components.QuestionScoresGrid
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamResultsScreen(
    viewModel: ExamResultsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsState()
    val rosterCount by viewModel.rosterCount.collectAsState()
    val unscannedStudents by viewModel.unscannedStudents.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingItem by remember { mutableStateOf<ExamResultWithStudent?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showUnscannedDialog by remember { mutableStateOf(false) }
    var showQuestionAnalysis by remember { mutableStateOf(true) }
    var maskTcOnExport by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var onlyIssuesFilter by remember { mutableStateOf(false) }
    var selectedReasonFilter by remember { mutableStateOf<ReviewReason?>(null) }

    val filteredResults = remember(results, searchQuery, onlyIssuesFilter, selectedReasonFilter) {
        val q = searchQuery.trim().lowercase(Locale("tr", "TR"))
        results.filter { item ->
            val r = item.result
            val adSoyad = (item.fullName ?: r.adSoyad).lowercase(Locale("tr", "TR"))
            val okulNo = (item.schoolNo ?: r.okulNo).lowercase(Locale("tr", "TR"))
            val tc = r.tcKimlikNo.lowercase(Locale("tr", "TR"))
            val matchesQuery = q.isEmpty() || adSoyad.contains(q) || okulNo.contains(q) || tc.contains(q)
            val isUnmatched = (item.fullName ?: r.adSoyad) == "Listede Yok"
            val reasonEnums = r.parsedReviewReasonEnums()
            val hasIssue = r.needsManualReview ||
                r.hasTotalMismatch ||
                !r.isTcValid ||
                isUnmatched ||
                reasonEnums.isNotEmpty()
            val matchesIssue = !onlyIssuesFilter || hasIssue
            val matchesReason = selectedReasonFilter == null ||
                selectedReasonFilter in reasonEnums ||
                (selectedReasonFilter == ReviewReason.TOTAL_MISMATCH && r.hasTotalMismatch) ||
                (selectedReasonFilter == ReviewReason.UNMATCHED_STUDENT && isUnmatched)
            matchesQuery && matchesIssue && matchesReason
        }
    }

    val rosterPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importRoster(uri)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.examTitle, maxLines = 1, fontWeight = FontWeight.Bold)
                        val sinifLabel = uiState.sinifSubesi.ifBlank { "-" }
                        Text(
                            "$sinifLabel • ${results.size} kağıt • Rehber: $rosterCount öğrenci",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.rematchWithDirectory() },
                        enabled = results.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Listeyle Yeniden Eşleştir")
                    }
                    IconButton(
                        onClick = {
                            rosterPicker.launch(
                                arrayOf(
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/pdf",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "*/*"
                                )
                            )
                        }
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "Öğrenci Listesi Yükle")
                    }
                    IconButton(
                        onClick = { viewModel.exportToExcel(maskTc = maskTcOnExport) },
                        enabled = results.isNotEmpty() && !uiState.isExporting
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Excel Dışa Aktar")
                    }
                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        enabled = results.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Tüm Sonuçları Temizle",
                            tint = if (results.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Bu sınav için henüz okunan kağıt yok.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val avg = results.map { it.result.totalScore }.average()
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Sınıf Ortalaması: ${String.format(Locale("tr", "TR"), "%.2f", avg)}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        FilledTonalButton(
                            onClick = { viewModel.exportToExcel(maskTc = maskTcOnExport) },
                            enabled = !uiState.isExporting
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (uiState.isExporting) "Hazırlanıyor..." else "Excel (.xlsx)")
                        }
                    }
                }

                val activeQuestionCount = uiState.soruSayisi.coerceIn(1, 10)
                val questionAverages = remember(results, activeQuestionCount) {
                    (0 until activeQuestionCount).map { q ->
                        results.map { item ->
                            val r = item.result
                            when (q) {
                                0 -> r.score1
                                1 -> r.score2
                                2 -> r.score3
                                3 -> r.score4
                                4 -> r.score5
                                5 -> r.score6
                                6 -> r.score7
                                7 -> r.score8
                                8 -> r.score9
                                else -> r.score10
                            }
                        }.average()
                    }
                }

                if (showQuestionAnalysis) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Soru Analizi (S1 – S$activeQuestionCount Ortalamaları)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(questionAverages) { idx, qAvg ->
                                    val maxScore = uiState.maxScores.getOrElse(idx) { 10 }
                                    val pct = if (maxScore > 0) (qAvg / maxScore) * 100.0 else 0.0
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 1.dp
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "S${idx + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                String.format(Locale("tr", "TR"), "%.1f", qAvg),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "%${String.format(Locale("tr", "TR"), "%.0f", pct)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Ad Soyad, Okul No veya T.C. ara...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Temizle")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = !onlyIssuesFilter && selectedReasonFilter == null,
                                onClick = {
                                    onlyIssuesFilter = false
                                    selectedReasonFilter = null
                                },
                                label = { Text("Tümü", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = onlyIssuesFilter,
                                onClick = { onlyIssuesFilter = !onlyIssuesFilter },
                                label = { Text("Kontrol Gerekli", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReasonFilter == ReviewReason.TOTAL_MISMATCH,
                                onClick = {
                                    selectedReasonFilter = if (selectedReasonFilter == ReviewReason.TOTAL_MISMATCH) null else ReviewReason.TOTAL_MISMATCH
                                },
                                label = { Text("Toplam Uyuşmazlığı", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReasonFilter == ReviewReason.TC_OKUL_NO_CONFLICT,
                                onClick = {
                                    selectedReasonFilter = if (selectedReasonFilter == ReviewReason.TC_OKUL_NO_CONFLICT) null else ReviewReason.TC_OKUL_NO_CONFLICT
                                },
                                label = { Text("T.C./Okul No Çelişkisi", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReasonFilter == ReviewReason.CLASS_MISMATCH,
                                onClick = {
                                    selectedReasonFilter = if (selectedReasonFilter == ReviewReason.CLASS_MISMATCH) null else ReviewReason.CLASS_MISMATCH
                                },
                                label = { Text("Sınıf Uyuşmazlığı", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReasonFilter == ReviewReason.UNMATCHED_STUDENT,
                                onClick = {
                                    selectedReasonFilter = if (selectedReasonFilter == ReviewReason.UNMATCHED_STUDENT) null else ReviewReason.UNMATCHED_STUDENT
                                },
                                label = { Text("Listede Yok", maxLines = 1) }
                            )
                        }
                        if (unscannedStudents.isNotEmpty()) {
                            item {
                                AssistChip(
                                    onClick = { showUnscannedDialog = true },
                                    label = {
                                        Text(
                                            "Okunmayan: ${unscannedStudents.size}",
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                        item {
                            FilterChip(
                                selected = showQuestionAnalysis,
                                onClick = { showQuestionAnalysis = !showQuestionAnalysis },
                                label = { Text("Soru Ort.", maxLines = 1) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = maskTcOnExport,
                                onClick = { maskTcOnExport = !maskTcOnExport },
                                label = { Text("Excel T.C. Maskele", maxLines = 1) }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filteredResults, key = { _, item -> item.result.id }) { idx, item ->
                        ResultRowCard(
                            index = idx + 1,
                            item = item,
                            onClick = { editingItem = item }
                        )
                    }
                }
            }
        }
    }

    // ShareSheet dialog after Excel export
    if (showUnscannedDialog) {
        AlertDialog(
            onDismissRequest = { showUnscannedDialog = false },
            title = { Text("Okunmayan Öğrenciler (${unscannedStudents.size})") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(unscannedStudents, key = { "${it.tcKimlikNo}_${it.okulAdi}" }) { st ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        st.adSoyad,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "No: ${st.okulNo} • T.C: ${st.tcKimlikNo}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (st.sinifSubesi.isNotBlank()) {
                                    Text(
                                        st.sinifSubesi,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUnscannedDialog = false }) {
                    Text("Kapat")
                }
            }
        )
    }

    uiState.exportedFile?.let { exported ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportedFile() },
            title = { Text("Excel Hazır") },
            text = {
                Text("${exported.fileName}\n\nKaydedilen konum: ${exported.savedLocationLabel}\n\nDosyayı şimdi paylaşmak ister misiniz?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            putExtra(Intent.EXTRA_STREAM, exported.shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Excel Sonuçlarını Paylaş"))
                        viewModel.clearExportedFile()
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paylaş")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearExportedFile() }) {
                    Text("Kapat")
                }
            }
        )
    }

    // Clear all results confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Tüm Sonuçları Temizle") },
            text = { Text("Bu sınavdaki ${results.size} okunan sonuç kalıcı olarak silinecek. Emin misiniz?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllResults()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Temizle")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }

    // Manual edit dialog
    editingItem?.let { item ->
        EditResultDialog(
            item = item,
            onSave = { tc, okulNo, scores ->
                viewModel.updateResult(item.result, tc, okulNo, scores)
                editingItem = null
            },
            onDelete = {
                viewModel.deleteResult(item.result.id)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }
}

@Composable
private fun ResultRowCard(
    index: Int,
    item: ExamResultWithStudent,
    onClick: () -> Unit
) {
    val r = item.result
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        "#$index",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        val adSoyadText = item.fullName?.takeIf { it.isNotBlank() } ?: r.adSoyad.ifBlank { "Listede Yok" }
                        val okulNoText = item.schoolNo?.takeIf { it.isNotBlank() } ?: r.okulNo.ifBlank { "-" }
                        val sinifText = r.sinifSubesi.ifBlank { "-" }
                        val isMissing = adSoyadText == "Listede Yok"

                        Text(
                            text = adSoyadText,
                            fontWeight = FontWeight.Bold,
                            color = if (isMissing) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Sınıf: $sinifText | Okul No: $okulNoText | T.C.: ${r.tcKimlikNo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!r.isTcValid || r.needsManualReview) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Kontrol Gerekli",
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "${r.totalScore} Puan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            QuestionScoresGrid(scores = r.scoresList())
            val reasons = r.formattedReviewLabels()
            if (r.hasTotalMismatch || reasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    val msg = if (reasons.isNotEmpty()) {
                        reasons.joinToString(" • ")
                    } else {
                        "TOPLAM uyuşmazlığı (Kodlanan: ${r.codedTotalScore ?: "-"})"
                    }
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB45309),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditResultDialog(
    item: ExamResultWithStudent,
    onSave: (String, String, List<Int>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val r = item.result
    var tc by remember { mutableStateOf(r.tcKimlikNo) }
    var okulNo by remember {
        mutableStateOf(
            item.schoolNo?.takeIf { it.isNotBlank() && it != "-" }
                ?: r.okulNo.takeIf { it.isNotBlank() && it != "-" }.orEmpty()
        )
    }
    val scoreStates = remember {
        mutableStateListOf<String>().apply {
            r.scoresList().forEach { add(it.toString()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sonucu Düzenle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.fullName.isNullOrBlank()) {
                    Text(
                        "${item.schoolNo ?: ""} - ${item.fullName}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
                    onSave(tc, okulNo, scoreStates.map { it.toIntOrNull() ?: 0 })
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("İptal")
                }
            }
        }
    )
}
