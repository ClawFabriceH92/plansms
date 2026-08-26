package com.fabrice.plansms.data

import android.content.Context
import com.fabrice.plansms.util.AppLogger
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envoie un fichier audio à un serveur de transcription compatible OpenAI
 * (POST multipart sur /v1/audio/transcriptions) et retourne le texte.
 */
object Transcriber {

    data class Result(val ok: Boolean, val text: String, val error: String = "")

    private const val BOUNDARY = "----PlanSMSBoundary7f3a91"
    private const val LINE = "\r\n"

    fun transcribe(context: Context, file: File): Result {
        val base = TranscriptionPrefs.serverUrl(context)
        if (base.isBlank()) return Result(false, "", "Aucun serveur de transcription configuré")
        if (!file.exists()) return Result(false, "", "Fichier introuvable")

        val endpoint = if (base.contains("/audio/transcriptions")) base
        else "$base/v1/audio/transcriptions"

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 300_000          // la transcription peut être longue
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                val key = TranscriptionPrefs.apiKey(context)
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }

            DataOutputStream(connection.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$BOUNDARY$LINE")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"$LINE$LINE")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes(LINE)
                }
                field("model", TranscriptionPrefs.model(context))
                field("language", TranscriptionPrefs.language(context))
                field("response_format", "json")

                out.writeBytes("--$BOUNDARY$LINE")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$LINE"
                )
                out.writeBytes("Content-Type: application/octet-stream$LINE$LINE")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes(LINE)
                out.writeBytes("--$BOUNDARY--$LINE")
            }

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return Result(false, "", "HTTP $code — ${error.take(150)}")
            }

            val text = try {
                JSONObject(body).optString("text").trim()
            } catch (_: Exception) {
                body.trim()      // certains serveurs renvoient du texte brut
            }
            if (text.isBlank()) {
                Result(false, "", "Réponse vide du serveur")
            } else {
                AppLogger.i("Transcriber", "Transcription reçue (${text.length} caractères)")
                Result(true, text)
            }
        } catch (e: Exception) {
            AppLogger.e("Transcriber", "Transcription impossible", e)
            Result(false, "", e.message ?: "Erreur réseau")
        } finally {
            connection?.disconnect()
        }
    }
}
