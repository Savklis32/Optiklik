package com.optiklik.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExifOrientationTest {

    private fun createCornerMarkedBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        for (x in 0 until 5) {
            for (y in 0 until 5) {
                bmp.setPixel(x, y, Color.RED)
            }
        }
        return bmp
    }

    @Test
    fun exifOrientation90And270SwapWidthAndHeightAndRotatePixels() {
        val horizontal = createCornerMarkedBitmap(40, 20)
        val rot90 = BatchScannerService.applyExifOrientation(horizontal, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(20, rot90.width)
        assertEquals(40, rot90.height)
        // Top-left (0..4, 0..4) rotates 90 deg CW to top-right
        assertEquals(Color.RED, rot90.getPixel(rot90.width - 2, 2))
        assertEquals(Color.BLUE, rot90.getPixel(2, 2))

        val horizontal2 = createCornerMarkedBitmap(40, 20)
        val rot270 = BatchScannerService.applyExifOrientation(horizontal2, ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(20, rot270.width)
        assertEquals(40, rot270.height)
        // Top-left (0..4, 0..4) rotates 270 deg CW to bottom-left
        assertEquals(Color.RED, rot270.getPixel(2, rot270.height - 2))
        assertEquals(Color.BLUE, rot270.getPixel(2, 2))
    }

    @Test
    fun exifOrientation180AndNormalPreserveDimensionsAndRotatePixels() {
        val vertical = createCornerMarkedBitmap(20, 40)
        val rot180 = BatchScannerService.applyExifOrientation(vertical, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(20, rot180.width)
        assertEquals(40, rot180.height)
        // Top-left (0..4, 0..4) rotates 180 deg to bottom-right
        assertEquals(Color.RED, rot180.getPixel(rot180.width - 2, rot180.height - 2))
        assertEquals(Color.BLUE, rot180.getPixel(2, 2))

        val normal = createCornerMarkedBitmap(20, 40)
        val unchanged = BatchScannerService.applyExifOrientation(normal, ExifInterface.ORIENTATION_NORMAL)
        assertSame(normal, unchanged)
        assertEquals(Color.RED, unchanged.getPixel(2, 2))
    }

    @Test
    fun exifInterfaceReadsOrientationTagFromJpegFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jpegFile = File(context.cacheDir, "test_exif_90.jpg")
        val bmp = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        FileOutputStream(jpegFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val exif = ExifInterface(jpegFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val readOrientation = ExifInterface(jpegFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, readOrientation)
        jpegFile.delete()
    }
}
