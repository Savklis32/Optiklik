package com.optiklik.app.domain.omr

import android.graphics.Bitmap
import android.util.Log
import com.optiklik.app.data.local.entity.ReviewReason
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Vertical OMR processor for 25-question TEST forms (Optik Form 3 / 4)
 * using solid black square corner markers (ZipGrade / ÖSYM style).
 *
 * Canonical warped dimensions: 1000 x 2000 (square-center to square-center).
 */
class TestOmrProcessor {

    internal data object OpticalFormLayout {
        // Block 1: T.C KİMLİK NO (11 cols x 10 rows, digits 0..9)
        const val TC_LEFT = 99.0
        const val TC_RIGHT = 779.0
        const val TC_TOP = 334.0
        const val TC_BOTTOM = 770.0
        const val TC_COLS = 11
        const val TC_ROWS = 10

        // Block 2: OKUL NO (5 cols x 10 rows, digits 0..9)
        const val OKUL_LEFT = 99.0
        const val OKUL_RIGHT = 408.0
        const val OKUL_TOP = 905.0
        const val OKUL_BOTTOM = 1349.0
        const val OKUL_COLS = 5
        const val OKUL_ROWS = 10

        // Block 3: CEVAPLAR (25 rows x 5 cols: A, B, C, D, E)
        const val Q_OPT_LEFT = 523.0
        const val Q_OPT_RIGHT = 838.0
        const val Q_BAND_TOP = 859.0
        const val Q_BAND_BOTTOM = 1971.0
        const val Q_MAX_ROWS = 25
        const val Q_OPTIONS_COUNT = 5
    }

    companion object {
        const val WARP_WIDTH = 1000.0
        const val WARP_HEIGHT = 2000.0
        private const val TARGET_WORK_WIDTH = 1600.0

        private const val INNER_CORE_W_RATIO = 0.50
        private const val INNER_CORE_H_RATIO = 0.50

        // First-Large-Gap dynamic thresholding (OMRChecker core.py)
        private const val GAP_MIN_JUMP = 0.07
        private const val GAP_MIN_ABOVE_BASELINE = 0.09
        private const val MULTI_ABOVE_BASELINE = 0.11
        private const val MULTI_MAX_DIFF = 0.05

        // Black square corner marker parameters
        private const val MIN_SQUARE_ASPECT = 0.60
        private const val MAX_SQUARE_ASPECT = 1.65
        private const val MIN_SQUARE_EXTENT = 0.65
        private const val MIN_SQUARE_SIDE_RATIO = 0.018
        private const val MAX_SQUARE_SIDE_RATIO = 0.16
        private const val MAX_SQUARE_INNER_MEAN = 145.0
        private const val MIN_SQUARE_QUIET_ZONE_CONTRAST = 30.0

        // Vertical test form quad geometry (9:16 live camera & PDF/gallery)
        private const val MIN_QUAD_ASPECT = 0.26
        private const val MAX_QUAD_ASPECT = 0.75
        private const val MAX_PARALLEL_SIDE_DIFF = 0.22
        private const val LIVE_MIN_QUAD_AREA_RATIO = 0.05
        private const val STATIC_MIN_QUAD_AREA_RATIO = 0.05
        private const val MIN_QUAD_HEIGHT_RATIO = 0.25
        private const val MIN_QUAD_WIDTH_RATIO = 0.14

        private const val MIN_LAPLACIAN_VARIANCE = 18.0

        private val OPTION_LABELS = listOf("A", "B", "C", "D", "E")
    }

    private data class SquareCandidate(
        val center: Point,
        val area: Double,
        val contrast: Double
    )

    private data class CornerSelection(
        val quad: Array<Point>?,
        val foundPoints: List<Point>,
        val missingCorners: List<String>,
        val method: String
    )

    @Synchronized
    fun analyzeBitmap(
        bitmap: Bitmap,
        isLiveCamera: Boolean,
        includeDebugBitmap: Boolean = true,
        isSingleShotCapture: Boolean = false,
        detectCornersOnly: Boolean = false,
        soruSayisi: Int = 25,
        answerKey: List<String> = emptyList(),
        maxScores: List<Int> = List(25) { 4 }
    ): OmrFrameAnalysis {
        val startNs = System.nanoTime()
        val rgba = Mat()
        val rawGray = Mat()
        val gray = Mat()
        var warpedCanonical: Mat? = null

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rawGray, Imgproc.COLOR_RGBA2GRAY)

