package com.optiklik.app.ui.batch

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.service.BatchPageReport
import com.optiklik.app.service.BatchScanProgress
import com.optiklik.app.service.BatchScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class BatchScanUiState(
    val examTitle: String = "",
    val isScanning: Boolean = false,
    val progress: BatchScanProgress? = null
)

class BatchScanViewModel(
    private val examSessionId: Long,
    private val repository: OptiklikRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val scannerService = BatchScannerService(appContext, repository)

    private val _uiState = MutableStateFlow(BatchScanUiState())
    val uiState: StateFlow<BatchScanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = repository.getSessionById(examSessionId)
            _uiState.update { it.copy(examTitle = session?.title ?: "Toplu Tarama") }
        }
    }

    fun startBatchScan(uris: List<Uri>) {
        if (uris.isEmpty() || _uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, progress = null) }
            scannerService.scanUris(examSessionId, uris).collect { progress ->
                _uiState.update {
                    it.copy(
                        isScanning = !progress.isFinished,
                        progress = progress
                    )
                }
            }
        }
    }

    fun saveManualCorrection(
        reportIndex: Int,
        report: BatchPageReport,
        newTc: String,
        newOkulNo: String,
        scores: List<Int>
    ) {
        viewModelScope.launch {
            val cleanTc = newTc.trim()
            val cleanNo = newOkulNo.trim()
            val c = (0 until 10).map { idx -> (scores.getOrNull(idx) ?: 0).coerceIn(0, 99) }
            val resolved = repository.resolveStudent(examSessionId, cleanTc, cleanNo)

            val existing = report.savedResultId?.let { repository.getResultById(it) }
                ?: repository.findExistingResult(examSessionId, resolved.tcKimlikNo, resolved.okulNo)

            val entity = ExamResultEntity(
                id = existing?.id ?: 0L,
                examSessionId = examSessionId,
                tcKimlikNo = resolved.tcKimlikNo,
                okulNo = resolved.okulNo,
                adSoyad = resolved.adSoyad,
                sinifSubesi = resolved.sinifSubesi,
                score1 = c[0],
                score2 = c[1],
                score3 = c[2],
                score4 = c[3],
                score5 = c[4],
                score6 = c[5],
                score7 = c[6],
                score8 = c[7],
                score9 = c[8],
                score10 = c[9],
                totalScore = c.sum(),
                codedTotalScore = report.readResult?.codedTotalScore,
                hasTotalMismatch = false,
                isTcValid = resolved.isTcValid,
                needsManualReview = false,
                matchStatus = resolved.matchStatus.name,
                reviewReasons = ExamResultEntity.encodeReviewReasonEnums(resolved.reviewReasons)
            )
            val savedId = repository.saveResult(entity)

            _uiState.update { state ->
                val currentProgress = state.progress ?: return@update state
                val updatedReports = currentProgress.reports.toMutableList()
                if (reportIndex in updatedReports.indices) {
                    val updatedRead = report.readResult?.copy(
                        tcKimlikNo = resolved.tcKimlikNo,
                        okulNo = resolved.okulNo,
                        isTcValid = resolved.isTcValid,
                        scores = c,
                        totalScore = c.sum(),
                        hasTotalMismatch = false,
                        needsManualReview = false
                    )
                    updatedReports[reportIndex] = report.copy(
                        success = true,
                        message = "Düzeltildi • ${resolved.adSoyad} (${resolved.okulNo}) • Toplam: ${c.sum()}",
                        readResult = updatedRead,
                        savedResultId = if (existing != null && existing.id > 0L) existing.id else savedId,
                        needsManualReview = false
                    )
                }
                val successCount = updatedReports.count { it.success }
                val failureCount = updatedReports.count { !it.success }
                state.copy(
                    progress = currentProgress.copy(
                        successCount = successCount,
                        failureCount = failureCount,
                        reports = updatedReports
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupOldDebugImages()
    }

    private fun cleanupOldDebugImages() {
        try {
            val debugDir = File(appContext.cacheDir, "omr_debug")
            if (!debugDir.exists()) return
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            debugDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    class Factory(
        private val examSessionId: Long,
        private val repository: OptiklikRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BatchScanViewModel(examSessionId, repository, context) as T
        }
    }
}
