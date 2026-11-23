package com.optiklik.app.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudentListParserTest {

    @Test
    fun parsesSyntheticHssfXlsByMagicBytesEvenWithUnknownExtension() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File(context.cacheDir, "eokul_list_legacy.dat")

        HSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Ogrenciler")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("Okul No")
            header.createCell(1).setCellValue("T.C. Kimlik No")
            header.createCell(2).setCellValue("Adı Soyadı")

            val row0 = sheet.createRow(1)
            row0.createCell(0).setCellValue("104")
            row0.createCell(1).setCellValue("10000000146")
            row0.createCell(2).setCellValue("AYŞE YILMAZ")

            FileOutputStream(tempFile).use { fos -> wb.write(fos) }
        }

        val parser = StudentListParser(context)
        val result = parser.parseUriWithReport(
            uri = Uri.fromFile(tempFile),
            okulAdi = "Atatürk Lisesi",
            defaultSinifSubesi = "9a"
        )

        assertFalse(result.usedDefaultColumnOrder)
        assertEquals(1, result.students.size)
        assertEquals("Atatürk Lisesi", result.students[0].okulAdi)
        assertEquals("9-A", result.students[0].sinifSubesi)
        assertEquals("104", result.students[0].okulNo)
        assertEquals("10000000146", result.students[0].tcKimlikNo)
        assertEquals("AYŞE YILMAZ", result.students[0].adSoyad)

        tempFile.delete()
    }

    @Test
    fun parsesSyntheticXlsxByMagicBytesWithDynamicHeaders() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File(context.cacheDir, "eokul_list_modern.XLSX")

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Liste")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("Adı Soyadı")
            header.createCell(1).setCellValue("Sınıf / Şube")
            header.createCell(2).setCellValue("T.C. Kimlik No")
            header.createCell(3).setCellValue("Öğrenci No")

            val row0 = sheet.createRow(1)
            row0.createCell(0).setCellValue("İBRAHİM ŞENOL")
            row0.createCell(1).setCellValue("10/b")
            row0.createCell(2).setCellValue("10000000214")
            row0.createCell(3).setCellValue("215")

            FileOutputStream(tempFile).use { fos -> wb.write(fos) }
        }

        val parser = StudentListParser(context)
        val result = parser.parseUriWithReport(
            uri = Uri.fromFile(tempFile),
            okulAdi = "Cumhuriyet Lisesi",
            defaultSinifSubesi = "9-A"
        )

        assertFalse(result.usedDefaultColumnOrder)
        assertEquals(1, result.students.size)
        assertEquals("İBRAHİM ŞENOL", result.students[0].adSoyad)
        assertEquals("10-B", result.students[0].sinifSubesi)
        assertEquals("215", result.students[0].okulNo)
        assertEquals("10000000214", result.students[0].tcKimlikNo)

        tempFile.delete()
    }

    @Test
    fun parsesEokulHtmlTableByMagicBytes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File(context.cacheDir, "OOG01001R090_HTML.XLS")
        tempFile.writeText(
            """
            <html><body>
            <table>
              <tr><th>Öğrenci No</th><th>T.C. Kimlik No</th><th>Adı Soyadı</th><th>Sınıf</th></tr>
              <tr><td>301</td><td>10000000146</td><td>ZEYNEP&nbsp;KARA</td><td>11-C</td></tr>
            </table>
            </body></html>
            """.trimIndent(),
            Charsets.UTF_8
        )

        val parser = StudentListParser(context)
        val result = parser.parseUriWithReport(
            uri = Uri.fromFile(tempFile),
            okulAdi = "Fen Lisesi",
            defaultSinifSubesi = ""
        )

        assertFalse(result.usedDefaultColumnOrder)
        assertEquals(1, result.students.size)
        assertEquals("301", result.students[0].okulNo)
        assertEquals("10000000146", result.students[0].tcKimlikNo)
        assertEquals("ZEYNEP KARA", result.students[0].adSoyad)
        assertEquals("11-C", result.students[0].sinifSubesi)

        tempFile.delete()
    }

    @Test
    fun headerlessCsvFallsBackToDefaultColumnOrderWithInfoMessage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = File(context.cacheDir, "headerless_list.csv")
        tempFile.writeText(
            "105;10000000146;MEHMET DEMİR;9-A\n",
            Charsets.UTF_8
        )

        val parser = StudentListParser(context)
        val result = parser.parseUriWithReport(
            uri = Uri.fromFile(tempFile),
            okulAdi = "Anadolu Lisesi",
            defaultSinifSubesi = "9-A"
        )

        assertTrue(result.usedDefaultColumnOrder)
        assertNotNull(result.infoMessage)
        assertEquals(1, result.students.size)
        assertEquals("105", result.students[0].okulNo)
        assertEquals("10000000146", result.students[0].tcKimlikNo)
        assertEquals("MEHMET DEMİR", result.students[0].adSoyad)

        tempFile.delete()
    }
}
