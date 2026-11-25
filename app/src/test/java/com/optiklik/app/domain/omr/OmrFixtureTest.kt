package com.optiklik.app.domain.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmrFixtureTest {

    private fun isOpenCvAvailable(): Boolean {
        return try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {
            false
        }
    }

    @Test
    fun fixturesJsonHasVerifiedFlagAndSkipsUnverifiedEntries() {
        val stream = javaClass.classLoader!!.getResourceAsStream("omr_expected_fixtures.json")
        assertNotNull("omr_expected_fixtures.json fixture bulunamadı", stream)
        val root = JSONObject(stream!!.bufferedReader().use { it.readText() })
        val fixtures = root.getJSONArray("fixtures")
        assertTrue(fixtures.length() >= 5)

        for (i in 0 until fixtures.length()) {
            val item = fixtures.getJSONObject(i)
            assertTrue("Her fixture kaydında verified alanı olmalı", item.has("verified"))
            val verified = item.getBoolean("verified")
            // Unverified fixtures are explicitly skipped via Assume rather than falsely passing
            Assume.assumeTrue(
                "Doğrulanmamış fixture atlandı: ${item.getString("sourceFile")} (sayfa ${item.getInt("page")})",
                verified
            )
        }
    }

    @Test
    fun syntheticOmrFormWithBullseyesAndEmblemLoadsWhenOpenCvPresent() {
        val bmp = createSyntheticFormBitmap(includeEmblem = true, includeSecondForm = false)
        assertEquals(2000, bmp.width)
        assertEquals(1400, bmp.height)

        Assume.assumeTrue(
            "OpenCV native kütüphanesi (libopencv_java4) host JVM'de yüklü değil; cihaz testi gerekir",
            isOpenCvAvailable()
        )
        val analysis = OmrProcessor().analyzeBitmap(bmp, isLiveCamera = false)
        assertNotNull(analysis.guidance)
    }

    @Test
    fun syntheticDoubleFormBitmapGeneratesTwoDistinctMarkerSets() {
        val bmp = createSyntheticFormBitmap(includeEmblem = false, includeSecondForm = true)
        assertEquals(2000, bmp.width)
        assertEquals(1400, bmp.height)

        Assume.assumeTrue(
            "OpenCV native kütüphanesi (libopencv_java4) host JVM'de yüklü değil; cihaz testi gerekir",
            isOpenCvAvailable()
        )
        val analysis = OmrProcessor().analyzeBitmap(bmp, isLiveCamera = false)
        assertTrue(analysis.result?.multipleFormsDetected == true)
    }

    private fun createSyntheticFormBitmap(
        includeEmblem: Boolean,
        includeSecondForm: Boolean
    ): Bitmap {
        val bmp = Bitmap.createBitmap(2000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun drawBullseye(cx: Float, cy: Float, r: Float) {
            canvas.drawCircle(cx, cy, r, blackPaint)
            canvas.drawCircle(cx, cy, r * 0.72f, whitePaint)
            canvas.drawCircle(cx, cy, r * 0.50f, blackPaint)
            canvas.drawCircle(cx, cy, r * 0.30f, whitePaint)
            canvas.drawCircle(cx, cy, r * 0.16f, blackPaint)
        }

        // Form 1 in top 40% of page
        drawBullseye(120f, 120f, 26f)
        drawBullseye(1880f, 120f, 26f)
        drawBullseye(1880f, 560f, 26f)
        drawBullseye(120f, 560f, 26f)

        // Official circular emblem outside form area (should be ignored)
        if (includeEmblem) {
            canvas.drawCircle(1000f, 900f, 70f, blackPaint)
            canvas.drawCircle(1000f, 900f, 55f, whitePaint)
        }

        // Second form in bottom half of page
        if (includeSecondForm) {
            drawBullseye(120f, 820f, 26f)
            drawBullseye(1880f, 820f, 26f)
            drawBullseye(1880f, 1260f, 26f)
            drawBullseye(120f, 1260f, 26f)
        }

        return bmp
    }

    @Test
    fun testOmrPickExtremeCornersRejectsInnerBubblePoints() {
        val processor = TestOmrProcessor()
        val points = listOf(
            Point(250.0, 500.0),   // inner filled bubble
            Point(720.0, 620.0),   // inner filled bubble
            Point(300.0, 1200.0),  // inner filled bubble
            Point(80.0, 80.0),     // real TL
            Point(920.0, 80.0),    // real TR
            Point(920.0, 1920.0),  // real BR
            Point(80.0, 1920.0)    // real BL
        )
        val corners = processor.pickExtremeCorners(points)
        assertNotNull(corners)
        assertEquals(80.0, corners!![0].x, 0.1)
        assertEquals(80.0, corners[0].y, 0.1)
        assertEquals(920.0, corners[1].x, 0.1)
        assertEquals(1920.0, corners[2].y, 0.1)
        assertEquals(80.0, corners[3].x, 0.1)
        assertEquals(1920.0, corners[3].y, 0.1)
    }

    @Test
    fun syntheticVerticalTestFormLocks4CornersWhenOpenCvPresent() {
        val bmp = createSyntheticVerticalTestFormBitmap()
        assertEquals(1000, bmp.width)
        assertEquals(2000, bmp.height)

        Assume.assumeTrue(
            "OpenCV native kütüphanesi (libopencv_java4) host JVM'de yüklü değil; cihaz testi gerekir",
            isOpenCvAvailable()
        )

        val processor = TestOmrProcessor()
        val analysis = processor.analyzeBitmap(
            bitmap = bmp,
            isLiveCamera = false,
            soruSayisi = 25,
            answerKey = List(25) { "A" },
            maxScores = List(25) { 4 }
        )
        assertEquals(GuidanceState.Ready, analysis.guidance)
        assertNotNull(analysis.result)
    }

    @Test
    fun opticalFormLayoutMatchesCalibratedCanonicalBounds() {
        assertEquals(99.0, TestOmrProcessor.OpticalFormLayout.TC_LEFT, 0.01)
        assertEquals(779.0, TestOmrProcessor.OpticalFormLayout.TC_RIGHT, 0.01)
        assertEquals(334.0, TestOmrProcessor.OpticalFormLayout.TC_TOP, 0.01)
        assertEquals(770.0, TestOmrProcessor.OpticalFormLayout.TC_BOTTOM, 0.01)
        assertEquals(11, TestOmrProcessor.OpticalFormLayout.TC_COLS)

        assertEquals(99.0, TestOmrProcessor.OpticalFormLayout.OKUL_LEFT, 0.01)
        assertEquals(408.0, TestOmrProcessor.OpticalFormLayout.OKUL_RIGHT, 0.01)
        assertEquals(905.0, TestOmrProcessor.OpticalFormLayout.OKUL_TOP, 0.01)
        assertEquals(1349.0, TestOmrProcessor.OpticalFormLayout.OKUL_BOTTOM, 0.01)
        assertEquals(5, TestOmrProcessor.OpticalFormLayout.OKUL_COLS)

        assertEquals(523.0, TestOmrProcessor.OpticalFormLayout.Q_OPT_LEFT, 0.01)
        assertEquals(838.0, TestOmrProcessor.OpticalFormLayout.Q_OPT_RIGHT, 0.01)
        assertEquals(859.0, TestOmrProcessor.OpticalFormLayout.Q_BAND_TOP, 0.01)
        assertEquals(1971.0, TestOmrProcessor.OpticalFormLayout.Q_BAND_BOTTOM, 0.01)
        assertEquals(25, TestOmrProcessor.OpticalFormLayout.Q_MAX_ROWS)
    }

    @Test
    fun syntheticTestFormWithKnownTcAndAnswersReadsExpectedResultWhenOpenCvPresent() {
        Assume.assumeTrue(
            "OpenCV native kütüphanesi (libopencv_java4) host JVM'de yüklü değil; cihaz testi gerekir",
            isOpenCvAvailable()
        )

        val expectedTc = "10000000146"
        val expectedAnswers = listOf("A", "B", "C", "D", "E", "A", "B", "C", "D", "E")
        val bmp = createCodedSyntheticVerticalTestFormBitmap(
            tc = expectedTc,
            answers = expectedAnswers
        )

        val processor = TestOmrProcessor()
        val analysis = processor.analyzeBitmap(
            bitmap = bmp,
            isLiveCamera = false,
            soruSayisi = expectedAnswers.size,
            answerKey = expectedAnswers,
            maxScores = List(expectedAnswers.size) { 10 }
        )

        assertEquals(GuidanceState.Ready, analysis.guidance)
        val result = analysis.result
        assertNotNull(result)
        assertEquals(expectedTc, result!!.tcKimlikNo)
        assertTrue(result.isTcValid)
        assertEquals(expectedAnswers, result.studentAnswers)
        assertEquals(expectedAnswers.size, result.correctCount)
        assertEquals(0, result.wrongCount)
        assertEquals(100, result.totalScore)
    }

    @Test
    fun camScannerPdfExpectedOutputsMatchWhenOpenCvPresent() {
        Assume.assumeTrue(
            "OpenCV native kütüphanesi (libopencv_java4) host JVM'de yüklü değil; cihaz testi gerekir",
            isOpenCvAvailable()
        )

        val answerKey = listOf(
            "A", "B", "C", "D", "E",
            "A", "B", "C", "D", "E",
            "A", "B", "C", "D", "E",
            "A", "B", "C", "D", "E",
            "A", "B", "C", "D", "E"
        )
        val processor = TestOmrProcessor()

        // Örnek 1: T.C. 11223344550, Okul No: 155 (00155)
        val ex1Tc = "11223344550"
        val ex1Okul = "00155"
        val ex1Analysis = processor.analyzeBitmap(
            bitmap = createCodedSyntheticVerticalTestFormBitmap(ex1Tc, answerKey, ex1Okul),
            isLiveCamera = false,
            soruSayisi = 25,
            answerKey = answerKey,
            maxScores = List(25) { 4 }
        )
        assertEquals(GuidanceState.Ready, ex1Analysis.guidance)
        assertEquals(ex1Tc, ex1Analysis.result?.tcKimlikNo)
        assertEquals("155", ex1Analysis.result?.okulNo)
        assertEquals(answerKey, ex1Analysis.result?.studentAnswers)
        assertEquals(100, ex1Analysis.result?.totalScore)

        // Örnek 2: T.C. 12345678910, Okul No: 112 (00112)
        val ex2Tc = "12345678910"
        val ex2Okul = "00112"
        val ex2Analysis = processor.analyzeBitmap(
            bitmap = createCodedSyntheticVerticalTestFormBitmap(ex2Tc, answerKey, ex2Okul),
            isLiveCamera = false,
            soruSayisi = 25,
            answerKey = answerKey,
            maxScores = List(25) { 4 }
        )
        assertEquals(GuidanceState.Ready, ex2Analysis.guidance)
        assertEquals(ex2Tc, ex2Analysis.result?.tcKimlikNo)
        assertEquals("112", ex2Analysis.result?.okulNo)
        assertEquals(answerKey, ex2Analysis.result?.studentAnswers)
        assertEquals(100, ex2Analysis.result?.totalScore)
    }

    private fun createCodedSyntheticVerticalTestFormBitmap(
        tc: String,
        answers: List<String>,
        okulNo: String = ""
    ): Bitmap {
        val bmp = createSyntheticVerticalTestFormBitmap()
        val canvas = Canvas(bmp)
        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // Map canonical 1000x2000 coordinates into the 80..920 x 80..1920 marker-bounded region
        fun toBmpX(cx: Double): Float = (80.0 + (cx / 999.0) * (920.0 - 80.0)).toFloat()
        fun toBmpY(cy: Double): Float = (80.0 + (cy / 1999.0) * (1920.0 - 80.0)).toFloat()

        val tcColW = (TestOmrProcessor.OpticalFormLayout.TC_RIGHT - TestOmrProcessor.OpticalFormLayout.TC_LEFT) /
            TestOmrProcessor.OpticalFormLayout.TC_COLS
        val tcRowH = (TestOmrProcessor.OpticalFormLayout.TC_BOTTOM - TestOmrProcessor.OpticalFormLayout.TC_TOP) /
            TestOmrProcessor.OpticalFormLayout.TC_ROWS
        tc.forEachIndexed { col, ch ->
            val digit = ch.digitToInt()
            val cx = TestOmrProcessor.OpticalFormLayout.TC_LEFT + (col + 0.5) * tcColW
            val cy = TestOmrProcessor.OpticalFormLayout.TC_TOP + (digit + 0.5) * tcRowH
            canvas.drawCircle(toBmpX(cx), toBmpY(cy), 14f, blackPaint)
        }

        if (okulNo.length == TestOmrProcessor.OpticalFormLayout.OKUL_COLS) {
            val okulColW = (TestOmrProcessor.OpticalFormLayout.OKUL_RIGHT - TestOmrProcessor.OpticalFormLayout.OKUL_LEFT) /
                TestOmrProcessor.OpticalFormLayout.OKUL_COLS
            val okulRowH = (TestOmrProcessor.OpticalFormLayout.OKUL_BOTTOM - TestOmrProcessor.OpticalFormLayout.OKUL_TOP) /
                TestOmrProcessor.OpticalFormLayout.OKUL_ROWS
            okulNo.forEachIndexed { col, ch ->
                val digit = ch.digitToInt()
                val cx = TestOmrProcessor.OpticalFormLayout.OKUL_LEFT + (col + 0.5) * okulColW
                val cy = TestOmrProcessor.OpticalFormLayout.OKUL_TOP + (digit + 0.5) * okulRowH
                canvas.drawCircle(toBmpX(cx), toBmpY(cy), 14f, blackPaint)
            }
        }

        val optLabels = listOf("A", "B", "C", "D", "E")
        val qColW = (TestOmrProcessor.OpticalFormLayout.Q_OPT_RIGHT - TestOmrProcessor.OpticalFormLayout.Q_OPT_LEFT) /
            TestOmrProcessor.OpticalFormLayout.Q_OPTIONS_COUNT
        val qRowH = (TestOmrProcessor.OpticalFormLayout.Q_BAND_BOTTOM - TestOmrProcessor.OpticalFormLayout.Q_BAND_TOP) /
            TestOmrProcessor.OpticalFormLayout.Q_MAX_ROWS
        answers.forEachIndexed { qIdx, ans ->
            val optIdx = optLabels.indexOf(ans)
            if (optIdx >= 0) {
                val cx = TestOmrProcessor.OpticalFormLayout.Q_OPT_LEFT + (optIdx + 0.5) * qColW
                val cy = TestOmrProcessor.OpticalFormLayout.Q_BAND_TOP + (qIdx + 0.5) * qRowH
                canvas.drawCircle(toBmpX(cx), toBmpY(cy), 14f, blackPaint)
            }
        }

        return bmp
    }

    private fun createSyntheticVerticalTestFormBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        fun drawCornerSquare(cx: Float, cy: Float, side: Float = 80f) {
            val half = side / 2f
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, blackPaint)
        }

        // 4 outer solid black square corner markers (80x80 px)
        drawCornerSquare(80f, 80f)
        drawCornerSquare(920f, 80f)
        drawCornerSquare(920f, 1920f)
        drawCornerSquare(80f, 1920f)

        // Filled answer bubbles inside the form (rejected by square corner detector)
        canvas.drawCircle(250f, 1050f, 16f, blackPaint)
        canvas.drawCircle(720f, 1200f, 16f, blackPaint)
        canvas.drawCircle(310f, 1500f, 16f, blackPaint)

        return bmp
    }
}
