package com.optiklik.app.domain.omr

import android.graphics.Bitmap
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV-based Optical Mark Recognition (OMR) engine for Optiklik,
 * adapted from Udayraj123/OMRChecker (CropOnMarkers + core.py + template.py).
 *
 * Template Architecture (13 Blocks, 39 Data Columns x 10 Rows):
 * - 4 Concentric Bullseye Markers at the outer corners (TL, TR, BR, BL)
 * - Block 0: ÖĞRENCİ T.C. KİMLİK NO (11 cols x 10 rows)
 * - Block 1: OKUL NO (5 cols x 10 rows)
 * - Blocks 2..11: SORU 1 .. SORU 10 (2 cols [tens, ones] x 10 rows each = 20 cols)
 * - Block 12: TOPLAM (3 cols [hundreds, tens, ones] x 10 rows)
 * - Top 23.5% of table height = title + handwriting boxes; bottom 76.5% = 0..9 matrix.
 *
 * NOTE (Handwriting OCR Backlog): Handwritten T.C./Okul No OCR comparison against bubble
 * codes is intentionally deferred to a future phase and is not executed in this engine.
 */
class OmrProcessor {

    companion object {
        const val WARP_WIDTH = 2100.0
        const val WARP_HEIGHT = 720.0
        private const val TARGET_WORK_WIDTH = 1600.0

        // Fallback vertical grid bounds inside 2100x720 marker-to-marker space
        private const val FALLBACK_TABLE_TOP = 76
        private const val FALLBACK_TABLE_BOTTOM = 644
        private const val HEADER_HEIGHT_RATIO = 0.235 // Top 23.5% header, bottom 76.5% matrix

        private val BLOCK_COL_WEIGHTS = intArrayOf(11, 5, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3)
        private const val IDENTITY_MIN_FILL = 0.21

        // Dynamic thresholding parameters (ported from OMRChecker/src/core.py to 0.0..1.0 scale)
        private const val QUESTION_MIN_FILL = 0.15
        private const val QUESTION_SECOND_DIFF = 0.11
        private const val MIN_JUMP = 0.06
        private const val JUMP_DELTA = 0.06
        private const val CONFIDENT_SURPLUS = 0.04
        private const val GLOBAL_DEFAULT_THRESHOLD = 0.22

        // Template Matching & Hysteresis thresholds (normalized on 1600 px width)
        private const val MIN_TEMPLATE_MATCH_SCORE = 0.45
        private const val SINGLE_SHOT_TEMPLATE_MATCH_SCORE = 0.38
        private const val HYSTERESIS_TEMPLATE_MATCH_SCORE = 0.36
        private const val MAX_HYSTERESIS_FRAMES = 3
        private const val HYSTERESIS_SEARCH_RADIUS_PX = 30
        private const val CORNER_ALIGN_TOLERANCE_PX = 60.0

        // Relative Radial Bullseye Contrast Verification thresholds (handles shadows & bright light)
        private const val MIN_RADIAL_CENTER_CONTRAST = 42.0  // whiteMean - centerMean > 42
        private const val MIN_RADIAL_OUTER_CONTRAST = 32.0   // whiteMean - outerMean > 32

        // Live camera guidance thresholds
        private const val MIN_LAPLACIAN_VARIANCE = 18.0
        private const val MIN_AREA_RATIO_LIVE = 0.05
        private const val LIVE_BORDER_MARGIN_PX = 4.0
        private const val MAX_SKEW_RATIO_DIFF = 0.25

        // Bullseye marker geometry filters (for full-page PDF / gallery contour detection)
        private const val MAX_CONCENTRIC_CENTER_DIST_PX = 8.0
        private const val MIN_BULLSEYE_CIRCULARITY = 0.42
        private const val MIN_BULLSEYE_DIAMETER_RATIO = 0.015
        private const val MAX_BULLSEYE_DIAMETER_RATIO = 0.085
        private const val MIN_QUAD_ASPECT = 1.65
        private const val MAX_QUAD_ASPECT = 3.65
        private const val MAX_PARALLEL_SIDE_DIFF = 0.10

        // Inner Core ROI ratios (52% width x 50% height to catch small/slightly shifted marks)
        private const val INNER_CORE_W_RATIO = 0.52
        private const val INNER_CORE_H_RATIO = 0.50

        // Proportional fallback block widths inside 2100x720
        private const val FALLBACK_TABLE_LEFT_PX = 92
        private const val FALLBACK_TABLE_RIGHT_PX = 2008
    }

    private data class MarkerCandidate(
        val center: Point,
        val area: Double,
        val depth: Int,
        val radius: Double,
        val circularity: Double
    )

    private data class TemplatePeak(
        val center: Point,
        val score: Double,
        val radius: Double
    )

    // 3-frame corner smoothing hysteresis state for live camera micro-shakes
    private var lastKnownCorners: Array<Point>? = null
    private var hysteresisFramesRemaining: Int = 0

    // Pre-generated 5-scale circular & elliptical concentric bullseye templates (CV_8UC1)
    private val bullseyeTemplates: List<Mat> by lazy { createBullseyeTemplates() }

    private fun createBullseyeTemplates(): List<Mat> {
        val specs = listOf(
            32 to 32, 42 to 42, 54 to 54, 68 to 68, // 4 circular scales
            46 to 26, 60 to 34, 76 to 44            // 3 elliptical scales
        )
        return specs.map { (w, h) ->
            val mat = Mat(h, w, CvType.CV_8UC1, Scalar(255.0))
            val center = Point(w / 2.0, h / 2.0)
            val rx = w * 0.44
            val ry = h * 0.44
            val black = Scalar(0.0)
            val white = Scalar(255.0)
            // Outer black ring
            Imgproc.ellipse(mat, center, Size(rx, ry), 0.0, 0.0, 360.0, black, -1)
            // Outer white gap
            Imgproc.ellipse(mat, center, Size(rx * 0.72, ry * 0.72), 0.0, 0.0, 360.0, white, -1)
            // Middle black ring
            Imgproc.ellipse(mat, center, Size(rx * 0.50, ry * 0.50), 0.0, 0.0, 360.0, black, -1)
            // Inner white gap
            Imgproc.ellipse(mat, center, Size(rx * 0.30, ry * 0.30), 0.0, 0.0, 360.0, white, -1)
            // Center black dot
            Imgproc.ellipse(mat, center, Size(rx * 0.16, ry * 0.16), 0.0, 0.0, 360.0, black, -1)
            Imgproc.GaussianBlur(mat, mat, Size(3.0, 3.0), 0.0)
            mat
        }
    }

    @Synchronized
    fun analyzeBitmap(
        bitmap: Bitmap,
        isLiveCamera: Boolean,
        includeDebugBitmap: Boolean = true,
        isSingleShotCapture: Boolean = false,
        detectCornersOnly: Boolean = false,
        soruSayisi: Int = 10,
        maxScores: List<Int> = List(10) { 10 }
    ): OmrFrameAnalysis {
        val rgba = Mat()
        val rawGray = Mat()
        val gray = Mat()
        val claheGray = Mat()
        val blurred = Mat()
        val bin = Mat()
        val hierarchy = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rawGray, Imgproc.COLOR_RGBA2GRAY)

            // Normalize every input (live preview, high-res ImageCapture, PDF) to fixed 1600 px width
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

            // Hybrid Corner Detection:
            // 1. Try local +/-30px hysteresis if live camera was recently locked
            // 2. Try RETR_TREE concentric contour hierarchy (primary)
            // 3. Fallback to 5-scale CLAHE Template Matching if RETR_TREE misses any corner
            val selection: CornerSelection = run {
                val useHysteresis = isLiveCamera && !isSingleShotCapture
                val localLock = if (useHysteresis) tryLocalHysteresisLock(gray) else null
                if (localLock != null) {
                    localLock
                } else {
                    Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
                    Imgproc.adaptiveThreshold(
                        blurred,
                        bin,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY_INV,
                        25,
                        10.0
                    )
                    val contours = ArrayList<MatOfPoint>()
                    Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
                    val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
                    clahe.apply(gray, claheGray)
                    val candidates = extractConcentricCandidates(contours, hierarchy, claheGray, imgW, imgH)
                    contours.forEach { it.release() }

                    val contourSelection = selectFourCornerMarkers(candidates, imgW, imgH, isLiveCamera = isLiveCamera)
                    if (contourSelection.quad != null) {
                        if (useHysteresis) {
                            lastKnownCorners = contourSelection.quad
                            hysteresisFramesRemaining = MAX_HYSTERESIS_FRAMES
                        }
                        contourSelection
                    } else {
                        val scoreThreshold = if (!isLiveCamera || isSingleShotCapture) {
                            SINGLE_SHOT_TEMPLATE_MATCH_SCORE
                        } else {
                            MIN_TEMPLATE_MATCH_SCORE
                        }
                        findCornerMarkersByTemplate(gray, scoreThreshold, useHysteresis, isLiveCamera)
                    }
                }
            }