            val workScale = if (rawGray.cols() > 0 && rawGray.cols() != TARGET_WORK_WIDTH.toInt()) {
                TARGET_WORK_WIDTH / rawGray.cols().toDouble()
            } else {
                1.0
            }
            if (workScale != 1.0) {
                val targetH = (rawGray.rows() * workScale).toInt().coerceAtLeast(1)
                Imgproc.resize(rawGray, gray, Size(TARGET_WORK_WIDTH, targetH.toDouble()))
            } else {
                rawGray.copyTo(gray)
            }

            if (isLiveCamera && !isSingleShotCapture && computeLaplacianVariance(gray) < MIN_LAPLACIAN_VARIANCE) {
                return OmrFrameAnalysis(GuidanceState.Blurry)
            }

            val imgW = gray.cols().toDouble()
            val imgH = gray.rows().toDouble()

            val selection = detectSquareCornerMarkers(gray, isLiveCamera && !isSingleShotCapture)

            runCatching {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val quadStr = selection.quad?.joinToString(",") { "(${it.x.toInt()},${it.y.toInt()})" } ?: "null"
                Log.d(
                    "TestOmrProcessor",
                    "method=${selection.method}, quad=$quadStr, missing=${selection.missingCorners}, elapsedMs=$elapsedMs"
                )
            }

            val normalizedMarkers = selection.foundPoints.map { pt ->
                (pt.x / imgW).toFloat().coerceIn(0f, 1f) to (pt.y / imgH).toFloat().coerceIn(0f, 1f)
            }

            val quad = selection.quad
            if (quad == null) {
                val missingList = selection.missingCorners.ifEmpty {
                    listOf("Sol Üst", "Sağ Üst", "Sağ Alt", "Sol Alt")
                }
                return OmrFrameAnalysis(
                    GuidanceState.MissingMarkers(missingList),
                    markerPointsNormalized = normalizedMarkers
                )
            }

            if (detectCornersOnly) {
                return OmrFrameAnalysis(
                    guidance = GuidanceState.Ready,
                    result = null,
                    markerPointsNormalized = normalizedMarkers
                )
            }

