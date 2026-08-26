package com.fabrice.plansms.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.fabrice.plansms.recorder.RecordingService
import com.fabrice.plansms.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Import d'un fichier audio partagé depuis une autre app — notamment un message
 * vocal partagé depuis l'application Téléphone, que PlanSMS ne peut pas lire
 * directement dans le fournisseur système.
 */
object AudioImporter {

    /** Copie le fichier partagé dans les enregistrements. Retourne le libellé, ou null. */
    fun importFrom(context: Context, uri: Uri): String? {
        return try {
            val original = displayName(context, uri)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
            val extension = original.substringAfterLast('.', "m4a").take(4).ifBlank { "m4a" }
            val target = File(RecordingService.recordingsDir(context), "Import-$stamp.$extension")

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!target.exists() || target.length() < 512) {
                target.delete()
                return null
            }

            val label = "Importé — " + original.substringBeforeLast('.').take(40)
            val recording = VoiceRecording(
                filePath = target.absolutePath,
                label = label,
                source = "IMPORT",
                durationMs = durationOf(target),
                sizeBytes = target.length()
            )
            kotlinx.coroutines.runBlocking {
                AppDatabase.get(context).voiceRecordingDao().insert(recording)
            }
            AppLogger.i("AudioImporter", "Audio importé : ${target.name}")
            label
        } catch (e: Exception) {
            AppLogger.e("AudioImporter", "Import impossible", e)
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && it.moveToFirst()) {
                    val name = it.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "audio"
    }

    private fun durationOf(file: File): Long = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        retriever.release()
        value?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }
}
