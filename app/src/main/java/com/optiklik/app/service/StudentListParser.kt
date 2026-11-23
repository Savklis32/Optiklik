package com.optiklik.app.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import com.optiklik.app.data.repository.OptiklikRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.floor

data class StudentParseResult(
    val students: List<StudentDirectoryEntity>,
    val usedDefaultColumnOrder: Boolean = false,
    val infoMessage: String? = null
)

/**
 * Smart parser for e-Okul student lists (OOG01001R090_*.XLS, .XLSX, .PDF, .CSV).
 * Supports both e-Okul vertical key-value blocks (with merged cells) and horizontal table rows.
 */
class StudentListParser(private val context: Context) {

    companion object {
        private val TC_REGEX = Regex("\\b[1-9][0-9]{10}\\b")
        private val SCHOOL_NO_REGEX = Regex("\\b\\d{1,5}\\b")
        private val MULTI_WORD_NAME_REGEX = Regex(
            "([A-Za-zÇĞİÖŞÜçğıöşü]{2,}(?:\\s+[A-Za-zÇĞİÖŞÜçğıöşü]{2,})+)"
        )
        private val TR_ROW_REGEX = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TD_CELL_REGEX = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val CLASS_BRANCH_REGEX = Regex("(\\d{1,2})\\s*[./-]?\\s*(?:sınıf\\s*/?\\s*)?([A-Za-zÇĞİÖŞÜçğıöşü]{1,3})(?:\\s*şube(?:si)?)?", RegexOption.IGNORE_CASE)
        private val LABEL_KEYWORDS = listOf(
            "öğrenci numarası", "ogrenci numarasi", "okul no",
            "t.c. kimlik", "tc kimlik", "kimlik no",
            "adı soyadı", "adi soyadi", "ad soyad",
            "baba adı", "anne adı", "cinsiyeti", "doğum tarihi", "sınıfı/şubesi"
        )
    }

    private enum class FileFormat { XLS, XLSX, PDF, HTML, CSV }

    suspend fun parseUri(
        uri: Uri,
        okulAdi: String = "",
        defaultSinifSubesi: String = ""
    ): List<StudentDirectoryEntity> = parseUriWithReport(uri, okulAdi, defaultSinifSubesi).students

    suspend fun parseUriWithReport(
        uri: Uri,
        okulAdi: String = "",
        defaultSinifSubesi: String = ""
    ): StudentParseResult = withContext(Dispatchers.IO) {
        val cleanOkul = okulAdi.trim()
        val cleanClass = if (defaultSinifSubesi.isNotBlank()) {
            OptiklikRepository.normalizeClassBranch(defaultSinifSubesi)
        } else {
            ""
        }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext StudentParseResult(emptyList(), usedDefaultColumnOrder = true)

        val format = detectFormatByMagicBytes(bytes)
        var usedDefaultOrder = false
        val parsed = when (format) {
            FileFormat.PDF -> parsePdfBytes(bytes) { usedDefaultOrder = it }
            FileFormat.XLS -> parseExcelBytes(bytes, isLegacyHssf = true) { usedDefaultOrder = it }
            FileFormat.XLSX -> parseExcelBytes(bytes, isLegacyHssf = false) { usedDefaultOrder = it }
            FileFormat.HTML -> parseHtmlBytes(bytes) { usedDefaultOrder = it }
            FileFormat.CSV -> parseCsvBytes(bytes) { usedDefaultOrder = it }
        }

        val mapped = parsed.map { st ->
            st.copy(
                okulAdi = if (cleanOkul.isNotEmpty()) cleanOkul else st.okulAdi,
                sinifSubesi = st.sinifSubesi.ifBlank { cleanClass }
            )
        }
        val infoMsg = if (usedDefaultOrder && mapped.isNotEmpty()) {
            "Başlık satırı bulunamadı; varsayılan sütun sırası kullanıldı."
        } else {
            null
        }
        StudentParseResult(mapped, usedDefaultColumnOrder = usedDefaultOrder, infoMessage = infoMsg)
    }