            val normalizedMarkers = selection.foundPoints.map { pt ->
                (pt.x / imgW).toFloat().coerceIn(0f, 1f) to (pt.y / imgH).toFloat().coerceIn(0f, 1f)
            }

            if (selection.quad == null) {
                val guidance = if (selection.missingCorners.isNotEmpty()) {
                    GuidanceState.MissingMarkers(selection.missingCorners)
                } else {
                    GuidanceState.FrameNotFound
                }
                return OmrFrameAnalysis(guidance, markerPointsNormalized = normalizedMarkers)
            }

            val quad = selection.quad

            if (isLiveCamera && !isSingleShotCapture) {
                val outOfBounds = quad.any { p ->
                    p.x < LIVE_BORDER_MARGIN_PX ||
                        p.x > (imgW - LIVE_BORDER_MARGIN_PX) ||
                        p.y < LIVE_BORDER_MARGIN_PX ||
                        p.y > (imgH - LIVE_BORDER_MARGIN_PX)
                }
                if (outOfBounds) {
                    return OmrFrameAnalysis(GuidanceState.TooClose, markerPointsNormalized = normalizedMarkers)
                }

                val quadArea = polygonArea(quad)
                if (quadArea / (imgW * imgH) < MIN_AREA_RATIO_LIVE) {
                    return OmrFrameAnalysis(GuidanceState.TooFar, markerPointsNormalized = normalizedMarkers)
                }

                val topW = distance(quad[0], quad[1])
                val bottomW = distance(quad[3], quad[2])
                val leftH = distance(quad[0], quad[3])
                val rightH = distance(quad[1], quad[2])
                val wDiff = abs(topW - bottomW) / max(topW, bottomW)
                val hDiff = abs(leftH - rightH) / max(leftH, rightH)
                if (wDiff > MAX_SKEW_RATIO_DIFF || hDiff > MAX_SKEW_RATIO_DIFF) {
                    return OmrFrameAnalysis(GuidanceState.TooSkewed, markerPointsNormalized = normalizedMarkers)
                }
            }

            if (detectCornersOnly) {
                return OmrFrameAnalysis(
                    guidance = GuidanceState.Ready,
                    result = null,
                    markerPointsNormalized = normalizedMarkers
                )
            }

