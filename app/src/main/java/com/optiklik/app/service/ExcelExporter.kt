package com.optiklik.app.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.optiklik.app.data.local.dao.ExamResultWithStudent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedExcelFile(
    val fileName: String,
    val shareUri: Uri,
    val savedLocationLabel: String
)

class ExcelExporter(private val context: Context) {

    suspend fun exportExamResults(
        examTitle: String,
        okulAdi: String = "",
        egitimOgretimYili: String = "",
        sinavDonemi: String,
        dersAdi: String = "",
        sinifSubesi: String,
        ogretmenLabel: String = "",
        soruSayisi: Int = 10,
        maxScores: List<Int> = List(10) { 10 },
        maskTc: Boolean = false,
        results: List<ExamResultWithStudent>
    ): ExportedExcelFile = withContext(Dispatchers.IO) {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Sınav Sonuçları")

        val metaFont = workbook.createFont().apply {
            bold = true
        }
        val metaStyle = workbook.createCellStyle().apply {
            setFont(metaFont)
        }

        // Top 3 rows: Corporate Exam Metadata
        val metaRow0 = sheet.createRow(0)
        metaRow0.createCell(0).apply {
            setCellValue("Okul Adı: ${okulAdi.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }
        metaRow0.createCell(4).apply {
            setCellValue("Eğitim Öğretim Yılı: ${egitimOgretimYili.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }

        val metaRow1 = sheet.createRow(1)
        metaRow1.createCell(0).apply {
            setCellValue("Sınav Dönemi: ${sinavDonemi.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }
        metaRow1.createCell(4).apply {
            setCellValue("Ders Adı: ${dersAdi.ifBlank { examTitle }.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }

        val metaRow2 = sheet.createRow(2)
        metaRow2.createCell(0).apply {
            setCellValue("Sınıf / Şube: ${sinifSubesi.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }
        metaRow2.createCell(4).apply {
            setCellValue("Ders Öğretmeni: ${ogretmenLabel.ifBlank { "-" }}")
            setCellStyle(metaStyle)
        }

        // Header style
        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            setFont(headerFont)
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val cellStyle = workbook.createCellStyle().apply {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val centerCellStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(cellStyle)
            alignment = HorizontalAlignment.CENTER
        }

        val decimalCellStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(centerCellStyle)
            dataFormat = workbook.createDataFormat().getFormat("0.00")
        }

        val summaryFont = workbook.createFont().apply { bold = true }
        val summaryStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(centerCellStyle)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFont(summaryFont)
        }

        val headers = listOf(
            "Sıra No",
            "T.C. Kimlik No",
            "Sınıfı",
            "Okul No",
            "Adı Soyadı",
            "Soru 1",
            "Soru 2",
            "Soru 3",
            "Soru 4",
            "Soru 5",
            "Soru 6",
            "Soru 7",
            "Soru 8",
            "Soru 9",
            "Soru 10",
            "Toplam Puan"
        )

        val headerRowIndex = 4
        val headerRow = sheet.createRow(headerRowIndex)
        headers.forEachIndexed { colIdx, title ->
            headerRow.createCell(colIdx).apply {
                setCellValue(title)
                setCellStyle(headerStyle)
            }
        }

        val firstDataRowExcel = headerRowIndex + 2 // 1-based Excel row index (6)
        results.forEachIndexed { index, item ->
            val row = sheet.createRow(headerRowIndex + 1 + index)
            val r = item.result
            val sinifText = r.sinifSubesi.ifBlank { sinifSubesi }.ifBlank { "-" }
            val okulNoText = item.schoolNo?.takeIf { it.isNotBlank() } ?: r.okulNo.ifBlank { "-" }
            val adSoyadText = item.fullName?.takeIf { it.isNotBlank() } ?: r.adSoyad.ifBlank { "Listede Yok" }

            val tcText = if (maskTc) maskTcKimlik(r.tcKimlikNo) else r.tcKimlikNo
            row.createCell(0).apply { setCellValue((index + 1).toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(1).apply { setCellValue(tcText); setCellStyle(centerCellStyle) }
            row.createCell(2).apply { setCellValue(sinifText); setCellStyle(centerCellStyle) }
            row.createCell(3).apply { setCellValue(okulNoText); setCellStyle(centerCellStyle) }
            row.createCell(4).apply { setCellValue(adSoyadText); setCellStyle(cellStyle) }
            row.createCell(5).apply { setCellValue(r.score1.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(6).apply { setCellValue(r.score2.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(7).apply { setCellValue(r.score3.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(8).apply { setCellValue(r.score4.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(9).apply { setCellValue(r.score5.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(10).apply { setCellValue(r.score6.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(11).apply { setCellValue(r.score7.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(12).apply { setCellValue(r.score8.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(13).apply { setCellValue(r.score9.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(14).apply { setCellValue(r.score10.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(15).apply { setCellValue(r.totalScore.toDouble()); setCellStyle(centerCellStyle) }
        }

        // Bottom row: Class Average (=AVERAGE(...))
        val avgRowIndex = headerRowIndex + 1 + results.size
        val lastDataRowExcel = firstDataRowExcel + results.size - 1
        val avgRow = sheet.createRow(avgRowIndex)
        avgRow.createCell(0).apply { setCellValue(""); setCellStyle(summaryStyle) }
        avgRow.createCell(1).apply { setCellValue(""); setCellStyle(summaryStyle) }
        avgRow.createCell(2).apply { setCellValue(""); setCellStyle(summaryStyle) }
        avgRow.createCell(3).apply { setCellValue(""); setCellStyle(summaryStyle) }
        avgRow.createCell(4).apply { setCellValue("SINIF ORTALAMASI"); setCellStyle(summaryStyle) }

        val colLetters = listOf("F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P")
        colLetters.forEachIndexed { i, colLetter ->
            val cell = avgRow.createCell(5 + i)
            if (results.isNotEmpty()) {
                cell.cellFormula = "AVERAGE(${colLetter}${firstDataRowExcel}:${colLetter}${lastDataRowExcel})"
            } else {
                cell.setCellValue(0.0)
            }
            cell.setCellStyle(summaryStyle)
        }

        val widths = listOf(10, 16, 12, 12, 26, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 14)
        widths.forEachIndexed { idx, w -> sheet.setColumnWidth(idx, w * 256) }

        // Sheet 2: Soru Analizi
        createQuestionAnalysisSheet(
            workbook = workbook,
            okulAdi = okulAdi,
            egitimOgretimYili = egitimOgretimYili,
            sinavDonemi = sinavDonemi,
            dersAdi = dersAdi.ifBlank { examTitle },
            sinifSubesi = sinifSubesi,
            ogretmenLabel = ogretmenLabel,
            soruSayisi = soruSayisi,
            maxScores = maxScores,
            results = results,
            metaStyle = metaStyle,
            headerStyle = headerStyle,
            centerCellStyle = centerCellStyle,
            decimalCellStyle = decimalCellStyle,
            summaryStyle = summaryStyle
        )

        val safeSinif = sinifSubesi.trim().replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ_-]"), "_")
        val safeTitle = dersAdi.ifBlank { examTitle }.trim().replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ_-]"), "_")
        val prefix = if (safeSinif.isNotBlank()) "${safeSinif}_${safeTitle}" else safeTitle
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = "${prefix}_Sonuclari_${timestamp}.xlsx"

        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val shareFile = File(exportDir, fileName)
            FileOutputStream(shareFile).use { fos ->
                workbook.write(fos)
            }
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            val locationLabel = saveToDownloads(fileName, shareFile)

            ExportedExcelFile(
                fileName = fileName,
                shareUri = shareUri,
                savedLocationLabel = locationLabel
            )
        } finally {
            workbook.close()
        }
    }

    private fun createQuestionAnalysisSheet(
        workbook: XSSFWorkbook,
        okulAdi: String,
        egitimOgretimYili: String,
        sinavDonemi: String,
        dersAdi: String,
        sinifSubesi: String,
        ogretmenLabel: String,
        soruSayisi: Int,
        maxScores: List<Int>,
        results: List<ExamResultWithStudent>,
        metaStyle: org.apache.poi.ss.usermodel.CellStyle,
        headerStyle: org.apache.poi.ss.usermodel.CellStyle,
        centerCellStyle: org.apache.poi.ss.usermodel.CellStyle,
        decimalCellStyle: org.apache.poi.ss.usermodel.CellStyle,
        summaryStyle: org.apache.poi.ss.usermodel.CellStyle
    ) {
        val analysisSheet = workbook.createSheet("Soru Analizi")

        analysisSheet.createRow(0).apply {
            createCell(0).apply { setCellValue("Okul Adı: ${okulAdi.ifBlank { "-" }}"); setCellStyle(metaStyle) }
            createCell(3).apply { setCellValue("Eğitim Öğretim Yılı: ${egitimOgretimYili.ifBlank { "-" }}"); setCellStyle(metaStyle) }
        }
        analysisSheet.createRow(1).apply {
            createCell(0).apply { setCellValue("Sınav Dönemi: ${sinavDonemi.ifBlank { "-" }}"); setCellStyle(metaStyle) }
            createCell(3).apply { setCellValue("Ders Adı: ${dersAdi.ifBlank { "-" }}"); setCellStyle(metaStyle) }
        }
        analysisSheet.createRow(2).apply {
            createCell(0).apply { setCellValue("Sınıf / Şube: ${sinifSubesi.ifBlank { "-" }}"); setCellStyle(metaStyle) }
            createCell(3).apply { setCellValue("Ders Öğretmeni: ${ogretmenLabel.ifBlank { "-" }}"); setCellStyle(metaStyle) }
        }

        val headers = listOf(
            "Soru",
            "Maksimum Puan",
            "Soru Ortalaması",
            "Başarı (%)",
            "En Yüksek Puan",
            "En Düşük Puan",
            "Sıfır Alan Öğrenci",
            "Katılan Öğrenci"
        )
        val headerRow = analysisSheet.createRow(4)
        headers.forEachIndexed { idx, title ->
            headerRow.createCell(idx).apply {
                setCellValue(title)
                setCellStyle(headerStyle)
            }
        }

        val count = results.size
        val activeCount = soruSayisi.coerceIn(1, 10)
        for (q in 0 until activeCount) {
            val scores = results.map { item ->
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
            }
            val row = analysisSheet.createRow(5 + q)
            val maxScore = maxScores.getOrElse(q) { 10 }
            val avg = if (scores.isNotEmpty()) scores.average() else 0.0
            val successPct = if (maxScore > 0) (avg / maxScore) * 100.0 else 0.0
            val max = scores.maxOrNull() ?: 0
            val min = scores.minOrNull() ?: 0
            val zeros = scores.count { it == 0 }

            row.createCell(0).apply { setCellValue("Soru ${q + 1}"); setCellStyle(centerCellStyle) }
            row.createCell(1).apply { setCellValue(maxScore.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(2).apply { setCellValue(avg); setCellStyle(decimalCellStyle) }
            row.createCell(3).apply { setCellValue(successPct); setCellStyle(decimalCellStyle) }
            row.createCell(4).apply { setCellValue(max.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(5).apply { setCellValue(min.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(6).apply { setCellValue(zeros.toDouble()); setCellStyle(centerCellStyle) }
            row.createCell(7).apply { setCellValue(count.toDouble()); setCellStyle(centerCellStyle) }
        }

        val totals = results.map { it.result.totalScore }
        val totalMax = (0 until activeCount).sumOf { maxScores.getOrElse(it) { 10 } }
        val totalAvg = if (totals.isNotEmpty()) totals.average() else 0.0
        val totalPct = if (totalMax > 0) (totalAvg / totalMax) * 100.0 else 0.0
        val summaryRow = analysisSheet.createRow(5 + activeCount)
        summaryRow.createCell(0).apply { setCellValue("GENEL TOPLAM"); setCellStyle(summaryStyle) }
        summaryRow.createCell(1).apply { setCellValue(totalMax.toDouble()); setCellStyle(summaryStyle) }
        summaryRow.createCell(2).apply { setCellValue(totalAvg); setCellStyle(summaryStyle) }
        summaryRow.createCell(3).apply { setCellValue(totalPct); setCellStyle(summaryStyle) }
        summaryRow.createCell(4).apply { setCellValue((totals.maxOrNull() ?: 0).toDouble()); setCellStyle(summaryStyle) }
        summaryRow.createCell(5).apply { setCellValue((totals.minOrNull() ?: 0).toDouble()); setCellStyle(summaryStyle) }
        summaryRow.createCell(6).apply { setCellValue("-"); setCellStyle(summaryStyle) }
        summaryRow.createCell(7).apply { setCellValue(count.toDouble()); setCellStyle(summaryStyle) }

        val analysisWidths = listOf(16, 16, 18, 14, 18, 16, 20, 18)
        analysisWidths.forEachIndexed { idx, w -> analysisSheet.setColumnWidth(idx, w * 256) }
    }

    private fun maskTcKimlik(tc: String): String {
        val clean = tc.trim()
        if (clean.length < 6) return clean
        return clean.take(3) + "******" + clean.takeLast(2)
    }

    private fun saveToDownloads(fileName: String, sourceFile: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }
                "İndirilenler/$fileName"
            } else {
                sourceFile.absolutePath
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val dest = File(downloadsDir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }
}
