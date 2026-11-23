package com.optiklik.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.optiklik.app.OptiklikApp
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.local.entity.ReviewReason
import com.optiklik.app.data.repository.MatchStatus
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.domain.omr.OmrProcessor
import com.optiklik.app.domain.omr.OmrReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream

data class BatchPageReport(
    val label: String,
    val success: Boolean,
    val message: String,
    val readResult: OmrReadResult? = null,
    val savedResultId: Long? = null,
    val needsManualReview: Boolean = false,
    val debugImagePath: String? = null
)

data class BatchScanProgress(
    val processedCount: Int,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val reports: List<BatchPageReport>,
    val isFinished: Boolean = false
)

class BatchScannerService(
    private val context: Context,
    private val repository: OptiklikRepository,
    private val omrProcessor: OmrProcessor = OmrProcessor()
) {

    fun scanUris(examSessionId: Long, uris: List<Uri>): Flow<BatchScanProgress> = flow {
        val workItems = mutableListOf<Pair<String, suspend () -> Bitmap?>>()
        var totalEstimated = 0

        // Expand URIs (PDF pages vs single image)
        for (uri in uris) {
            val mime = context.contentResolver.getType(uri) ?: ""
            val name = getDisplayName(uri) ?: "Dosya"
            val isPdf = mime.contains("pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)

            if (isPdf) {
                val pageCount = getPdfPageCount(uri)
                if (pageCount > 0) {
                    totalEstimated += pageCount
                    for (pageIndex in 0 until pageCount) {
                        workItems.add("$name (Sayfa ${pageIndex + 1})" to {
                            renderPdfPage(uri, pageIndex)
                        })
                    }
                } else {
                    totalEstimated += 1
                    workItems.add(name to { null })
                }
            } else {
                totalEstimated += 1
                workItems.add(name to { decodeImageBitmap(uri) })
            }
        }

        val session = repository.getSessionById(examSessionId)
        val soruSayisi = session?.soruSayisi ?: 10
        val maxScores = session?.parsedMaxScores() ?: List(10) { 10 }
        // Load student directory once for the entire batch (avoids per-page full table scan)
        val preloadedDirectory = repository.getAllDirectoryStudentsOnce()
        val hasDirectory = preloadedDirectory.isNotEmpty()
        val debugEnabled = (context.applicationContext as? OptiklikApp)
            ?.teacherProfile?.profile?.value?.debugModeEnabled ?: false

        val reports = mutableListOf<BatchPageReport>()
        var successCount = 0
        var failureCount = 0

        emit(BatchScanProgress(0, totalEstimated, 0, 0, emptyList(), isFinished = false))

        workItems.forEachIndexed { index, (label, bitmapLoader) ->
            val bitmap = try {
                bitmapLoader()
            } catch (_: Exception) {
                null
            }

            if (bitmap == null) {
                failureCount++
                reports.add(BatchPageReport(label, false, "Görüntü açılamadı veya okunamadı."))
            } else {
                try {
                    val analysis = omrProcessor.analyzeBitmap(
                        bitmap,
                        isLiveCamera = false,
                        includeDebugBitmap = debugEnabled,
                        soruSayisi = soruSayisi,
                        maxScores = maxScores
                    )
                    val rawRead = analysis.result
                    val debugPath = if (debugEnabled && rawRead?.debugBitmap != null) {
                        saveDebugBitmap(rawRead.debugBitmap, index)
                    } else null
                    val read = rawRead?.copy(debugImagePath = debugPath)

                    if (read == null) {
                        failureCount++
                        reports.add(BatchPageReport(label, false, analysis.guidance.message))
                    } else if (read.multipleFormsDetected) {
                        failureCount++
                        reports.add(
                            BatchPageReport(
                                label = label,
                                success = false,
                                message = "Sayfa başına tek form yazdırın",
                                readResult = read,
                                needsManualReview = true,
                                debugImagePath = debugPath
                            )
                        )
                    } else if (read.tcKimlikNo.isBlank() && read.okulNo.isBlank()) {
                        failureCount++
                        reports.add(
                            BatchPageReport(
                                label = label,
                                success = false,
                                message = "T.C. Kimlik No veya Okul No kodlanmamış!",
                                readResult = read,
                                needsManualReview = true,
                                debugImagePath = debugPath
                            )
                        )
                    } else {
                        val resolved = repository.resolveStudent(
                            session = session,
                            readTc = read.tcKimlikNo,
                            readOkulNo = read.okulNo,
                            preloadedDirectory = preloadedDirectory
                        )
                        if (hasDirectory && !resolved.isMatchedInDirectory) {
                            failureCount++
                            val tcLabel = read.tcKimlikNo.ifBlank { "-" }
                            val okulLabel = read.okulNo.ifBlank { "-" }
                            val reason = when (resolved.matchStatus) {
                                MatchStatus.AMBIGUOUS ->
                                    resolved.conflictWarning ?: "Belirsiz öğrenci eşleşmesi (TC: $tcLabel / Okul No: $okulLabel)"
                                else ->
                                    "Listede bulunamadı (TC: $tcLabel / Okul No: $okulLabel)"
                            }
                            reports.add(
                                BatchPageReport(
                                    label = label,
                                    success = false,
                                    message = reason,
                                    readResult = read,
                                    needsManualReview = true,
                                    debugImagePath = debugPath
                                )
                            )
                        } else {
                            // Duplicate check by T.C. OR Okul No within same session
                            val existing = repository.findExistingResult(
                                examSessionId,
                                resolved.tcKimlikNo,
                                resolved.okulNo
                            )

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
                                id = existing?.id ?: 0L,
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
                            val savedId = repository.saveResult(entity)
                            successCount++
                            val statusText = when {
                                needsReview -> {
                                    val warnSummary = allWarnings.firstOrNull() ?: "Kontrol gerekli"
                                    "Kontrol gerekli ($warnSummary) • ${resolved.adSoyad} (${resolved.okulNo}) • Toplam: ${read.totalScore}"
                                }
                                else ->
                                    "Başarılı • ${resolved.adSoyad} (${resolved.okulNo}) • Toplam: ${read.totalScore}"
                            }
                            reports.add(
                                BatchPageReport(
                                    label = label,
                                    success = true,
                                    message = statusText,
                                    readResult = read,
                                    savedResultId = if (existing != null && existing.id > 0L) existing.id else savedId,
                                    needsManualReview = needsReview,
                                    debugImagePath = debugPath
                                )
                            )
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            emit(
                BatchScanProgress(
                    processedCount = index + 1,
                    totalCount = totalEstimated,
                    successCount = successCount,
                    failureCount = failureCount,
                    reports = reports.toList(),
                    isFinished = index + 1 == totalEstimated
                )
            )
        }

        if (workItems.isEmpty()) {
            emit(BatchScanProgress(0, 0, 0, 0, emptyList(), isFinished = true))
        }
    }.flowOn(Dispatchers.IO)

    private fun getPdfPageCount(uri: Uri): Int {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun renderPdfPage(uri: Uri, pageIndex: Int): Bitmap? {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex >= renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    // Render at 2480px width (300 DPI A4 standard) for crisp OMR grid recognition
                    val targetWidth = 2480
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    private fun decodeImageBitmap(uri: Uri): Bitmap? {
        val orientation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
            try {
                ExifInterface(exifStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 2400) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return applyExifOrientation(decoded, orientation)
    }

    private fun saveDebugBitmap(bitmap: Bitmap, index: Int): String? {
        return try {
            val dir = File(context.cacheDir, "omr_debug").apply { mkdirs() }
            val file = File(dir, "debug_${System.currentTimeMillis()}_$index.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            return rotated
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
