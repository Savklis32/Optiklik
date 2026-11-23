package com.optiklik.app.ui.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.repository.MatchStatus
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.data.repository.ResolvedStudent
import com.optiklik.app.domain.omr.GuidanceState
import com.optiklik.app.domain.omr.OmrProcessor
import com.optiklik.app.domain.omr.OmrReadResult
import com.optiklik.app.domain.omr.TcKimlikValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveScannerUiState(
    val examTitle: String = "",
    val isAutoScanEnabled: Boolean = false,
    val frameAspectRatio: Float = 9f / 16f,
    val guidance: GuidanceState = GuidanceState.FrameNotFound,
    val markerPointsNormalized: List<Pair<Float, Float>> = emptyList(),
    val stableFrameCount: Int = 0,
    val lastSavedResult: OmrReadResult? = null,
    val lastSavedStudentName: String? = null,
    val lastSavedOkulNo: String? = null,
    val lastSavedTc: String? = null,
    val lastSavedWarnings: List<String> = emptyList(),
    val showSavedBanner: Boolean = false,
    val notInDirectoryWarning: String? = null,
    val duplicateCandidate: Pair<OmrReadResult, Long>? = null, // (newRead, existingRowId)
    val invalidTcCandidate: OmrReadResult? = null,
    val isPaused: Boolean = false,
    val feedbackTriggerId: Long = 0L
)