    private fun detectFormatByMagicBytes(bytes: ByteArray): FileFormat {
        if (bytes.size >= 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            // D0 CF 11 E0 -> OLE2 Compound File (HSSF .xls)
            if (b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0) {
                return FileFormat.XLS
            }
            // 50 4B -> ZIP archive (XSSF .xlsx)
            if (b0 == 0x50 && b1 == 0x4B) {
                return FileFormat.XLSX
            }
            // 25 50 44 46 -> %PDF
            if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) {
                return FileFormat.PDF
            }
        }
        // Skip optional UTF-8 BOM and whitespace to check for '<' (e-Okul HTML/XML table)
        val preview = bytes.copyOfRange(0, minOf(bytes.size, 512))
            .toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
        if (preview.startsWith("<")) {
            return FileFormat.HTML
        }
        return FileFormat.CSV
    }

    private fun parseExcelBytes(
        bytes: ByteArray,
        isLegacyHssf: Boolean,
        onHeaderFallback: (Boolean) -> Unit
    ): List<StudentDirectoryEntity> {
        val students = LinkedHashMap<String, StudentDirectoryEntity>()
        val formatter = DataFormatter()
        var foundHeader = false

        ByteArrayInputStream(bytes).use { stream ->
            val workbook: Workbook = if (isLegacyHssf) {
                HSSFWorkbook(stream)
            } else {
                try {
                    XSSFWorkbook(stream)
                } catch (_: Exception) {
                    WorkbookFactory.create(ByteArrayInputStream(bytes))
                }
            }
            workbook.use { wb ->
                for (sIdx in 0 until wb.numberOfSheets) {
                    val sheet = wb.getSheetAt(sIdx) ?: continue
                    val rowsAsCells = mutableListOf<List<String>>()
                    for (row in sheet) {
                        val cells = mutableListOf<String>()
                        val lastCol = row.lastCellNum.toInt().coerceAtLeast(0)
                        for (c in 0 until lastCol) {
                            val text = formatCellClean(row.getCell(c), formatter)
                            cells.add(text)
                        }
                        if (cells.any { it.isNotBlank() }) {
                            rowsAsCells.add(cells)
                        }
                    }
                    if (extractFromRows(rowsAsCells, students)) {
                        foundHeader = true
                    }
                }
            }
        }
        onHeaderFallback(!foundHeader)
        return students.values.toList()
    }

    private fun parseHtmlBytes(
        bytes: ByteArray,
        onHeaderFallback: (Boolean) -> Unit
    ): List<StudentDirectoryEntity> {
        val students = LinkedHashMap<String, StudentDirectoryEntity>()
        val html = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()

        for (trMatch in TR_ROW_REGEX.findAll(html)) {
            val rowHtml = trMatch.groupValues[1]
            val cells = TD_CELL_REGEX.findAll(rowHtml).map { tdMatch ->
                decodeHtmlCell(tdMatch.groupValues[1])
            }.toList()
            if (cells.any { it.isNotBlank() }) {
                rows.add(cells)
            }
        }
        val foundHeader = extractFromRows(rows, students)
        onHeaderFallback(!foundHeader)
        return students.values.toList()
    }

    private fun decodeHtmlCell(raw: String): String {
        val noTags = HTML_TAG_REGEX.replace(raw, " ")
        return noTags
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parsePdfBytes(
        bytes: ByteArray,
        onHeaderFallback: (Boolean) -> Unit
    ): List<StudentDirectoryEntity> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val students = LinkedHashMap<String, StudentDirectoryEntity>()

        ByteArrayInputStream(bytes).use { stream ->
            PDDocument.load(stream).use { document ->
                val text = PDFTextStripper().getText(document)
                val rows = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { line ->
                        line.split(Regex("\\s{2,}|\\t+"))
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    }
                val foundHeader = extractFromRows(rows, students)
                onHeaderFallback(!foundHeader)
            }
        }
        return students.values.toList()
    }

    private fun parseCsvBytes(
        bytes: ByteArray,
        onHeaderFallback: (Boolean) -> Unit
    ): List<StudentDirectoryEntity> {
        val students = LinkedHashMap<String, StudentDirectoryEntity>()
        ByteArrayInputStream(bytes).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val rows = reader.lineSequence()
                    .map { it.trim().removePrefix("\uFEFF") }
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val delimiter = if (line.contains(';')) ';' else ','
                        line.split(delimiter).map { it.trim().trim('"') }
                    }
                    .toList()
                val foundHeader = extractFromRows(rows, students)
                onHeaderFallback(!foundHeader)
            }
        }
        return students.values.toList()
    }

    private fun extractFromRows(
        rows: List<List<String>>,
        out: LinkedHashMap<String, StudentDirectoryEntity>
    ): Boolean {
        var blockOkulNo: String? = null
        var blockTc: String? = null
        var blockName: String? = null
        var currentClassBranch = ""
        var headerTcCol: Int? = null
        var headerNoCol: Int? = null
        var headerNameCol: Int? = null
        var headerClassCol: Int? = null
        var foundHeader = false

        fun flushBlock() {
            val tc = blockTc
            if (tc != null && tc.length == 11) {
                out[tc] = StudentDirectoryEntity(
                    tcKimlikNo = tc,
                    sinifSubesi = currentClassBranch,
                    okulNo = blockOkulNo?.ifBlank { "-" } ?: "-",
                    adSoyad = blockName?.ifBlank { "Listede Yok" } ?: "Listede Yok"
                )
            }
            blockOkulNo = null
            blockTc = null
            blockName = null
        }

        val trLocale = Locale("tr", "TR")
        for (cells in rows) {
            val nonEmptyCells = cells.filter { it.isNotBlank() }
            if (nonEmptyCells.isEmpty()) continue
            val joined = nonEmptyCells.joinToString(" ")
            val lower = joined.lowercase(trLocale)

            if (lower.contains("sınıfı") || lower.contains("sinifi") || lower.contains("şubesi") || lower.contains("subesi")) {
                val detected = extractClassBranch(joined)
                if (detected.isNotBlank()) {
                    currentClassBranch = detected
                }
            }

            // Detect horizontal table header row (with >= 2 column headers)
            if (cells.size >= 2 && TC_REGEX.find(joined) == null) {
                var tcIdx: Int? = null
                var noIdx: Int? = null
                var nameIdx: Int? = null
                var clsIdx: Int? = null
                cells.forEachIndexed { idx, rawCell ->
                    val cl = rawCell.trim().lowercase(trLocale)
                    when {
                        tcIdx == null && (cl.contains("t.c.") || cl.contains("tc kimlik") || cl.contains("kimlik no")) -> tcIdx = idx
                        noIdx == null && (cl.contains("öğrenci no") || cl.contains("ogrenci no") || cl.contains("okul no") || cl == "no" || cl.contains("numara")) -> noIdx = idx
                        nameIdx == null && (cl.contains("adı soyadı") || cl.contains("adi soyadi") || cl.contains("ad soyad") || cl.contains("öğrenci adı")) -> nameIdx = idx
                        clsIdx == null && (cl.contains("sınıf") || cl.contains("sinif") || cl.contains("şube") || cl.contains("sube")) -> clsIdx = idx
                    }
                }
                val matchCount = listOfNotNull(tcIdx, noIdx, nameIdx, clsIdx).size
                if (matchCount >= 2) {
                    headerTcCol = tcIdx
                    headerNoCol = noIdx
                    headerNameCol = nameIdx
                    headerClassCol = clsIdx
                    foundHeader = true
                    continue
                }
            }

            // 1. Check for e-Okul vertical key-value block labels
            when {
                lower.contains("öğrenci numarası") || lower.contains("ogrenci numarasi") -> {
                    if (blockTc != null) flushBlock()
                    blockOkulNo = extractSchoolNoFromCells(nonEmptyCells)
                    continue
                }
                lower.contains("t.c. kimlik") || lower.contains("tc kimlik") -> {
                    val tc = TC_REGEX.find(joined)?.value
                    if (tc != null) {
                        if (blockTc != null && blockTc != tc) flushBlock()
                        blockTc = tc
                    }
                    continue
                }
                lower.contains("adı soyadı") || lower.contains("adi soyadi") -> {
                    val name = extractNameAfterLabel(nonEmptyCells)
                    if (!name.isNullOrBlank()) {
                        blockName = name
                        if (blockTc != null) flushBlock()
                    }
                    continue
                }
            }

            // 2. Horizontal table row: use header mapping if available, else heuristic
            val tcFromHeader = headerTcCol?.let { idx -> cells.getOrNull(idx) }?.let { TC_REGEX.find(it)?.value }
            val tcMatch = tcFromHeader ?: TC_REGEX.find(joined)?.value
            if (tcMatch != null) {
                val tc = tcMatch
                if (LABEL_KEYWORDS.any { lower.contains(it) }) continue

                var okulNo: String? = headerNoCol?.let { idx ->
                    cells.getOrNull(idx)?.trim()?.let { SCHOOL_NO_REGEX.find(it)?.value }
                }
                var adSoyad: String? = headerNameCol?.let { idx ->
                    cells.getOrNull(idx)?.trim()?.takeIf { it.length >= 2 && !isLikelyLabel(it) }
                }
                val rowClass: String = headerClassCol?.let { idx ->
                    cells.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() }?.let {
                        OptiklikRepository.normalizeClassBranch(it)
                    }
                } ?: currentClassBranch

                for (cell in nonEmptyCells) {
                    val clean = cell.trim()
                    if (clean.contains(tc)) continue

                    if (okulNo == null && Regex("^\\d{1,5}$").matches(clean)) {
                        okulNo = clean
                        continue
                    }

                    if (adSoyad == null && !isLikelyLabel(clean)) {
                        val nameMatch = MULTI_WORD_NAME_REGEX.find(clean)
                        if (nameMatch != null) {
                            adSoyad = nameMatch.value.trim()
                        }
                    }
                }

                // Fallback for single-string PDF line
                if (okulNo == null || adSoyad == null) {
                    val withoutTc = joined.replace(tc, " ").trim()
                    if (okulNo == null) {
                        okulNo = SCHOOL_NO_REGEX.find(withoutTc)?.value
                    }
                    if (adSoyad == null) {
                        val cleanedForName = withoutTc.replace(Regex("\\b\\d+\\b"), " ").trim()
                        adSoyad = MULTI_WORD_NAME_REGEX.find(cleanedForName)?.value?.trim()
                    }
                }

                out[tc] = StudentDirectoryEntity(
                    tcKimlikNo = tc,
                    sinifSubesi = rowClass,
                    okulNo = okulNo?.ifBlank { "-" } ?: "-",
                    adSoyad = adSoyad?.ifBlank { "Listede Yok" } ?: "Listede Yok"
                )
            }
        }

        flushBlock()
        return foundHeader
    }

    private fun extractSchoolNoFromCells(cells: List<String>): String? {
        for (cell in cells) {
            val lower = cell.lowercase(Locale("tr", "TR"))
            if (lower.contains("öğrenci") || lower.contains("ogrenci") || lower.contains("numara")) {
                val afterColon = cell.substringAfter(':', "").trim()
                if (afterColon.isNotEmpty()) {
                    SCHOOL_NO_REGEX.find(afterColon)?.let { return it.value }
                }
                continue
            }
            SCHOOL_NO_REGEX.find(cell.trim())?.let { return it.value }
        }
        return null
    }

    private fun extractNameAfterLabel(cells: List<String>): String? {
        for (cell in cells) {
            val lower = cell.lowercase(Locale("tr", "TR"))
            if (lower.contains("adı soyadı") || lower.contains("adi soyadi")) {
                val afterColon = cell.substringAfter(':', "").trim()
                if (afterColon.length >= 3 && !isLikelyLabel(afterColon)) {
                    return afterColon
                }
                continue
            }
            val clean = cell.trim()
            if (clean.length >= 3 && !isLikelyLabel(clean) && clean.any { it.isLetter() }) {
                return clean
            }
        }
        return null
    }

    private fun extractClassBranch(text: String): String {
        val afterColon = text.substringAfter(':', text)
        val m = CLASS_BRANCH_REGEX.find(afterColon) ?: return ""
        val grade = m.groupValues[1]
        val branch = m.groupValues[2]
        return OptiklikRepository.normalizeClassBranch("$grade-$branch")
    }

    private fun isLikelyLabel(text: String): Boolean {
        val lower = text.lowercase(Locale("tr", "TR"))
        return LABEL_KEYWORDS.any { lower.contains(it) }
    }

    private fun formatCellClean(cell: Cell?, formatter: DataFormatter): String {
        if (cell == null) return ""
        return try {
            if (cell.cellType == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
                val d = cell.numericCellValue
                if (d == floor(d) && d <= Long.MAX_VALUE.toDouble()) {
                    d.toLong().toString()
                } else {
                    formatter.formatCellValue(cell).trim()
                }
            } else {
                formatter.formatCellValue(cell).trim()
            }
        } catch (_: Exception) {
            formatter.formatCellValue(cell).trim()
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var result = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) result = cursor.getString(idx) ?: ""
            }
        }
        return result
    }
}
