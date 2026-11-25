package com.optiklik.app.domain.omr

import android.graphics.Bitmap
import com.optiklik.app.data.local.entity.ReviewReason

sealed class GuidanceState(val message: String, val isReady: Boolean = false) {
    data object Ready : GuidanceState("Optik form kilitlendi", isReady = true)
    data object FrameNotFound : GuidanceState(
        "4 köşe hedefini çerçeveye hizalayın"
    )
    data class MissingMarkers(val missingCorners: List<String>) : GuidanceState(
        "Eksik köşe işareti: ${missingCorners.joinToString(", ")}"
    )
    data object TooFar : GuidanceState("Kamerayı kağıda yaklaştırın.")
    data object TooClose : GuidanceState("Kamerayı kağıttan uzaklaştırın.")
    data object Blurry : GuidanceState("Görüntü bulanık, telefonu sabit tutun.")
    data object TooSkewed : GuidanceState("Telefonu kağıda paralel tutun.")
    data object BottomEdgeClipped : GuidanceState("Sayfanın alt kenarını kadraja alın")
    data object TopEdgeClipped : GuidanceState("Sayfanın üst kenarını kadraja alın")
    data object UnclearCorners : GuidanceState("Köşe net değil, kağıdı düz tutun ve ışığı artırın")
}

data class OmrReadResult(
    val tcKimlikNo: String,
    val okulNo: String = "",
    val isTcValid: Boolean,
    val scores: List<Int>, // 10 scores (WRITTEN) or 1..25 scores (TEST)
    val totalScore: Int,
    val codedTotalScore: Int? = null,
    val hasTotalMismatch: Boolean = false,
    val needsManualReview: Boolean = false,
    val multipleFormsDetected: Boolean = false,
    val reviewReasons: List<ReviewReason> = emptyList(),
    val warnings: List<String> = emptyList(),
    val debugBitmap: Bitmap? = null,
    val debugImagePath: String? = null,
    val studentAnswers: List<String> = emptyList(),
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val blankCount: Int = 0
)

data class OmrFrameAnalysis(
    val guidance: GuidanceState,
    val result: OmrReadResult? = null,
    val markerPointsNormalized: List<Pair<Float, Float>> = emptyList()
)