            warpedCanonical = warpToCanonical(gray, quad)
            val result = readTestGrid(
                warpedGray = warpedCanonical,
                includeDebugBitmap = includeDebugBitmap,
                soruSayisi = soruSayisi,
                answerKey = answerKey,
                maxScores = maxScores
            )
            return OmrFrameAnalysis(
                guidance = GuidanceState.Ready,
                result = result,
                markerPointsNormalized = normalizedMarkers
            )
        } finally {
            warpedCanonical?.release()
            rgba.release()
            rawGray.release()
            gray.release()
        }
    }

    private fun detectSquareCornerMarkers(gray: Mat, isLivePreview: Boolean): CornerSelection {
        val imgW = gray.cols().toDouble()
        val imgH = gray.rows().toDouble()
        val normGray = Mat()
        val blurred = Mat()
        val binAdaptive = Mat()
        val binOtsu = Mat()

        try {
            Core.normalize(gray, normGray, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.GaussianBlur(normGray, blurred, Size(5.0, 5.0), 0.0)

            // Primary: adaptive threshold with large blockSize (81) so 60-100 px solid squares stay filled
            Imgproc.adaptiveThreshold(
                blurred,
                binAdaptive,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                81,
                10.0
            )
            val adaptiveCands = extractSquareCandidates(binAdaptive, normGray, imgW)
            val adaptiveSel = selectFourSquareCorners(adaptiveCands, imgW, imgH, isLivePreview, "adaptive")
            if (adaptiveSel.quad != null) return adaptiveSel

            // Fallback: Otsu binary threshold
            Imgproc.threshold(blurred, binOtsu, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            val otsuCands = extractSquareCandidates(binOtsu, normGray, imgW)
            val otsuSel = selectFourSquareCorners(otsuCands, imgW, imgH, isLivePreview, "otsu")
            if (otsuSel.quad != null) return otsuSel

            return if (otsuSel.foundPoints.size > adaptiveSel.foundPoints.size) otsuSel else adaptiveSel
        } finally {
            normGray.release()
            blurred.release()
            binAdaptive.release()
            binOtsu.release()
        }
    }

    private fun extractSquareCandidates(bin: Mat, gray: Mat, imgW: Double): List<SquareCandidate> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val minSide = imgW * MIN_SQUARE_SIDE_RATIO
        val maxSide = imgW * MAX_SQUARE_SIDE_RATIO
        val raw = mutableListOf<SquareCandidate>()

        try {
            Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (cnt in contours) {
                val rect = Imgproc.boundingRect(cnt)
                val side = max(rect.width, rect.height).toDouble()
                if (side !in minSide..maxSide) continue

                val aspect = rect.width.toDouble() / rect.height.coerceAtLeast(1).toDouble()
                if (aspect !in MIN_SQUARE_ASPECT..MAX_SQUARE_ASPECT) continue

                val area = Imgproc.contourArea(cnt)
                val rectArea = rect.width.toDouble() * rect.height.toDouble()
                if (rectArea <= 0.0 || area / rectArea < MIN_SQUARE_EXTENT) continue

                val cnt2f = MatOfPoint2f(*cnt.toArray())
                val approx = MatOfPoint2f()
                val isConvex4Gon = try {
                    val peri = Imgproc.arcLength(cnt2f, true)
                    Imgproc.approxPolyDP(cnt2f, approx, 0.05 * peri, true)
                    val pts = approx.toArray()
                    if (pts.size in 4..6) {
                        val poly = MatOfPoint(*pts)
                        try {
                            Imgproc.isContourConvex(poly)
                        } finally {
                            poly.release()
                        }
                    } else {
                        false
                    }
                } finally {
                    cnt2f.release()
                    approx.release()
                }
                if (!isConvex4Gon) continue

                val contrast = measureSquareQuietZoneContrast(gray, rect) ?: continue
                val cx = rect.x + rect.width / 2.0
                val cy = rect.y + rect.height / 2.0
                raw.add(SquareCandidate(Point(cx, cy), area, contrast))
            }

            val sorted = raw.sortedByDescending { it.contrast * 100.0 + it.area }
            val unique = mutableListOf<SquareCandidate>()
            for (cand in sorted) {
                if (unique.none { distance(it.center, cand.center) < minSide * 0.8 }) {
                    unique.add(cand)
                }
            }
            return unique
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun measureSquareQuietZoneContrast(gray: Mat, rect: Rect): Double? {
        val w = gray.cols()
        val h = gray.rows()

        // Inner 60% of the square must be dark
        val inW = (rect.width * 0.60).toInt().coerceAtLeast(2)
        val inH = (rect.height * 0.60).toInt().coerceAtLeast(2)
        val inX0 = (rect.x + (rect.width - inW) / 2).coerceIn(0, w - 1)
        val inY0 = (rect.y + (rect.height - inH) / 2).coerceIn(0, h - 1)
        val inX1 = (inX0 + inW).coerceAtMost(w)
        val inY1 = (inY0 + inH).coerceAtMost(h)
        if (inX1 <= inX0 || inY1 <= inY0) return null

        val innerRoi = gray.submat(inY0, inY1, inX0, inX1)
        val innerMean = try {
            Core.mean(innerRoi).`val`[0]
        } finally {
            innerRoi.release()
        }
        if (innerMean >= MAX_SQUARE_INNER_MEAN) return null

        // Outer quiet-zone band: 1.3x..1.75x around the square center
        val cx = rect.x + rect.width / 2.0
        val cy = rect.y + rect.height / 2.0
        val innerHalfW = rect.width * 0.65
        val innerHalfH = rect.height * 0.65
        val outerHalfW = rect.width * 0.875
        val outerHalfH = rect.height * 0.875

        val ox0 = (cx - outerHalfW).toInt().coerceIn(0, w - 1)
        val oy0 = (cy - outerHalfH).toInt().coerceIn(0, h - 1)
        val ox1 = (cx + outerHalfW).toInt().coerceIn(ox0 + 1, w)
        val oy1 = (cy + outerHalfH).toInt().coerceIn(oy0 + 1, h)
        val pw = ox1 - ox0
        val ph = oy1 - oy0
        if (pw <= 0 || ph <= 0) return null

        val outerPatch = gray.submat(oy0, oy1, ox0, ox1)
        val buf = ByteArray(pw * ph)
        try {
            outerPatch.get(0, 0, buf)
        } finally {
            outerPatch.release()
        }

        var topSum = 0.0; var topCount = 0
        var botSum = 0.0; var botCount = 0
        var leftSum = 0.0; var leftCount = 0
        var rightSum = 0.0; var rightCount = 0
        for (py in oy0 until oy1) {
            val rowOff = (py - oy0) * pw
            val dy = py - cy
            for (px in ox0 until ox1) {
                val dx = px - cx
                val v = (buf[rowOff + (px - ox0)].toInt() and 0xFF).toDouble()
                when {
                    dy <= -innerHalfH && abs(dx) <= outerHalfW -> { topSum += v; topCount++ }
                    dy >= innerHalfH && abs(dx) <= outerHalfW -> { botSum += v; botCount++ }
                    dx <= -innerHalfW && abs(dy) <= outerHalfH -> { leftSum += v; leftCount++ }
                    dx >= innerHalfW && abs(dy) <= outerHalfH -> { rightSum += v; rightCount++ }
                }
            }
        }
        val stripMeans = listOfNotNull(
            if (topCount > 0) topSum / topCount else null,
            if (botCount > 0) botSum / botCount else null,
            if (leftCount > 0) leftSum / leftCount else null,
            if (rightCount > 0) rightSum / rightCount else null
        ).sortedDescending()
        if (stripMeans.size < 2) return null
        val brightTwoSidesMean = stripMeans.take(2).average()
        val contrast = brightTwoSidesMean - innerMean
        return if (contrast >= MIN_SQUARE_QUIET_ZONE_CONTRAST) contrast else null
    }

    private fun selectFourSquareCorners(
        candidates: List<SquareCandidate>,
        imgW: Double,
        imgH: Double,
        isLivePreview: Boolean,
        methodTag: String
    ): CornerSelection {
        val tlList = candidates
            .filter { it.center.x < imgW * 0.45 && it.center.y < imgH * 0.45 }
            .sortedBy { distance(it.center, Point(0.0, 0.0)) }
        val trList = candidates
            .filter { it.center.x > imgW * 0.55 && it.center.y < imgH * 0.45 }
            .sortedBy { distance(it.center, Point(imgW, 0.0)) }
        val brList = candidates
            .filter { it.center.x > imgW * 0.55 && it.center.y > imgH * 0.55 }
            .sortedBy { distance(it.center, Point(imgW, imgH)) }
        val blList = candidates
            .filter { it.center.x < imgW * 0.45 && it.center.y > imgH * 0.55 }
            .sortedBy { distance(it.center, Point(0.0, imgH)) }

        val missing = mutableListOf<String>()
        if (tlList.isEmpty()) missing.add("Sol Üst")
        if (trList.isEmpty()) missing.add("Sağ Üst")
        if (brList.isEmpty()) missing.add("Sağ Alt")
        if (blList.isEmpty()) missing.add("Sol Alt")

        val found = listOfNotNull(
            tlList.firstOrNull()?.center,
            trList.firstOrNull()?.center,
            brList.firstOrNull()?.center,
            blList.firstOrNull()?.center
        )
        if (missing.isNotEmpty()) {
            return CornerSelection(null, found, missing, "${methodTag}_missing")
        }

        // Try quadrant candidates first (outermost first)
        val limit = 4
        for (tl in tlList.take(limit)) {
            for (tr in trList.take(limit)) {
                for (br in brList.take(limit)) {
                    for (bl in blList.take(limit)) {
                        val quad = arrayOf(tl.center, tr.center, br.center, bl.center)
                        if (isValidVerticalQuad(quad, imgW, imgH, isLivePreview)) {
                            return CornerSelection(quad, quad.toList(), emptyList(), "${methodTag}_squares")
                        }
                    }
                }
            }
        }

        // Fallback: pickExtremeCorners if >4 candidates exist
        if (candidates.size > 4) {
            val extreme = pickExtremeCorners(candidates.map { it.center })
            if (extreme != null && isValidVerticalQuad(extreme, imgW, imgH, isLivePreview)) {
                return CornerSelection(extreme, extreme.toList(), emptyList(), "${methodTag}_extreme")
            }
        }

        return CornerSelection(
            null,
            found,
            listOf("Sol Üst", "Sağ Üst", "Sağ Alt", "Sol Alt"),
            "${methodTag}_invalid_quad"
        )
    }

    private fun isValidVerticalQuad(
        quad: Array<Point>,
        imgW: Double,
        imgH: Double,
        isLivePreview: Boolean
    ): Boolean {
        val quadArea = polygonArea(quad)
        val minAreaRatio = if (isLivePreview) LIVE_MIN_QUAD_AREA_RATIO else STATIC_MIN_QUAD_AREA_RATIO
        if (quadArea / (imgW * imgH) < minAreaRatio) return false

        val topW = distance(quad[0], quad[1])
        val bottomW = distance(quad[3], quad[2])
        val leftH = distance(quad[0], quad[3])
        val rightH = distance(quad[1], quad[2])
        val avgW = (topW + bottomW) / 2.0
        val avgH = (leftH + rightH) / 2.0
        if (avgW <= 0.0 || avgH <= 0.0) return false

        if (avgH < imgH * MIN_QUAD_HEIGHT_RATIO || avgW < imgW * MIN_QUAD_WIDTH_RATIO) return false

        val aspect = avgW / avgH
        if (aspect !in MIN_QUAD_ASPECT..MAX_QUAD_ASPECT) return false

        val wDiff = abs(topW - bottomW) / max(topW, bottomW)
        val hDiff = abs(leftH - rightH) / max(leftH, rightH)
        return wDiff <= MAX_PARALLEL_SIDE_DIFF && hDiff <= MAX_PARALLEL_SIDE_DIFF
    }

    private fun warpToCanonical(gray: Mat, quad: Array<Point>): Mat {
        val src = MatOfPoint2f(*quad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(WARP_WIDTH - 1, 0.0),
            Point(WARP_WIDTH - 1, WARP_HEIGHT - 1),
            Point(0.0, WARP_HEIGHT - 1)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(gray, warped, m, Size(WARP_WIDTH, WARP_HEIGHT))
            return warped
        } finally {
            src.release()
            dst.release()
            m.release()
        }
    }

    private fun readTestGrid(
        warpedGray: Mat,
        includeDebugBitmap: Boolean,
        soruSayisi: Int,
        answerKey: List<String>,
        maxScores: List<Int>
    ): OmrReadResult {
        val claheGray = Mat()
        val blurredGray = Mat()
        val normGray = Mat()
        val invGray = Mat()
        val cleanInv = Mat()
        var debugColor: Mat? = null

        try {
            val clahe = Imgproc.createCLAHE(5.0, Size(8.0, 8.0))
            clahe.apply(warpedGray, claheGray)
            Imgproc.GaussianBlur(claheGray, blurredGray, Size(3.0, 3.0), 0.0)
            Core.normalize(blurredGray, normGray, 0.0, 255.0, Core.NORM_MINMAX)

            Core.bitwise_not(normGray, invGray)
            val erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.erode(invGray, cleanInv, erodeKernel)
            erodeKernel.release()

            if (includeDebugBitmap) {
                debugColor = Mat()
                Imgproc.cvtColor(warpedGray, debugColor, Imgproc.COLOR_GRAY2RGBA)
                val blue = Scalar(59.0, 130.0, 246.0, 255.0)
                Imgproc.rectangle(
                    debugColor,
                    Point(OpticalFormLayout.TC_LEFT, OpticalFormLayout.TC_TOP),
                    Point(OpticalFormLayout.TC_RIGHT, OpticalFormLayout.TC_BOTTOM),
                    blue,
                    2
                )
                Imgproc.rectangle(
                    debugColor,
                    Point(OpticalFormLayout.OKUL_LEFT, OpticalFormLayout.OKUL_TOP),
                    Point(OpticalFormLayout.OKUL_RIGHT, OpticalFormLayout.OKUL_BOTTOM),
                    blue,
                    2
                )
                Imgproc.rectangle(
                    debugColor,
                    Point(OpticalFormLayout.Q_OPT_LEFT, OpticalFormLayout.Q_BAND_TOP),
                    Point(OpticalFormLayout.Q_OPT_RIGHT, OpticalFormLayout.Q_BAND_BOTTOM),
                    blue,
                    2
                )
            }

            val warnings = mutableListOf<String>()
            val reviewReasons = mutableListOf<ReviewReason>()

            // Block 1: T.C KİMLİK NO (11 x 10)
            val tcDigits = readDigitBlock(
                cleanInv = cleanInv,
                debugColor = debugColor,
                left = OpticalFormLayout.TC_LEFT,
                right = OpticalFormLayout.TC_RIGHT,
                top = OpticalFormLayout.TC_TOP,
                bottom = OpticalFormLayout.TC_BOTTOM,
                colCount = OpticalFormLayout.TC_COLS,
                rowCount = OpticalFormLayout.TC_ROWS,
                warnings = warnings,
                reviewReasons = reviewReasons,
                blockLabel = "T.C."
            )
            val tcString = if (tcDigits.all { it == "?" }) "" else tcDigits.joinToString("")
            val isTcValid = TcKimlikValidator.isValid(tcString)

            // Block 2: OKUL NO (5 x 10)
            val okulDigits = readDigitBlock(
                cleanInv = cleanInv,
                debugColor = debugColor,
                left = OpticalFormLayout.OKUL_LEFT,
                right = OpticalFormLayout.OKUL_RIGHT,
                top = OpticalFormLayout.OKUL_TOP,
                bottom = OpticalFormLayout.OKUL_BOTTOM,
                colCount = OpticalFormLayout.OKUL_COLS,
                rowCount = OpticalFormLayout.OKUL_ROWS,
                warnings = warnings,
                reviewReasons = reviewReasons,
                blockLabel = "Okul No"
            )
            val okulNo = if (okulDigits.all { it == "?" }) "" else okulDigits.joinToString("").trimStart('?')

            // Block 3: CEVAPLAR (25 x 5)
            val activeCount = soruSayisi.coerceIn(1, OpticalFormLayout.Q_MAX_ROWS)
            val qColW = (OpticalFormLayout.Q_OPT_RIGHT - OpticalFormLayout.Q_OPT_LEFT) /
                OpticalFormLayout.Q_OPTIONS_COUNT.toDouble()
            val qRowH = (OpticalFormLayout.Q_BAND_BOTTOM - OpticalFormLayout.Q_BAND_TOP) /
                OpticalFormLayout.Q_MAX_ROWS.toDouble()

            val studentAnswers = MutableList(activeCount) { "" }
            val scores = MutableList(activeCount) { 0 }
            var correctCount = 0
            var wrongCount = 0
            var blankCount = 0
            var hasMultiMarkOnQuestion = false

            val green = Scalar(34.0, 197.0, 94.0, 255.0)
            val orangeRed = Scalar(239.0, 68.0, 68.0, 255.0)

            for (qIdx in 0 until activeCount) {
                val y0 = OpticalFormLayout.Q_BAND_TOP + qIdx * qRowH
                val y1 = y0 + qRowH

                val fills = DoubleArray(OpticalFormLayout.Q_OPTIONS_COUNT) { optIdx ->
                    val x0 = OpticalFormLayout.Q_OPT_LEFT + optIdx * qColW
                    val x1 = x0 + qColW
                    drawSamplingBox(debugColor, x0, y0, x1, y1)
                    sampleCellFill(cleanInv, x0, y0, x1, y1)
                }

                val sortedAsc = fills.sorted()
                val colBaseline = sortedAsc.take(3).average()
                val ranked = fills.indices.sortedByDescending { fills[it] }
                val bestIdx = ranked[0]
                val secondIdx = ranked[1]
                val bestFill = fills[bestIdx]
                val secondFill = fills[secondIdx]
                val jump = bestFill - secondFill

                val expectedAns = answerKey.getOrNull(qIdx)?.trim()?.uppercase(Locale.ROOT).orEmpty()
                val maxPts = maxScores.getOrNull(qIdx)?.coerceAtLeast(0) ?: 0

                if (secondFill >= colBaseline + MULTI_ABOVE_BASELINE && jump < MULTI_MAX_DIFF) {
                    studentAnswers[qIdx] = "MULTI"
                    wrongCount++
                    hasMultiMarkOnQuestion = true
                    drawCellMarker(
                        debugColor,
                        OpticalFormLayout.Q_OPT_LEFT + bestIdx * qColW,
                        y0,
                        qColW,
                        qRowH,
                        orangeRed
                    )
                } else if (jump >= GAP_MIN_JUMP && bestFill >= colBaseline + GAP_MIN_ABOVE_BASELINE) {
                    val selected = OPTION_LABELS[bestIdx]
                    studentAnswers[qIdx] = selected
                    val isCorrect = expectedAns.isNotEmpty() && selected == expectedAns
                    if (isCorrect) {
                        scores[qIdx] = maxPts
                        correctCount++
                    } else {
                        wrongCount++
                    }
                    drawCellMarker(
                        debugColor,
                        OpticalFormLayout.Q_OPT_LEFT + bestIdx * qColW,
                        y0,
                        qColW,
                        qRowH,
                        if (isCorrect) green else orangeRed
                    )
                } else {
                    studentAnswers[qIdx] = ""
                    blankCount++
                    val expectedIdx = OPTION_LABELS.indexOf(expectedAns)
                    if (expectedIdx >= 0) {
                        drawCellMarker(
                            debugColor,
                            OpticalFormLayout.Q_OPT_LEFT + expectedIdx * qColW,
                            y0,
                            qColW,
                            qRowH,
                            orangeRed
                        )
                    }
                }
            }

            if (hasMultiMarkOnQuestion && ReviewReason.MULTI_MARK !in reviewReasons) {
                reviewReasons.add(ReviewReason.MULTI_MARK)
                warnings.add("Bazı sorularda çoklu işaretleme tespit edildi.")
            }

            val totalScore = scores.sum()
            val needsManualReview = !isTcValid || reviewReasons.isNotEmpty()

            val debugBitmap = debugColor?.let { mat ->
                Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888).also { bmp ->
                    Utils.matToBitmap(mat, bmp)
                }
            }

            return OmrReadResult(
                tcKimlikNo = tcString,
                okulNo = okulNo,
                isTcValid = isTcValid,
                scores = scores,
                totalScore = totalScore,
                codedTotalScore = null,
                hasTotalMismatch = false,
                needsManualReview = needsManualReview,
                multipleFormsDetected = false,
                reviewReasons = reviewReasons.distinct(),
                warnings = warnings.distinct(),
                debugBitmap = debugBitmap,
                studentAnswers = studentAnswers,
                correctCount = correctCount,
                wrongCount = wrongCount,
                blankCount = blankCount
            )
        } finally {
            claheGray.release()
            blurredGray.release()
            normGray.release()
            invGray.release()
            cleanInv.release()
            debugColor?.release()
        }
    }

    private fun readDigitBlock(
        cleanInv: Mat,
        debugColor: Mat?,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        colCount: Int,
        rowCount: Int,
        warnings: MutableList<String>,
        reviewReasons: MutableList<ReviewReason>,
        blockLabel: String
    ): List<String> {
        val colWidth = (right - left) / colCount.toDouble()
        val rowHeight = (bottom - top) / rowCount.toDouble()
        val green = Scalar(34.0, 197.0, 94.0, 255.0)
        val orangeRed = Scalar(239.0, 68.0, 68.0, 255.0)

        return List(colCount) { colIdx ->
            val x0 = left + colIdx * colWidth
            val x1 = x0 + colWidth
            val fills = DoubleArray(rowCount) { digit ->
                val y0 = top + digit * rowHeight
                val y1 = y0 + rowHeight
                drawSamplingBox(debugColor, x0, y0, x1, y1)
                sampleCellFill(cleanInv, x0, y0, x1, y1)
            }
            val sortedAsc = fills.sorted()
            val colBaseline = sortedAsc.take(rowCount / 2).average()
            val ranked = fills.indices.sortedByDescending { fills[it] }
            val best = ranked[0]
            val second = ranked[1]
            val bestFill = fills[best]
            val secondFill = fills[second]
            val jump = bestFill - secondFill

            if (secondFill >= colBaseline + MULTI_ABOVE_BASELINE && jump < MULTI_MAX_DIFF) {
                if (ReviewReason.MULTI_MARK !in reviewReasons) {
                    reviewReasons.add(ReviewReason.MULTI_MARK)
                }
                warnings.add("$blockLabel ${colIdx + 1}. hanede çoklu işaretleme.")
                drawCellMarker(debugColor, x0, top + best * rowHeight, colWidth, rowHeight, orangeRed)
                "?"
            } else if (jump >= GAP_MIN_JUMP && bestFill >= colBaseline + GAP_MIN_ABOVE_BASELINE) {
                drawCellMarker(debugColor, x0, top + best * rowHeight, colWidth, rowHeight, green)
                best.toString()
            } else {
                "?"
            }
        }
    }

    private fun sampleCellFill(cleanInv: Mat, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val w = x1 - x0
        val h = y1 - y0
        val coreW = w * INNER_CORE_W_RATIO
        val coreH = h * INNER_CORE_H_RATIO
        val rx = (x0 + (w - coreW) / 2.0).toInt().coerceIn(0, cleanInv.cols() - 1)
        val ry = (y0 + (h - coreH) / 2.0).toInt().coerceIn(0, cleanInv.rows() - 1)
        val rw = coreW.toInt().coerceAtLeast(1).coerceAtMost(cleanInv.cols() - rx)
        val rh = coreH.toInt().coerceAtLeast(1).coerceAtMost(cleanInv.rows() - ry)
        if (rw <= 0 || rh <= 0) return 0.0
        val roi = cleanInv.submat(Rect(rx, ry, rw, rh))
        return try {
            val meanVal = Core.mean(roi).`val`[0]
            (meanVal / 255.0).coerceIn(0.0, 1.0)
        } finally {
            roi.release()
        }
    }

    private fun drawSamplingBox(debugColor: Mat?, x0: Double, y0: Double, x1: Double, y1: Double) {
        if (debugColor == null) return
        val w = x1 - x0
        val h = y1 - y0
        val coreW = w * INNER_CORE_W_RATIO
        val coreH = h * INNER_CORE_H_RATIO
        val bx0 = x0 + (w - coreW) / 2.0
        val by0 = y0 + (h - coreH) / 2.0
        val yellow = Scalar(250.0, 204.0, 21.0, 255.0)
        Imgproc.rectangle(debugColor, Point(bx0, by0), Point(bx0 + coreW, by0 + coreH), yellow, 1)
    }

    private fun drawCellMarker(debugColor: Mat?, x0: Double, y0: Double, w: Double, h: Double, color: Scalar) {
        if (debugColor == null) return
        val cx = x0 + w / 2.0
        val cy = y0 + h / 2.0
        val r = (min(w, h) * 0.35).toInt().coerceAtLeast(4)
        Imgproc.circle(debugColor, Point(cx, cy), r, color, 2)
    }

    internal fun pickExtremeCorners(points: List<Point>): Array<Point>? {
        if (points.size < 4) return null
        val tl = points.indices.minBy { points[it].x + points[it].y }
        val tr = points.indices.maxBy { points[it].x - points[it].y }
        val br = points.indices.maxBy { points[it].x + points[it].y }
        val bl = points.indices.minBy { points[it].x - points[it].y }
        if (setOf(tl, tr, br, bl).size < 4) return null
        return arrayOf(points[tl], points[tr], points[br], points[bl])
    }

    private fun computeLaplacianVariance(gray: Mat): Double {
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        return try {
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, std)
            val s = std.get(0, 0)[0]
            s * s
        } finally {
            lap.release()
            mean.release()
            std.release()
        }
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun polygonArea(pts: Array<Point>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        return abs(area) / 2.0
    }
}
