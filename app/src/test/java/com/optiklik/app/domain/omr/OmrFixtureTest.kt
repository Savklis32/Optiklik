package com.optiklik.app.domain.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
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
}