class LiveScannerViewModel(
    private val examSessionId: Long,
    private val repository: OptiklikRepository,
    private val omrProcessor: OmrProcessor = OmrProcessor()
) : ViewModel() {

    companion object {
        const val REQUIRED_STABLE_FRAMES = 3
        private const val AUTO_DUPLICATE_COOLDOWN_MS = 5000L
    }

    private val _uiState = MutableStateFlow(LiveScannerUiState())
    val uiState: StateFlow<LiveScannerUiState> = _uiState.asStateFlow()

    private var lastCaptureTimestamp = 0L
    private var consecutiveReadyFrames = 0
    private var lastConsensusSignature: String? = null
    private var lastSavedTc: String? = null
    private var lastSavedTime: Long = 0L
    private var activeOkulAdi: String = ""
    private var activeSoruSayisi: Int = 10
    private var activeMaxScores: List<Int> = List(10) { 10 }

    private val latestFrameLock = Any()
    private var latestFrameBitmap: Bitmap? = null
    private var latestFrameRotation: Int = 0
    @Volatile
    private var isSingleShotRunning: Boolean = false

    init {
        viewModelScope.launch {
            val session = repository.getSessionById(examSessionId)
            activeOkulAdi = session?.okulAdi.orEmpty()
            activeSoruSayisi = session?.soruSayisi ?: 10
            activeMaxScores = session?.parsedMaxScores() ?: List(10) { 10 }
            _uiState.update { it.copy(examTitle = session?.title ?: "Canlı Tarama") }
        }
    }

    fun toggleAutoScan(enabled: Boolean) {
        consecutiveReadyFrames = 0
        lastConsensusSignature = null
        _uiState.update {
            it.copy(
                isAutoScanEnabled = enabled,
                stableFrameCount = 0,
                notInDirectoryWarning = null
            )
        }
    }

    fun dismissNotInDirectoryWarning() {
        _uiState.update { it.copy(notInDirectoryWarning = null) }
    }

    fun processFrame(rawBitmap: Bitmap, rotationDegrees: Int) {
        updateLatestFrame(rawBitmap, rotationDegrees)

        val state = _uiState.value
        if (state.isPaused || state.duplicateCandidate != null || state.invalidTcCandidate != null) {
            return
        }

        val now = System.currentTimeMillis()
        if (state.isAutoScanEnabled && now - lastCaptureTimestamp < 1500L) {
            return
        }

        val rotated = rotateBitmap(rawBitmap, rotationDegrees)
        val frameAspect = rotated.width.toFloat() / rotated.height.coerceAtLeast(1).toFloat()
        val roi = cropCenterGuideRoi(rotated)
        if (rotated !== rawBitmap) rotated.recycle()

        // In default Manual mode, only detect 4 corners for live green crosshair feedback
        val analysis = try {
            omrProcessor.analyzeBitmap(
                roi,
                isLiveCamera = true,
                includeDebugBitmap = state.isAutoScanEnabled,
                detectCornersOnly = !state.isAutoScanEnabled,
                soruSayisi = activeSoruSayisi,
                maxScores = activeMaxScores
            )
        } finally {
            roi.recycle()
        }

        if (!state.isAutoScanEnabled) {
            _uiState.update {
                it.copy(
                    frameAspectRatio = frameAspect,
                    guidance = analysis.guidance,
                    markerPointsNormalized = analysis.markerPointsNormalized,
                    stableFrameCount = 0
                )
            }
            return
        }

        val currentRead = analysis.result
        if (analysis.guidance.isReady && currentRead != null) {
            val signature = "${currentRead.tcKimlikNo}|${currentRead.scores.joinToString(",")}"
            if (signature == lastConsensusSignature) {
                consecutiveReadyFrames++
            } else {
                lastConsensusSignature = signature
                consecutiveReadyFrames = 1
            }
        } else {
            lastConsensusSignature = null
            consecutiveReadyFrames = 0
        }

        _uiState.update {
            it.copy(
                frameAspectRatio = frameAspect,
                guidance = analysis.guidance,
                markerPointsNormalized = analysis.markerPointsNormalized,
                stableFrameCount = consecutiveReadyFrames
            )
        }

        if (consecutiveReadyFrames < REQUIRED_STABLE_FRAMES) {
            return
        }

        val read = currentRead ?: return
        // 5-second duplicate T.C. protection in auto-scan mode; resets immediately if a different T.C. appears
        if (read.tcKimlikNo == lastSavedTc && (now - lastSavedTime) < AUTO_DUPLICATE_COOLDOWN_MS) {
            consecutiveReadyFrames = 0
            lastConsensusSignature = null
            return
        }

        consecutiveReadyFrames = 0
        lastConsensusSignature = null
        lastCaptureTimestamp = now
        handleReadResult(read)
    }

    /**
     * Instant single-shot scan using the latest sharp frame from ImageAnalysis (0.1s latency,
     * bypassing ImageCapture hardware shutter delay).
     */
    fun captureLatestFrameAndScan() {
        val state = _uiState.value
        if (state.isPaused || state.duplicateCandidate != null || state.invalidTcCandidate != null) {
            return
        }
        if (isSingleShotRunning) return

        val snapshot: Pair<Bitmap, Int> = synchronized(latestFrameLock) {
            val bmp = latestFrameBitmap?.takeIf { !it.isRecycled } ?: return
            val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
            copy to latestFrameRotation
        }

        isSingleShotRunning = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    captureAndScanNow(snapshot.first, snapshot.second)
                }
            } finally {
                if (!snapshot.first.isRecycled) {
                    snapshot.first.recycle()
                }
                isSingleShotRunning = false
            }
        }
    }

    private fun updateLatestFrame(rawBitmap: Bitmap, rotationDegrees: Int) {
        if (rawBitmap.isRecycled) return
        val copy = rawBitmap.copy(rawBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        synchronized(latestFrameLock) {
            val old = latestFrameBitmap
            latestFrameBitmap = copy
            latestFrameRotation = rotationDegrees
            if (old != null && old !== copy && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(latestFrameLock) {
            latestFrameBitmap?.takeIf { !it.isRecycled }?.recycle()
            latestFrameBitmap = null
        }
    }

    /**
     * Immediate single-shot scan triggered by the Tap-to-Scan shutter button.
     */
    fun captureAndScanNow(rawBitmap: Bitmap, rotationDegrees: Int) {
        val state = _uiState.value
        if (state.isPaused || state.duplicateCandidate != null || state.invalidTcCandidate != null) {
            return
        }

        val rotated = rotateBitmap(rawBitmap, rotationDegrees)
        val roi = cropCenterGuideRoi(rotated)
        if (rotated !== rawBitmap) rotated.recycle()

        val analysis = try {
            omrProcessor.analyzeBitmap(
                roi,
                isLiveCamera = true,
                includeDebugBitmap = true,
                isSingleShotCapture = true,
                soruSayisi = activeSoruSayisi,
                maxScores = activeMaxScores
            )
        } finally {
            roi.recycle()
        }

        _uiState.update {
            it.copy(
                guidance = analysis.guidance,
                markerPointsNormalized = analysis.markerPointsNormalized
            )
        }

        val read = analysis.result ?: return
        consecutiveReadyFrames = 0
        lastConsensusSignature = null
        lastCaptureTimestamp = System.currentTimeMillis()
        handleReadResult(read)
    }

    private fun handleReadResult(read: OmrReadResult) {
        viewModelScope.launch {
            if (read.tcKimlikNo.isBlank() && read.okulNo.isBlank()) {
                _uiState.update {
                    it.copy(
                        notInDirectoryWarning = "T.C. Kimlik No veya Okul No kodlanmamış!",
                        showSavedBanner = false
                    )
                }
                return@launch
            }

            val hasDirectory = repository.hasDirectoryStudents()
            val resolved = repository.resolveStudent(examSessionId, read.tcKimlikNo, read.okulNo)

            if (hasDirectory) {
                if (!resolved.isMatchedInDirectory) {
                    val tcLabel = read.tcKimlikNo.ifBlank { "-" }
                    val okulLabel = read.okulNo.ifBlank { "-" }
                    val msg = if (resolved.matchStatus == MatchStatus.AMBIGUOUS) {
                        resolved.conflictWarning ?: "Belirsiz Öğrenci Eşleşmesi (TC: $tcLabel / Okul No: $okulLabel)"
                    } else {
                        "Öğrenci Listede Bulunamadı (TC: $tcLabel / Okul No: $okulLabel) - Kağıdı düz tutup tekrar deneyin"
                    }
                    _uiState.update {
                        it.copy(
                            notInDirectoryWarning = msg,
                            showSavedBanner = false
                        )
                    }
                    return@launch
                }
                val existing = repository.findExistingResult(
                    examSessionId,
                    resolved.tcKimlikNo,
                    resolved.okulNo
                )
                _uiState.update { it.copy(notInDirectoryWarning = null) }
                saveNewResult(read, resolved, existingId = existing?.id ?: 0L)
                return@launch
            }

            // Fallback when no student directory is loaded
            val hasMissingTcDigit = read.tcKimlikNo.isBlank() || read.tcKimlikNo.contains("?")
            if (hasMissingTcDigit && read.okulNo.isBlank()) {
                _uiState.update { it.copy(invalidTcCandidate = read, isPaused = true) }
                return@launch
            }

            val existing = repository.findExistingResult(
                examSessionId,
                resolved.tcKimlikNo,
                resolved.okulNo
            )

            if (existing != null) {
                _uiState.update {
                    it.copy(duplicateCandidate = read to existing.id, isPaused = true)
                }
            } else {
                saveNewResult(read, resolved, existingId = 0L)
            }
        }
    }

    fun confirmDuplicateUpdate() {
        val (read, existingId) = _uiState.value.duplicateCandidate ?: return
        viewModelScope.launch {
            val resolved = repository.resolveStudent(examSessionId, read.tcKimlikNo, read.okulNo)
            saveNewResult(read, resolved, existingId = existingId)
            _uiState.update { it.copy(duplicateCandidate = null, isPaused = false) }
        }
    }

    fun dismissDuplicate() {
        lastCaptureTimestamp = System.currentTimeMillis()
        _uiState.update { it.copy(duplicateCandidate = null, isPaused = false) }
    }

    fun saveCorrectedInvalidTc(tc: String, scores: List<Int>) {
        viewModelScope.launch {
            val cleanTc = tc.trim()
            val validTc = TcKimlikValidator.isValid(cleanTc)
            val clampedScores = (0 until 10).map { idx -> (scores.getOrNull(idx) ?: 0).coerceIn(0, 99) }
            val corrected = OmrReadResult(
                tcKimlikNo = cleanTc,
                okulNo = _uiState.value.invalidTcCandidate?.okulNo.orEmpty(),
                isTcValid = validTc,
                scores = clampedScores,
                totalScore = clampedScores.sum(),
                needsManualReview = false
            )
            val resolved = repository.resolveStudent(examSessionId, cleanTc, corrected.okulNo)
            val existing = if (resolved.tcKimlikNo.length == 11) {
                repository.findResultByTc(examSessionId, resolved.tcKimlikNo)
            } else null

            saveNewResult(corrected, resolved, existingId = existing?.id ?: 0L)
            _uiState.update { it.copy(invalidTcCandidate = null, isPaused = false) }
        }
    }

    fun dismissSavedBanner() {
        val oldBitmap = _uiState.value.lastSavedResult?.debugBitmap
        _uiState.update { it.copy(showSavedBanner = false, lastSavedResult = null) }
        oldBitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    fun dismissInvalidTc() {
        lastCaptureTimestamp = System.currentTimeMillis()
        _uiState.update { it.copy(invalidTcCandidate = null, isPaused = false) }
    }

    private suspend fun saveNewResult(
        read: OmrReadResult,
        resolved: ResolvedStudent,
        existingId: Long
    ) {
        val allWarnings = (
            read.warnings +
                listOfNotNull(resolved.conflictWarning, resolved.classMismatchWarning)
            ).distinct()
        val allReasons = (read.reviewReasons + resolved.reviewReasons).distinct()
        val needsReview = read.needsManualReview ||
            resolved.needsManualReview ||
            allReasons.isNotEmpty() ||
            allWarnings.isNotEmpty()
        val entity = ExamResultEntity(
            id = existingId,
            examSessionId = examSessionId,
            tcKimlikNo = resolved.tcKimlikNo,
            okulNo = resolved.okulNo,
            adSoyad = resolved.adSoyad,
            sinifSubesi = resolved.sinifSubesi,
            score1 = read.scores.getOrElse(0) { 0 },
            score2 = read.scores.getOrElse(1) { 0 },
            score3 = read.scores.getOrElse(2) { 0 },
            score4 = read.scores.getOrElse(3) { 0 },
            score5 = read.scores.getOrElse(4) { 0 },
            score6 = read.scores.getOrElse(5) { 0 },
            score7 = read.scores.getOrElse(6) { 0 },
            score8 = read.scores.getOrElse(7) { 0 },
            score9 = read.scores.getOrElse(8) { 0 },
            score10 = read.scores.getOrElse(9) { 0 },
            totalScore = read.totalScore,
            codedTotalScore = read.codedTotalScore,
            hasTotalMismatch = read.hasTotalMismatch,
            isTcValid = resolved.isTcValid,
            needsManualReview = needsReview,
            matchStatus = resolved.matchStatus.name,
            reviewReasons = ExamResultEntity.encodeReviewReasonEnums(allReasons)
        )
        repository.saveResult(entity)
        lastSavedTc = resolved.tcKimlikNo
        lastSavedTime = System.currentTimeMillis()
        val previousBitmap = _uiState.value.lastSavedResult?.debugBitmap
        _uiState.update {
            it.copy(
                lastSavedResult = read,
                lastSavedStudentName = resolved.adSoyad,
                lastSavedOkulNo = resolved.okulNo,
                lastSavedTc = resolved.tcKimlikNo,
                lastSavedWarnings = allWarnings,
                showSavedBanner = true,
                feedbackTriggerId = System.currentTimeMillis()
            )
        }
        if (previousBitmap != null && previousBitmap !== read.debugBitmap && !previousBitmap.isRecycled) {
            previousBitmap.recycle()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Crops the central horizontal guide band matching the on-screen OMR overlay frame.
     */
    /**
     * Crops the middle optical band (18%..82% of frame height) so slight vertical shifts
     * never clip the corner bullseye markers.
     */
    private fun cropCenterGuideRoi(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val roiH = (h * 0.64f).toInt().coerceAtLeast(10)
        val top = (h * 0.18f).toInt().coerceIn(0, (h - roiH).coerceAtLeast(0))
        return Bitmap.createBitmap(bitmap, 0, top, w, roiH)
    }

    class Factory(
        private val examSessionId: Long,
        private val repository: OptiklikRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveScannerViewModel(examSessionId, repository) as T
        }
    }
}
