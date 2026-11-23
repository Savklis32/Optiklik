package com.optiklik.app.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Exports and shares bundled OMR form templates from assets/formlar/.
 */
object FormTemplateExporter {

    private const val ASSETS_DIR = "formlar"
    private const val EXTENSION_ASSETS_DIR = "eklenti"
    const val OYSS_EXTENSION_FILE_NAME = "E-OKUL_OYSS_NOT_AKTARICI.rar"
    private const val OYSS_EXTENSION_MIME = "application/vnd.rar"

    suspend fun saveToDownloads(
        context: Context,
        assetFileName: String,
        mimeType: String
    ): Result<String> = saveAssetToDownloads(
        context = context,
        assetPath = "$ASSETS_DIR/$assetFileName",
        outputFileName = assetFileName,
        mimeType = mimeType
    )

    suspend fun exportOyssExtension(context: Context): Result<String> = saveAssetToDownloads(
        context = context,
        assetPath = "$EXTENSION_ASSETS_DIR/$OYSS_EXTENSION_FILE_NAME",
        outputFileName = OYSS_EXTENSION_FILE_NAME,
        mimeType = OYSS_EXTENSION_MIME
    )

    private suspend fun saveAssetToDownloads(
        context: Context,
        assetPath: String,
        outputFileName: String,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outputFileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("İndirilenler klasörüne dosya oluşturulamadı")
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        context.assets.open(assetPath).use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Dosya çıkış akışı açılamadı")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(itemUri, null, null)
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, outputFileName)
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { out ->
                        input.copyTo(out)
                    }
                }
            }
            outputFileName
        }
    }

    suspend fun getShareUri(
        context: Context,
        assetFileName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val shareFile = File(exportDir, assetFileName)
            context.assets.open("$ASSETS_DIR/$assetFileName").use { input ->
                FileOutputStream(shareFile).use { out ->
                    input.copyTo(out)
                }
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )
        }
    }

    suspend fun createShareIntent(
        context: Context,
        assetFileName: String,
        mimeType: String,
        chooserTitle: String
    ): Result<Intent> = getShareUri(context, assetFileName).map { uri ->
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, assetFileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(sendIntent, chooserTitle)
    }
}