            // Perspective warp using the 4 marker centers -> fixed 2100 x 720
            val warped = warpToCanonical(gray, quad)
            return try {
                val result = readOmrGrid(
                    warped,
                    includeDebugBitmap,
                    soruSayisi,
                    maxScores,
                    multipleFormsDetected = selection.multipleFormsDetected,
                    isLiveCamera = isLiveCamera
                )
                OmrFrameAnalysis(
                    guidance = GuidanceState.Ready,
                    result = result,
                    markerPointsNormalized = normalizedMarkers
                )
            } finally {
                warped.release()
            }
        } finally {
            rgba.release()
            rawGray.release()
            gray.release()
            claheGray.release()
            blurred.release()
            bin.release()
            hierarchy.release()
        }
    }

    /**
     * Fast local +/-30 px hysteresis search around lastKnownCorners (1600 px scale).
     * Prevents green lock from flickering red during micro-shakes.
     */
    private fun tryLocalHysteresisLock(gray: Mat): CornerSelection? {
        val prevCorners = lastKnownCorners ?: return null
        val claheGray = Mat()
        try {
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(gray, claheGray)
            val w = claheGray.cols()
            val h = claheGray.rows()
            val localCenters = arrayOfNulls<Point>(4)

            for (i in 0 until 4) {
                val prev = prevCorners[i]
                val rx = (prev.x.toInt() - HYSTERESIS_SEARCH_RADIUS_PX).coerceIn(0, (w - 80).coerceAtLeast(0))
                val ry = (prev.y.toInt() - HYSTERESIS_SEARCH_RADIUS_PX).coerceIn(0, (h - 80).coerceAtLeast(0))
                val rw = (HYSTERESIS_SEARCH_RADIUS_PX * 2 + 66).coerceAtMost(w - rx)
                val rh = (HYSTERESIS_SEARCH_RADIUS_PX * 2 + 66).coerceAtMost(h - ry)
                if (rw <= 24 || rh <= 24) return null

                val peak = findTopTemplatePeaks(
                    claheGray,
                    Rect(rx, ry, rw, rh),
                    HYSTERESIS_TEMPLATE_MATCH_SCORE,
                    maxPeaks = 1
                ).firstOrNull { isValidRadialBullseye(claheGray, it.center, it.radius) } ?: return null

                localCenters[i] = peak.center
            }

            val ordered = arrayOf(localCenters[0]!!, localCenters[1]!!, localCenters[2]!!, localCenters[3]!!)
            if (!isValidOmrRectangle(ordered)) return null

            lastKnownCorners = ordered
            hysteresisFramesRemaining = MAX_HYSTERESIS_FRAMES
            return CornerSelection(quad = ordered, foundPoints = ordered.toList(), missingCorners = emptyList())
        } finally {
            claheGray.release()
        }
    }

    /**
     * Wide 4-quadrant multi-scale Template Matching (TM_CCOEFF_NORMED) with CLAHE preprocessing,
     * Relative Radial Bullseye Contrast Verification, and top-3 candidate geometric rectangle lock.
     */
    private fun findCornerMarkersByTemplate(
        gray: Mat,
        scoreThreshold: Double,
        useHysteresis: Boolean,
        isLiveCamera: Boolean
    ): CornerSelection {
        val claheGray = Mat()
        try {
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(gray, claheGray)

            val w = claheGray.cols()
            val h = claheGray.rows()
            val midY = h / 2

            var bestQuadScore = -1.0
            var bestQuad: Array<Point>? = null
            var multipleFormsDetected = false
            val found = mutableListOf<Point>()
            val missing = mutableListOf<String>()

            if (isLiveCamera) {
                // Live camera: search outer 26% left and right strips so angled/distant corners aren't clipped
                val leftStripW = (w * 0.26).toInt().coerceAtLeast(1)
                val rightStripX = (w * 0.74).toInt().coerceAtMost(w - 1)
                val rightStripW = (w - rightStripX).coerceAtLeast(1)

                val quadRects = listOf(
                    "Sol-Üst" to Rect(0, 0, leftStripW, midY),
                    "Sağ-Üst" to Rect(rightStripX, 0, rightStripW, midY),
                    "Sağ-Alt" to Rect(rightStripX, midY, rightStripW, h - midY),
                    "Sol-Alt" to Rect(0, midY, leftStripW, h - midY)
                )

                val quadCandidates = quadRects.map { (_, rect) ->
                    findTopTemplatePeaks(claheGray, rect, scoreThreshold, maxPeaks = 3)
                        .filter { peak -> isValidRadialBullseye(claheGray, peak.center, peak.radius) }
                }

                for (tl in quadCandidates[0]) {
                    for (tr in quadCandidates[1]) {
                        if (abs(tl.center.y - tr.center.y) >= CORNER_ALIGN_TOLERANCE_PX) continue
                        for (br in quadCandidates[2]) {
                            if (abs(tr.center.x - br.center.x) >= CORNER_ALIGN_TOLERANCE_PX) continue
                            for (bl in quadCandidates[3]) {
                                if (abs(tl.center.x - bl.center.x) >= CORNER_ALIGN_TOLERANCE_PX) continue
                                if (abs(bl.center.y - br.center.y) >= CORNER_ALIGN_TOLERANCE_PX) continue

                                val candidateQuad = arrayOf(tl.center, tr.center, br.center, bl.center)
                                if (!isValidOmrRectangle(candidateQuad)) continue

                                val totalScore = tl.score + tr.score + br.score + bl.score
                                if (totalScore > bestQuadScore) {
                                    bestQuadScore = totalScore
                                    bestQuad = candidateQuad
                                }
                            }
                        }
                    }
                }

                for ((idx, pair) in quadRects.withIndex()) {
                    val top = quadCandidates[idx].firstOrNull()
                    if (top != null) found.add(top.center) else missing.add(pair.first)
                }
            } else {
                // Full-page PDF / gallery mode: no midY split (form may be in top 38% of A4)
                val leftHalf = Rect(0, 0, (w * 0.55).toInt().coerceAtLeast(1), h)
                val rightStart = (w * 0.45).toInt().coerceAtMost(w - 1)
                val rightHalf = Rect(rightStart, 0, (w - rightStart).coerceAtLeast(1), h)

                val leftPeaks = findTopTemplatePeaks(claheGray, leftHalf, scoreThreshold, maxPeaks = 12)
                    .filter { isValidRadialBullseye(claheGray, it.center, it.radius) }
                val rightPeaks = findTopTemplatePeaks(claheGray, rightHalf, scoreThreshold, maxPeaks = 12)
                    .filter { isValidRadialBullseye(claheGray, it.center, it.radius) }

                val validQuads = mutableListOf<Pair<Double, Array<Point>>>()
                for (i in leftPeaks.indices) {
                    for (j in i + 1 until leftPeaks.size) {
                        val a = leftPeaks[i]
                        val b = leftPeaks[j]
                        val (tl, bl) = if (a.center.y < b.center.y) a to b else b to a
                        if (abs(tl.center.x - bl.center.x) >= CORNER_ALIGN_TOLERANCE_PX) continue

                        for (k in rightPeaks.indices) {
                            for (m in k + 1 until rightPeaks.size) {
                                val c = rightPeaks[k]
                                val d = rightPeaks[m]
                                val (tr, br) = if (c.center.y < d.center.y) c to d else d to c
                                if (tl.center.x >= tr.center.x || bl.center.x >= br.center.x) continue
                                if (abs(tr.center.x - br.center.x) >= CORNER_ALIGN_TOLERANCE_PX) continue
                                if (abs(tl.center.y - tr.center.y) >= CORNER_ALIGN_TOLERANCE_PX) continue
                                if (abs(bl.center.y - br.center.y) >= CORNER_ALIGN_TOLERANCE_PX) continue

                                val candidateQuad = arrayOf(tl.center, tr.center, br.center, bl.center)
                                if (!isValidOmrRectangle(candidateQuad)) continue

                                val areaRatio = polygonArea(candidateQuad) / (w.toDouble() * h.toDouble()).coerceAtLeast(1.0)
                                if (areaRatio < 0.05) continue

                                val score = (tl.score + tr.score + br.score + bl.score) + areaRatio * 2.0
                                validQuads.add(score to candidateQuad)
                                if (score > bestQuadScore) {
                                    bestQuadScore = score
                                    bestQuad = candidateQuad
                                }
                            }
                        }
                    }
                }

                if (bestQuad != null) {
                    multipleFormsDetected = validQuads.any { (_, other) ->
                        areQuadsDisjoint(bestQuad!!, other)
                    }
                }

                (leftPeaks.take(2) + rightPeaks.take(2)).forEach { found.add(it.center) }
                missing.addAll(listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt"))
            }

            if (bestQuad != null) {
                if (useHysteresis) {
                    lastKnownCorners = bestQuad
                    hysteresisFramesRemaining = MAX_HYSTERESIS_FRAMES
                }
                return CornerSelection(
                    quad = bestQuad,
                    foundPoints = bestQuad.toList(),
                    missingCorners = emptyList(),
                    multipleFormsDetected = multipleFormsDetected
                )
            }

            val prevCorners = lastKnownCorners
            if (useHysteresis && prevCorners != null && hysteresisFramesRemaining > 0) {
                hysteresisFramesRemaining--
                return CornerSelection(
                    quad = prevCorners,
                    foundPoints = prevCorners.toList(),
                    missingCorners = emptyList()
                )
            }

            lastKnownCorners = null
            hysteresisFramesRemaining = 0

            if (missing.isEmpty()) {
                missing.addAll(listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt"))
            }
            return CornerSelection(quad = null, foundPoints = found, missingCorners = missing)
        } finally {
            claheGray.release()
        }
    }

    /**
     * Scale-adaptive 5-band Radial Bullseye Contrast Verification around (cx, cy) on CLAHE grayscale:
     * - Center dark disk: 0.00..0.22 * r
     * - Inner white ring: 0.26..0.42 * r
     * - Middle dark ring: 0.46..0.64 * r
     * - Outer white ring: 0.68..0.84 * r
     * - Outer dark ring:  0.86..1.05 * r
     * Valid when (whiteMean - darkMean) > 32.0.
     */
    private fun isValidRadialBullseye(claheGray: Mat, center: Point, radius: Double): Boolean {
        val r = radius.coerceIn(10.0, 42.0)
        val maxR = kotlin.math.ceil(r * 1.05).toInt()
        val cx = center.x.toInt()
        val cy = center.y.toInt()
        val w = claheGray.cols()
        val h = claheGray.rows()
        if (cx < maxR || cy < maxR || cx >= w - maxR || cy >= h - maxR) return false

        val side = maxR * 2 + 1
        val patch = claheGray.submat(cy - maxR, cy + maxR + 1, cx - maxR, cx + maxR + 1)
        val buf = ByteArray(side * side)
        try {
            patch.get(0, 0, buf)
        } finally {
            patch.release()
        }

        val r0End = 0.22 * r
        val r1Start = 0.26 * r
        val r1End = 0.42 * r
        val r2Start = 0.46 * r
        val r2End = 0.64 * r
        val r3Start = 0.68 * r
        val r3End = 0.84 * r
        val r4Start = 0.86 * r
        val r4End = 1.05 * r

        var darkSum = 0.0
        var darkCount = 0
        var whiteSum = 0.0
        var whiteCount = 0

        for (dy in -maxR..maxR) {
            val rowOffset = (dy + maxR) * side
            for (dx in -maxR..maxR) {
                val dist = hypot(dx.toDouble(), dy.toDouble())
                val px = (buf[rowOffset + (dx + maxR)].toInt() and 0xFF).toDouble()
                when {
                    dist <= r0End || (dist >= r2Start && dist <= r2End) || (dist >= r4Start && dist <= r4End) -> {
                        darkSum += px
                        darkCount++
                    }
                    (dist >= r1Start && dist <= r1End) || (dist >= r3Start && dist <= r3End) -> {
                        whiteSum += px
                        whiteCount++
                    }
                }
            }
        }

        if (darkCount == 0 || whiteCount == 0) return false
        val darkMean = darkSum / darkCount
        val whiteMean = whiteSum / whiteCount

        return (whiteMean - darkMean) > 32.0
    }

    private fun isValidOmrRectangle(quad: Array<Point>): Boolean {
        val (tl, tr, br, bl) = quad
        if (abs(tl.x - bl.x) >= CORNER_ALIGN_TOLERANCE_PX) return false
        if (abs(tr.x - br.x) >= CORNER_ALIGN_TOLERANCE_PX) return false
        if (abs(tl.y - tr.y) >= CORNER_ALIGN_TOLERANCE_PX) return false
        if (abs(bl.y - br.y) >= CORNER_ALIGN_TOLERANCE_PX) return false

        val topW = distance(tl, tr)
        val bottomW = distance(bl, br)
        val leftH = distance(tl, bl)
        val rightH = distance(tr, br)
        val maxW = max(topW, bottomW)
        val maxH = max(leftH, rightH)
        if (maxW <= 60.0 || maxH <= 15.0) return false

        val wDiff = abs(topW - bottomW) / maxW
        val hDiff = abs(leftH - rightH) / maxH
        if (wDiff > MAX_PARALLEL_SIDE_DIFF || hDiff > MAX_PARALLEL_SIDE_DIFF) return false

        val avgW = (topW + bottomW) / 2.0
        val avgH = (leftH + rightH) / 2.0
        val aspect = avgW / avgH
        return aspect in MIN_QUAD_ASPECT..MAX_QUAD_ASPECT
    }

    private fun findTopTemplatePeaks(
        claheGray: Mat,
        rect: Rect,
        scoreThreshold: Double,
        maxPeaks: Int
    ): List<TemplatePeak> {
        val roi = claheGray.submat(rect)
        val allPeaks = mutableListOf<TemplatePeak>()
        try {
            for (tmpl in bullseyeTemplates) {
                if (roi.cols() < tmpl.cols() || roi.rows() < tmpl.rows()) continue
                val res = Mat()
                try {
                    Imgproc.matchTemplate(roi, tmpl, res, Imgproc.TM_CCOEFF_NORMED)
                    val work = res.clone()
                    try {
                        for (k in 0 until maxPeaks) {
                            val mm = Core.minMaxLoc(work)
                            if (mm.maxVal < scoreThreshold) break
                            val cx = rect.x + mm.maxLoc.x + tmpl.cols() / 2.0
                            val cy = rect.y + mm.maxLoc.y + tmpl.rows() / 2.0
                            val tmplRadius = max(tmpl.cols(), tmpl.rows()) * 0.44
                            allPeaks.add(TemplatePeak(Point(cx, cy), mm.maxVal, tmplRadius))
                            // Suppress 24px neighborhood around this peak to find distinct candidates
                            Imgproc.circle(work, mm.maxLoc, 24, Scalar(-1.0), -1)
                        }
                    } finally {
                        work.release()
                    }
                } finally {
                    res.release()
                }
            }
        } finally {
            roi.release()
        }

        // Deduplicate peaks across scales within 25 px
        val sorted = allPeaks.sortedByDescending { it.score }
        val distinct = mutableListOf<TemplatePeak>()
        for (p in sorted) {
            if (distinct.none { distance(it.center, p.center) < 25.0 }) {
                distinct.add(p)
                if (distinct.size >= maxPeaks) break
            }
        }
        return distinct
    }

    private data class CornerSelection(
        val quad: Array<Point>?,
        val foundPoints: List<Point>,
        val missingCorners: List<String>,
        val multipleFormsDetected: Boolean = false
    )

    private fun extractConcentricCandidates(
        contours: List<MatOfPoint>,
        hierarchy: Mat,
        claheGray: Mat,
        imgW: Double,
        imgH: Double
    ): List<MarkerCandidate> {
        if (contours.isEmpty() || hierarchy.empty()) return emptyList()
        val minDiameter = imgW * MIN_BULLSEYE_DIAMETER_RATIO
        val maxDiameter = imgW * MAX_BULLSEYE_DIAMETER_RATIO

        val rawCandidates = mutableListOf<MarkerCandidate>()

        for (i in contours.indices) {
            val hData = hierarchy.get(0, i) ?: continue
            val firstChild = hData[2].toInt()
            if (firstChild < 0 || firstChild >= contours.size) continue

            // Require at least 3 nested contours (depth >= 2: outer ring -> white gap -> inner ring/dot)
            // This naturally rejects single-loop digits like '0', '6', '8', '9' which have depth == 1.
            var depth = 0
            var cur = firstChild
            val visited = HashSet<Int>()
            while (cur >= 0 && cur < contours.size && visited.add(cur)) {
                depth++
                val ch = hierarchy.get(0, cur) ?: break
                cur = ch[2].toInt()
            }
            if (depth < 2) continue

            // Size & aspect ratio check on outer contour (allows elliptical bullseyes up to ~2.8:1)
            val rect = Imgproc.boundingRect(contours[i])
            if (rect.width < 8 || rect.height < 6) continue
            val aspect = rect.width.toDouble() / rect.height.toDouble()
            if (aspect !in 0.70..2.80) continue

            val diameter = max(rect.width, rect.height).toDouble()
            if (diameter !in minDiameter..maxDiameter) continue

            val area = Imgproc.contourArea(contours[i])
            if (area <= 0.0) continue

            // Outer contour circularity > 0.42 (accommodates stretched ellipses)
            val outerC2f = MatOfPoint2f(*contours[i].toArray())
            val outerPeri = Imgproc.arcLength(outerC2f, true)
            outerC2f.release()
            if (outerPeri <= 0.0) continue
            val outerCircularity = (4.0 * Math.PI * area) / (outerPeri * outerPeri)
            if (outerCircularity <= MIN_BULLSEYE_CIRCULARITY) continue

            // Child contour circularity > 0.42
            val childArea = Imgproc.contourArea(contours[firstChild])
            if (childArea <= 0.0) continue
            val childC2f = MatOfPoint2f(*contours[firstChild].toArray())
            val childPeri = Imgproc.arcLength(childC2f, true)
            childC2f.release()
            if (childPeri <= 0.0) continue
            val childCircularity = (4.0 * Math.PI * childArea) / (childPeri * childPeri)
            if (childCircularity <= MIN_BULLSEYE_CIRCULARITY) continue

            // Concentricity: Euclidean distance between outer center and child center <= 8.0 px
            val centerX = rect.x + rect.width / 2.0
            val centerY = rect.y + rect.height / 2.0
            val childRect = Imgproc.boundingRect(contours[firstChild])
            val childCx = childRect.x + childRect.width / 2.0
            val childCy = childRect.y + childRect.height / 2.0
            if (hypot(centerX - childCx, centerY - childCy) > MAX_CONCENTRIC_CENTER_DIST_PX) continue

            val centerPt = Point(centerX, centerY)
            val candRadius = diameter / 2.0
            if (!isValidRadialBullseye(claheGray, centerPt, candRadius)) continue

            rawCandidates.add(
                MarkerCandidate(
                    center = centerPt,
                    area = area,
                    depth = depth,
                    radius = candRadius,
                    circularity = (outerCircularity + childCircularity) / 2.0
                )
            )
        }

        // Deduplicate nested rings belonging to the same bullseye (keep largest/outermost)
        val sorted = rawCandidates.sortedByDescending { it.depth * 100000.0 + it.circularity * 10000.0 + it.area }
        val deduplicated = mutableListOf<MarkerCandidate>()
        for (cand in sorted) {
            val tooClose = deduplicated.any { existing ->
                distance(existing.center, cand.center) < max(existing.radius, cand.radius) * 1.4
            }
            if (!tooClose) {
                deduplicated.add(cand)
            }
        }
        return deduplicated
    }

    private fun selectFourCornerMarkers(
        candidates: List<MarkerCandidate>,
        imgW: Double,
        imgH: Double,
        isLiveCamera: Boolean
    ): CornerSelection {
        if (candidates.isEmpty()) {
            return CornerSelection(
                quad = null,
                foundPoints = emptyList(),
                missingCorners = listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt")
            )
        }

        // For batch/full-page scans (e.g. A4 PDF), search all valid 4-tuples across the entire page
        // without any 0.50 * imgH midpoint restriction.
        if (!isLiveCamera) {
            if (candidates.size >= 4) {
                val topCands = candidates.take(24)
                var bestScore = -1.0
                var bestQuad: Array<Point>? = null

                val validQuads = mutableListOf<Pair<Double, Array<Point>>>()
                for (a in 0 until topCands.size) {
                    for (b in a + 1 until topCands.size) {
                        for (c in b + 1 until topCands.size) {
                            for (d in c + 1 until topCands.size) {
                                val quadCands = listOf(topCands[a], topCands[b], topCands[c], topCands[d])
                                val ordered = orderPoints(quadCands.map { it.center }.toTypedArray())
                                val (tl, tr, br, bl) = ordered

                                val topW = distance(tl, tr)
                                val bottomW = distance(bl, br)
                                val leftH = distance(tl, bl)
                                val rightH = distance(tr, br)
                                val avgW = (topW + bottomW) / 2.0
                                val avgH = (leftH + rightH) / 2.0
                                if (avgH < 20.0 || avgW < 60.0) continue
                                if ((avgW * avgH) / (imgW * imgH) < 0.05) continue

                                val aspect = avgW / avgH
                                if (aspect !in MIN_QUAD_ASPECT..MAX_QUAD_ASPECT) continue

                                val wDiff = abs(topW - bottomW) / max(topW, bottomW)
                                val hDiff = abs(leftH - rightH) / max(leftH, rightH)
                                if (wDiff > MAX_PARALLEL_SIDE_DIFF || hDiff > MAX_PARALLEL_SIDE_DIFF) continue

                                val maxArea = quadCands.maxOf { it.area }
                                val minArea = quadCands.minOf { it.area }
                                if (maxArea / max(minArea, 1.0) > 4.0) continue

                                val totalDepth = quadCands.sumOf { it.depth }
                                val score = totalDepth * 10000.0 + avgW * avgH
                                validQuads.add(score to ordered)
                                if (score > bestScore) {
                                    bestScore = score
                                    bestQuad = ordered
                                }
                            }
                        }
                    }
                }

                if (bestQuad != null) {
                    val hasSecondForm = validQuads.any { (_, other) ->
                        areQuadsDisjoint(bestQuad!!, other)
                    }
                    return CornerSelection(
                        quad = bestQuad,
                        foundPoints = bestQuad.toList(),
                        missingCorners = emptyList(),
                        multipleFormsDetected = hasSecondForm
                    )
                }
            }
            return CornerSelection(
                quad = null,
                foundPoints = candidates.take(4).map { it.center },
                missingCorners = listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt")
            )
        }

        // Live camera: restrict candidates to outer 26% left and right strips
        val leftMaxX = imgW * 0.26
        val rightMinX = imgW * 0.74
        val topMaxY = imgH * 0.45
        val bottomMinY = imgH * 0.55

        val tl = candidates.filter { it.center.x <= leftMaxX && it.center.y <= topMaxY }
            .maxByOrNull { it.depth * 10000.0 + it.circularity * 5000.0 + it.area }
        val tr = candidates.filter { it.center.x >= rightMinX && it.center.y <= topMaxY }
            .maxByOrNull { it.depth * 10000.0 + it.circularity * 5000.0 + it.area }
        val br = candidates.filter { it.center.x >= rightMinX && it.center.y >= bottomMinY }
            .maxByOrNull { it.depth * 10000.0 + it.circularity * 5000.0 + it.area }
        val bl = candidates.filter { it.center.x <= leftMaxX && it.center.y >= bottomMinY }
            .maxByOrNull { it.depth * 10000.0 + it.circularity * 5000.0 + it.area }

        val found = listOfNotNull(tl?.center, tr?.center, br?.center, bl?.center)
        val missing = mutableListOf<String>()
        if (tl == null) missing.add("Sol-Üst")
        if (tr == null) missing.add("Sağ-Üst")
        if (br == null) missing.add("Sağ-Alt")
        if (bl == null) missing.add("Sol-Alt")

        if (missing.isNotEmpty()) {
            return CornerSelection(quad = null, foundPoints = found, missingCorners = missing)
        }

        val ordered = arrayOf(tl!!.center, tr!!.center, br!!.center, bl!!.center)
        val topW = distance(ordered[0], ordered[1])
        val bottomW = distance(ordered[3], ordered[2])
        val leftH = distance(ordered[0], ordered[3])
        val rightH = distance(ordered[1], ordered[2])
        val avgW = (topW + bottomW) / 2.0
        val avgH = (leftH + rightH) / 2.0
        if (avgH <= 10.0) {
            return CornerSelection(quad = null, foundPoints = found, missingCorners = listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt"))
        }

        val aspect = avgW / avgH
        val wDiff = abs(topW - bottomW) / max(topW, bottomW)
        val hDiff = abs(leftH - rightH) / max(leftH, rightH)
        if (aspect !in MIN_QUAD_ASPECT..MAX_QUAD_ASPECT || wDiff >= MAX_SKEW_RATIO_DIFF || hDiff >= MAX_SKEW_RATIO_DIFF) {
            return CornerSelection(quad = null, foundPoints = emptyList(), missingCorners = listOf("Sol-Üst", "Sağ-Üst", "Sağ-Alt", "Sol-Alt"))
        }

        return CornerSelection(
            quad = ordered,
            foundPoints = found,
            missingCorners = emptyList()
        )
    }

    private fun computeLaplacianVariance(gray: Mat): Double {
        val lap = Mat()
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, stdDev)
            val sd = stdDev.get(0, 0)[0]
            return sd * sd
        } finally {
            lap.release()
            mean.release()
            stdDev.release()
        }
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val sumSorted = pts.sortedBy { it.x + it.y }
        val diffSorted = pts.sortedBy { it.y - it.x }
        return arrayOf(
            sumSorted.first(), // TL
            diffSorted.first(), // TR
            sumSorted.last(),  // BR
            diffSorted.last()  // BL
        )
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun areQuadsDisjoint(q1: Array<Point>, q2: Array<Point>): Boolean {
        val y1Min = q1.minOf { it.y }
        val y1Max = q1.maxOf { it.y }
        val y2Min = q2.minOf { it.y }
        val y2Max = q2.maxOf { it.y }
        val overlapY = (min(y1Max, y2Max) - max(y1Min, y2Min)).coerceAtLeast(0.0)
        val minH = min(y1Max - y1Min, y2Max - y2Min).coerceAtLeast(1.0)
        return (overlapY / minH) < 0.20
    }

    private fun polygonArea(pts: Array<Point>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        return abs(area) / 2.0
    }

    private fun warpToCanonical(gray: Mat, quad: Array<Point>): Mat {
        val src = MatOfPoint2f(*quad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(WARP_WIDTH - 1, 0.0),
            Point(WARP_WIDTH - 1, WARP_HEIGHT - 1),
            Point(0.0, WARP_HEIGHT - 1)
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(gray, warped, transform, Size(WARP_WIDTH, WARP_HEIGHT))
            return warped
        } finally {
            src.release()
            dst.release()
            transform.release()
        }
    }

    private fun readOmrGrid(
        warpedGray: Mat,
        includeDebugBitmap: Boolean,
        soruSayisi: Int = 10,
        maxScores: List<Int> = List(10) { 10 },
        multipleFormsDetected: Boolean = false,
        isLiveCamera: Boolean = true
    ): OmrReadResult {
        val claheGray = Mat()
        val blurredGray = Mat()
        val normGray = Mat()
        val invGray = Mat()
        val cleanInv = Mat()
        val binLinesFull = Mat()
        var debugColor: Mat? = null

        try {
            // Stage 3a: OMRChecker-style normalization: CLAHE + GaussianBlur + Normalize + Erosion
            val clahe = Imgproc.createCLAHE(5.0, Size(8.0, 8.0))
            clahe.apply(warpedGray, claheGray)
            Imgproc.GaussianBlur(claheGray, blurredGray, Size(3.0, 3.0), 0.0)
            Core.normalize(blurredGray, normGray, 0.0, 255.0, Core.NORM_MINMAX)

            // Invert so pencil marks are high (bright) and paper is 0, then erode to suppress thin printed digits (0, 6, 8) while preserving pencil marks.
            Core.bitwise_not(normGray, invGray)
            val erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(4.0, 4.0))
            Imgproc.erode(invGray, cleanInv, erodeKernel)
            erodeKernel.release()

            // Otsu binary for horizontal/vertical white-gap projection of the table grid
            Imgproc.threshold(blurredGray, binLinesFull, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

            // Stage 2: Fine-tune vertical matrix bounds (top 22% header, bottom 78% 0..9 matrix)
            val (gridTop, gridBottom) = detectVerticalGridBounds(binLinesFull)
            val binLinesBand = binLinesFull.submat(gridTop, gridBottom, 0, binLinesFull.cols())
            val height = (gridBottom - gridTop).toDouble()
            val rowHeight = height / 10.0

            // Fine-tune horizontal bounds for all 13 blocks
            // (Block 0: TC, Block 1: OKUL NO, Blocks 2..11: S1..S10, Block 12: TOPLAM)
            val blockBounds = detect13BlockBounds(binLinesBand, binLinesFull.cols(), isLiveCamera)
            binLinesBand.release()

            if (includeDebugBitmap) {
                debugColor = Mat()
                Imgproc.cvtColor(warpedGray, debugColor, Imgproc.COLOR_GRAY2RGBA)

                // Draw 4 corner bullseye marker centers
                val cyan = Scalar(6.0, 182.0, 212.0, 255.0)
                val corners = listOf(
                    Point(18.0, 18.0),
                    Point(WARP_WIDTH - 19.0, 18.0),
                    Point(WARP_WIDTH - 19.0, WARP_HEIGHT - 19.0),
                    Point(18.0, WARP_HEIGHT - 19.0)
                )
                corners.forEach { pt ->
                    Imgproc.circle(debugColor, pt, 14, cyan, 4)
                    Imgproc.circle(debugColor, pt, 4, cyan, -1)
                }

                // Draw 13 block boundaries
                val blue = Scalar(59.0, 130.0, 246.0, 255.0)
                blockBounds.forEach { (left, right) ->
                    Imgproc.rectangle(
                        debugColor,
                        Point(left.toDouble(), gridTop.toDouble()),
                        Point(right.toDouble(), gridBottom.toDouble()),
                        blue,
                        2
                    )
                }
            }

            // Stage 3b: Collect all 39 columns x 10 rows (390 cells) darkness stats using Inner Core ROI
            data class ColumnSpec(val blockIndex: Int, val centerX: Double, val colWidth: Double)
            val allColumns = mutableListOf<ColumnSpec>()

            // Block 0: T.C. Kimlik (11 columns) -> indices 0..10
            val tcBlock = blockBounds[0]
            val tcColW = (tcBlock.second - tcBlock.first).toDouble() / 11.0
            for (c in 0 until 11) {
                allColumns.add(ColumnSpec(0, tcBlock.first + (c + 0.5) * tcColW, tcColW))
            }

            // Block 1: Okul No (5 columns) -> indices 11..15
            val okulBlock = blockBounds[1]
            val okulColW = (okulBlock.second - okulBlock.first).toDouble() / 5.0
            for (c in 0 until 5) {
                allColumns.add(ColumnSpec(1, okulBlock.first + (c + 0.5) * okulColW, okulColW))
            }

            // Blocks 2..11: Soru 1..10 (2 columns each = 20 columns) -> indices 16..35
            for (q in 2..11) {
                val qBlock = blockBounds[q]
                val qColW = (qBlock.second - qBlock.first).toDouble() / 2.0
                allColumns.add(ColumnSpec(q, qBlock.first + 0.5 * qColW, qColW))
                allColumns.add(ColumnSpec(q, qBlock.first + 1.5 * qColW, qColW))
            }

            // Block 12: TOPLAM (3 columns) -> indices 36..38
            val totalBlock = blockBounds[12]
            val totalColW = (totalBlock.second - totalBlock.first).toDouble() / 3.0
            for (c in 0 until 3) {
                allColumns.add(ColumnSpec(12, totalBlock.first + (c + 0.5) * totalColW, totalColW))
            }

            val allColValues = ArrayList<DoubleArray>(39)
            val allQVals = ArrayList<Double>(390)
            val allQStdVals = ArrayList<Double>(39)

            for (col in allColumns) {
                val colVals = readColumnInnerCore(cleanInv, col.centerX, col.colWidth, gridTop, rowHeight)
                allColValues.add(colVals)
                colVals.forEach { allQVals.add(it) }
                allQStdVals.add(stdDev(colVals))
            }

            // OMRChecker dynamic global & local thresholding
            val globalStdThresh = getGlobalThreshold(allQStdVals, looseness = 1)
            val globalThresh = getGlobalThreshold(allQVals, looseness = 4)

            val green = Scalar(34.0, 197.0, 94.0, 255.0)
            val orange = Scalar(249.0, 115.0, 22.0, 255.0)
            val red = Scalar(239.0, 68.0, 68.0, 255.0)
            val sampleBoxColor = Scalar(234.0, 179.0, 8.0, 255.0)

            // Draw inner core sampling rectangles in debug mode
            if (debugColor != null) {
                for (col in allColumns) {
                    val coreW = col.colWidth * INNER_CORE_W_RATIO
                    val coreH = rowHeight * INNER_CORE_H_RATIO
                    val x1 = (col.centerX - coreW / 2.0)
                    val x2 = (col.centerX + coreW / 2.0)
                    for (row in 0 until 10) {
                        val cy = gridTop + (row + 0.5) * rowHeight
                        Imgproc.rectangle(
                            debugColor,
                            Point(x1, cy - coreH / 2.0),
                            Point(x2, cy + coreH / 2.0),
                            sampleBoxColor,
                            1
                        )
                    }
                }
            }

            // Adaptive background-normalized identity column reader with multi-mark detection
            var hasIdentityMultiMark = false
            fun readIdentityColumn(colIndex: Int): Char? {
                val colVals = allColValues[colIndex]
                val sortedVals = colVals.sorted()
                val colBaseline = sortedVals.take(7).average()
                val adaptiveMinFill = max(IDENTITY_MIN_FILL, colBaseline + 0.12)

                val indexedSorted = colVals.withIndex().sortedByDescending { it.value }
                val best = indexedSorted[0]
                val second = indexedSorted[1]

                if (best.value <= adaptiveMinFill) {
                    return null // Uncoded / empty column
                }

                // Multi-mark detection: >1 bubble filled above adaptive threshold or close top-2
                val filledCount = colVals.count { it > adaptiveMinFill }
                val isCloseMultiMark = second.value > adaptiveMinFill && (best.value - second.value) < 0.06
                if (filledCount > 1 && isCloseMultiMark) {
                    hasIdentityMultiMark = true
                    if (debugColor != null) {
                        val cy1 = gridTop + (best.index + 0.5) * rowHeight
                        val cy2 = gridTop + (second.index + 0.5) * rowHeight
                        Imgproc.circle(debugColor, Point(allColumns[colIndex].centerX, cy1), 8, red, -1)
                        Imgproc.circle(debugColor, Point(allColumns[colIndex].centerX, cy2), 8, red, -1)
                    }
                    return '?'
                }

                if ((best.value - second.value) < 0.08) {
                    return null
                }

                if (debugColor != null) {
                    val cy = gridTop + (best.index + 0.5) * rowHeight
                    Imgproc.circle(debugColor, Point(allColumns[colIndex].centerX, cy), 8, green, -1)
                    Imgproc.putText(
                        debugColor,
                        "${(best.value * 100).toInt()}%",
                        Point(allColumns[colIndex].centerX - 12.0, cy - 10.0),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        0.32,
                        green,
                        1
                    )
                }
                return ('0'.code + best.index).toChar()
            }

            // Read Block 0: T.C. Kimlik (11 digits, indices 0..10)
            val tcChars = CharArray(11) { '?' }
            var tcCodedCount = 0
            for (c in 0 until 11) {
                val ch = readIdentityColumn(c)
                if (ch != null) {
                    tcChars[c] = ch
                    tcCodedCount++
                }
            }
            val tcString = if (tcCodedCount == 0) "" else String(tcChars)
            val isTcChecksumValid = tcString.length == 11 && !tcString.contains('?') && TcKimlikValidator.isValid(tcString)

            // Read Block 1: Okul No (5 digits, indices 11..15)
            val okulChars = arrayOfNulls<Char>(5)
            var okulCodedCount = 0
            for (c in 0 until 5) {
                val ch = readIdentityColumn(11 + c)
                if (ch != null) {
                    okulChars[c] = ch
                    okulCodedCount++
                }
            }
            val okulNoString = if (okulCodedCount == 0) {
                ""
            } else {
                val firstActive = okulChars.indexOfFirst { it != null }
                val lastActive = okulChars.indexOfLast { it != null }
                val raw = buildString {
                    for (c in firstActive..lastActive) {
                        append(okulChars[c] ?: '?')
                    }
                }
                if (raw.contains('?')) raw else raw.trimStart('0').ifEmpty { "0" }
            }

            // Helper to read a question/total digit using background-normalized adaptive threshold + gap check + double-bubble
            var hasDoubleBubble = false
            fun readDigitWithDynamicThreshold(
                colIndex: Int,
                allowedDigits: IntRange = 0..9
            ): Int? {
                val colVals = allColValues[colIndex]
                val colBaseline = colVals.sorted().take(7).average()
                val adaptiveMinFill = max(QUESTION_MIN_FILL, colBaseline + 0.09)
                val noOutliers = allQStdVals[colIndex] < globalStdThresh
                val localThresh = getLocalThreshold(colVals, globalThresh, noOutliers)
                val sorted = colVals.withIndex()
                    .filter { it.index in allowedDigits }
                    .sortedByDescending { it.value }
                if (sorted.isEmpty()) return null
                val best = sorted[0]
                val second = sorted.getOrNull(1)

                val minGap = 0.05
                val isDoubleBubble = second != null &&
                    best.value >= adaptiveMinFill &&
                    second.value >= adaptiveMinFill &&
                    (best.value - second.value) < minGap
                if (isDoubleBubble) {
                    hasDoubleBubble = true
                    return best.index
                }

                val passesGap = second == null || (best.value - second.value) >= minGap
                val passesMin = best.value >= adaptiveMinFill
                val passesDynamic = !noOutliers || best.value >= (localThresh * 0.80)

                return if (passesMin && passesGap && passesDynamic) best.index else null
            }

            // Stage 4: Read Blocks 2..11 (Soru 1..10, indices 16..35)
            val activeQuestionCount = soruSayisi.coerceIn(1, 10)
            val scores = mutableListOf<Int>()
            val maxScoreWarnings = mutableListOf<String>()
            var hasMaxScoreExceeded = false

            for (q in 0 until 10) {
                if (q >= activeQuestionCount) {
                    scores.add(0)
                    continue
                }

                val maxScore = maxScores.getOrElse(q) { 99 }.coerceIn(0, 99)
                val maxTens = (maxScore / 10).coerceIn(0, 9)
                val tensColIdx = 16 + q * 2
                val onesColIdx = 16 + q * 2 + 1

                val tens = if (maxScore in 1..9) null else readDigitWithDynamicThreshold(tensColIdx, allowedDigits = 0..maxTens)
                val onesAllowed = if (tens == maxTens) 0..(maxScore % 10) else 0..9
                val ones = readDigitWithDynamicThreshold(onesColIdx, allowedDigits = onesAllowed)

                if (debugColor != null) {
                    tens?.let { d ->
                        Imgproc.circle(debugColor, Point(allColumns[tensColIdx].centerX, gridTop + (d + 0.5) * rowHeight), 8, green, -1)
                    }
                    ones?.let { d ->
                        Imgproc.circle(debugColor, Point(allColumns[onesColIdx].centerX, gridTop + (d + 0.5) * rowHeight), 8, green, -1)
                    }
                }

                val rawScore = when {
                    tens == null && ones == null -> 0
                    tens == null && ones != null -> ones
                    tens != null && ones == null -> tens * 10
                    else -> (tens!! * 10) + ones!!
                }.coerceIn(0, 99)

                if (rawScore > maxScore) {
                    hasMaxScoreExceeded = true
                    maxScoreWarnings.add("S${q + 1} okunan puan ($rawScore) maksimum puanı ($maxScore) aşıyor")
                }
                scores.add(rawScore.coerceAtMost(maxScore))
            }

            val calculatedTotal = scores.sum()

            // Read Block 12: TOPLAM (3 columns: hundreds, tens, ones -> indices 36..38)
            val hDigit = readDigitWithDynamicThreshold(36)
            val tDigit = readDigitWithDynamicThreshold(37)
            val oDigit = readDigitWithDynamicThreshold(38)

            val codedTotal: Int? = if (hDigit == null && tDigit == null && oDigit == null) {
                null
            } else {
                (hDigit ?: 0) * 100 + (tDigit ?: 0) * 10 + (oDigit ?: 0)
            }

            val hasTotalMismatch = codedTotal != null && codedTotal != calculatedTotal
            val missingBothIdentities = tcString.isBlank() && okulNoString.isBlank()
            val warnings = mutableListOf<String>()
            val reasonEnums = mutableListOf<ReviewReason>()
            if (multipleFormsDetected) {
                reasonEnums.add(ReviewReason.MULTIPLE_FORMS)
                warnings.add(ReviewReason.MULTIPLE_FORMS.label)
            }
            if (missingBothIdentities) {
                reasonEnums.add(ReviewReason.MISSING_IDENTITY)
                warnings.add(ReviewReason.MISSING_IDENTITY.label)
            }
            if (hasIdentityMultiMark || hasDoubleBubble) {
                reasonEnums.add(ReviewReason.MULTI_MARK)
                warnings.add(ReviewReason.MULTI_MARK.label)
            }
            if (hasMaxScoreExceeded) {
                reasonEnums.add(ReviewReason.MAX_SCORE_EXCEEDED)
                warnings.addAll(maxScoreWarnings)
            }
            if (hasTotalMismatch) {
                reasonEnums.add(ReviewReason.TOTAL_MISMATCH)
                warnings.add("Toplam uyuşmazlığı: Hesaplanan $calculatedTotal, Kodlanan $codedTotal")
            }

            if (debugColor != null) {
                val dotColor = if (hasTotalMismatch) orange else green
                listOf(36 to hDigit, 37 to tDigit, 38 to oDigit).forEach { (colIdx, digit) ->
                    digit?.let { d ->
                        Imgproc.circle(debugColor, Point(allColumns[colIdx].centerX, gridTop + (d + 0.5) * rowHeight), 8, dotColor, -1)
                    }
                }
            }

            val previewBitmap = debugColor?.let { mat ->
                val scaled = Mat()
                try {
                    Imgproc.resize(mat, scaled, Size(1050.0, 360.0))
                    Bitmap.createBitmap(scaled.cols(), scaled.rows(), Bitmap.Config.ARGB_8888).also { bmp ->
                        Utils.matToBitmap(scaled, bmp)
                    }
                } finally {
                    scaled.release()
                }
            }

            return OmrReadResult(
                tcKimlikNo = tcString,
                okulNo = okulNoString,
                isTcValid = isTcChecksumValid,
                scores = scores,
                totalScore = calculatedTotal,
                codedTotalScore = codedTotal,
                hasTotalMismatch = hasTotalMismatch,
                needsManualReview = hasTotalMismatch ||
                    missingBothIdentities ||
                    hasDoubleBubble ||
                    hasIdentityMultiMark ||
                    hasMaxScoreExceeded ||
                    multipleFormsDetected,
                multipleFormsDetected = multipleFormsDetected,
                reviewReasons = reasonEnums.distinct(),
                warnings = warnings,
                debugBitmap = previewBitmap
            )
        } finally {
            claheGray.release()
            blurredGray.release()
            normGray.release()
            invGray.release()
            cleanInv.release()
            binLinesFull.release()
            debugColor?.release()
        }
    }

    /**
     * Locates the 10-row matrix vertical bounds using horizontal projection on Otsu-binarized lines.
     * Top 22% of the table height is the header + handwriting row; bottom 78% is the 0..9 matrix.
     */
    private fun detectVerticalGridBounds(binLinesFull: Mat): Pair<Int, Int> {
        val rowSums = Mat()
        try {
            Core.reduce(binLinesFull, rowSums, 1, Core.REDUCE_SUM, CvType.CV_64F)
            val width = binLinesFull.cols().toDouble()
            val lineThreshold = width * 255.0 * 0.40

            var tableTop = FALLBACK_TABLE_TOP
            for (y in 40..220) {
                if (rowSums.get(y, 0)[0] >= lineThreshold) {
                    tableTop = y
                    break
                }
            }

            var tableBottom = FALLBACK_TABLE_BOTTOM
            for (y in (binLinesFull.rows() - 20) downTo (binLinesFull.rows() * 0.72).toInt()) {
                if (rowSums.get(y, 0)[0] >= lineThreshold) {
                    tableBottom = y
                    break
                }
            }

            val tableHeight = tableBottom - tableTop
            if (tableHeight !in 380..680) {
                val fallbackGridTop = (FALLBACK_TABLE_TOP + (FALLBACK_TABLE_BOTTOM - FALLBACK_TABLE_TOP) * HEADER_HEIGHT_RATIO).toInt()
                return fallbackGridTop to FALLBACK_TABLE_BOTTOM
            }

            // Expected header-bottom line at 22% of tableHeight
            val expectedGridTop = (tableTop + tableHeight * HEADER_HEIGHT_RATIO).toInt()
            var gridTop = expectedGridTop
            var maxHeaderSum = 0.0
            val searchStart = (expectedGridTop - 45).coerceAtLeast(tableTop + 40)
            val searchEnd = (expectedGridTop + 45).coerceAtMost(tableBottom - 220)
            for (y in searchStart..searchEnd) {
                val sum = rowSums.get(y, 0)[0]
                if (sum >= lineThreshold && sum > maxHeaderSum) {
                    maxHeaderSum = sum
                    gridTop = y + 2
                }
            }

            val span = (tableBottom - 2) - gridTop
            return if (span in 310..560) {
                gridTop to (tableBottom - 2)
            } else {
                expectedGridTop to (tableBottom - 2)
            }
        } finally {
            rowSums.release()
        }
    }

    /**
     * Detects the 13 blocks (TC [11], OKUL NO [5], Soru 1..10 [2 each], TOPLAM [3]) using
     * vertical white-gap and black-border projection inside the 10-row matrix band, locking each
     * block independently to its own left/right vertical black border lines to eliminate cumulative drift.
     */
    private fun detect13BlockBounds(
        binLinesBand: Mat,
        width: Int,
        isLiveCamera: Boolean = true
    ): List<Pair<Int, Int>> {
        val colSums = Mat()
        try {
            Core.reduce(binLinesBand, colSums, 0, Core.REDUCE_SUM, CvType.CV_64F)
            val height = binLinesBand.rows().toDouble()
            val whiteThreshold = height * 255.0 * if (isLiveCamera) 0.06 else 0.07
            val blackLineThreshold = height * 255.0 * if (isLiveCamera) 0.45 else 0.40

            // Find table outer left & right vertical black borders
            var tableLeft = FALLBACK_TABLE_LEFT_PX
            for (x in 25 until (width * 0.14).toInt()) {
                if (colSums.get(0, x)[0] >= blackLineThreshold) {
                    tableLeft = x + 2
                    break
                }
            }
            var tableRight = FALLBACK_TABLE_RIGHT_PX
            for (x in (width - 25) downTo (width * 0.86).toInt()) {
                if (colSums.get(0, x)[0] >= blackLineThreshold) {
                    tableRight = x - 2
                    break
                }
            }

            // Detect contiguous white vertical gaps (>= 2 px wide) between blocks
            val gaps = mutableListOf<IntRange>()
            var inGap = false
            var gapStart = 0
            val minX = tableLeft + 30
            val maxX = tableRight - 25
            for (x in minX until maxX) {
                val v = colSums.get(0, x)[0]
                if (v <= whiteThreshold) {
                    if (!inGap) {
                        inGap = true
                        gapStart = x
                    }
                } else if (inGap) {
                    inGap = false
                    if (x - gapStart >= 2) {
                        gaps.add(gapStart until x)
                    }
                }
            }
            if (inGap && maxX - gapStart >= 2) {
                gaps.add(gapStart until maxX)
            }

            // Snap each of the 12 inter-block gaps independently, then find each block's black border lines
            val span = (tableRight - tableLeft).toDouble()
            val expectedGaps = buildExpectedGapFractions()
            val matchedGapEdges = expectedGaps.map { frac ->
                val targetX = tableLeft + span * frac
                gaps.minByOrNull { abs((it.first + it.last) / 2.0 - targetX) }
                    ?.takeIf { abs((it.first + it.last) / 2.0 - targetX) < span * 0.03 }
            }

            val proportional = buildProportionalBlockBounds(tableLeft, tableRight)
            val bounds = mutableListOf<Pair<Int, Int>>()

            for (b in 0 until 13) {
                val leftGap = if (b == 0) null else matchedGapEdges[b - 1]
                val rightGap = if (b == 12) null else matchedGapEdges[b]

                var blockLeft = if (b == 0) tableLeft else (leftGap?.let { it.last + 1 } ?: proportional[b].first)
                var blockRight = if (b == 12) tableRight else (rightGap?.let { it.first - 1 } ?: proportional[b].second)

                // Refine blockLeft to the actual vertical black border line of this block
                if (b > 0 && leftGap != null) {
                    val searchEnd = (blockLeft + 12).coerceAtMost(width - 1)
                    for (x in blockLeft..searchEnd) {
                        if (colSums.get(0, x)[0] >= blackLineThreshold) {
                            blockLeft = x + 2
                            break
                        }
                    }
                }
                // Refine blockRight to the actual vertical black border line of this block
                if (b < 12 && rightGap != null) {
                    val searchStart = (blockRight - 12).coerceAtLeast(0)
                    for (x in blockRight downTo searchStart) {
                        if (colSums.get(0, x)[0] >= blackLineThreshold) {
                            blockRight = x - 2
                            break
                        }
                    }
                }

                val propWidth = proportional[b].second - proportional[b].first
                val invalidWidth = if (isLiveCamera) {
                    blockRight <= blockLeft + 10
                } else {
                    blockRight <= blockLeft + 10 || (blockRight - blockLeft) < (propWidth * 0.70)
                }
                if (invalidWidth) {
                    bounds.add(proportional[b])
                } else {
                    bounds.add(blockLeft to blockRight)
                }
            }
            return bounds
        } finally {
            colSums.release()
        }
    }

    private fun buildExpectedGapFractions(): List<Double> {
        // Total units: 39 columns + 12 gaps; each gap ~ 0.35 column width (TOPLAM gap ~ 0.65)
        val gapW = 0.35
        val totalGapW = 0.65
        val totalUnits = 39.0 + 11.0 * gapW + totalGapW
        val fracs = mutableListOf<Double>()
        var cursor = 0.0
        for (b in 0 until 12) {
            cursor += BLOCK_COL_WEIGHTS[b].toDouble()
            val gW = if (b == 11) totalGapW else gapW
            fracs.add((cursor + gW / 2.0) / totalUnits)
            cursor += gW
        }
        return fracs
    }

    private fun buildProportionalBlockBounds(tableLeft: Int, tableRight: Int): List<Pair<Int, Int>> {
        val gapW = 0.35
        val totalGapW = 0.65
        val totalUnits = 39.0 + 11.0 * gapW + totalGapW
        val span = (tableRight - tableLeft).toDouble()
        val unitPx = span / totalUnits

        val bounds = mutableListOf<Pair<Int, Int>>()
        var cursor = tableLeft.toDouble()

        for (b in 0 until 13) {
            if (b == 12) {
                bounds.add(cursor.toInt() to tableRight)
            } else {
                val w = BLOCK_COL_WEIGHTS[b].toDouble()
                val gW = if (b == 11) totalGapW else gapW
                val start = cursor.toInt()
                val end = (cursor + w * unitPx).toInt()
                bounds.add(start to end)
                cursor += (w + gW) * unitPx
            }
        }
        return bounds
    }

    /**
     * Reads Inner Core ROI (44% width x 46% height) across all 10 rows (0..9)
     * on the CLAHE+eroded inverted image, returning normalized darkness values [0.0..1.0].
     */
    private fun readColumnInnerCore(
        cleanInv: Mat,
        centerX: Double,
        colWidth: Double,
        gridTop: Int,
        rowHeight: Double
    ): DoubleArray {
        val coreW = colWidth * INNER_CORE_W_RATIO
        val coreH = rowHeight * INNER_CORE_H_RATIO
        val cols = cleanInv.cols()
        val rows = cleanInv.rows()
        val rw = coreW.toInt().coerceAtLeast(1).coerceAtMost(cols - 1)
        val rh = coreH.toInt().coerceAtLeast(1).coerceAtMost(rows - 1)
        val xShifts = intArrayOf(-2, 0, 2)
        val yShifts = intArrayOf(-2, 0, 2)

        val values = DoubleArray(10)
        for (row in 0 until 10) {
            val centerY = gridTop + (row + 0.5) * rowHeight
            var bestMean = 0.0
            for (dy in yShifts) {
                val ry = (centerY + dy - coreH / 2.0).toInt().coerceIn(0, (rows - rh).coerceAtLeast(0))
                for (dx in xShifts) {
                    val rx = (centerX + dx - coreW / 2.0).toInt().coerceIn(0, (cols - rw).coerceAtLeast(0))
                    val cellRoi = cleanInv.submat(Rect(rx, ry, rw, rh))
                    val meanVal = Core.mean(cellRoi).`val`[0] / 255.0
                    cellRoi.release()
                    if (meanVal > bestMean) bestMean = meanVal
                }
            }
            values[row] = bestMean.coerceIn(0.0, 1.0)
        }
        return values
    }

    /**
     * Port of OMRChecker/src/core.py get_global_threshold (First Large Gap heuristic).
     */
    private fun getGlobalThreshold(valuesOrig: List<Double>, looseness: Int = 1): Double {
        if (valuesOrig.size < 3) return GLOBAL_DEFAULT_THRESHOLD
        val sorted = valuesOrig.sorted()
        val ls = (looseness + 1) / 2
        val l = sorted.size - ls
        var max1 = MIN_JUMP
        var thr1 = GLOBAL_DEFAULT_THRESHOLD
        for (i in ls until l) {
            val jump = sorted[i + ls] - sorted[i - ls]
            if (jump > max1) {
                max1 = jump
                thr1 = sorted[i - ls] + jump / 2.0
            }
        }
        return thr1
    }

    /**
     * Port of OMRChecker/src/core.py get_local_threshold.
     */
    private fun getLocalThreshold(
        colVals: DoubleArray,
        globalThresh: Double,
        noOutliers: Boolean
    ): Double {
        val sorted = colVals.sorted()
        if (sorted.size < 3) {
            val spread = (sorted.maxOrNull() ?: 0.0) - (sorted.minOrNull() ?: 0.0)
            return if (spread < MIN_JUMP) globalThresh else sorted.average()
        }

        var max1 = MIN_JUMP
        var thr1 = 1.0
        for (i in 1 until sorted.size - 1) {
            val jump = sorted[i + 1] - sorted[i - 1]
            if (jump > max1) {
                max1 = jump
                thr1 = sorted[i - 1] + jump / 2.0
            }
        }

        val confidentJump = MIN_JUMP + CONFIDENT_SURPLUS
        if (max1 < confidentJump && noOutliers) {
            thr1 = globalThresh
        }
        return thr1
    }

    private fun stdDev(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
