package com.fabrice.plansms.data

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.fabrice.plansms.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcription 100 % SUR LE TÉLÉPHONE, sans réseau.
 *
 * Android ne sait transcrire que du direct… sauf depuis Android 12, où l'on peut
 * fournir au moteur vocal un flux audio via un tube (EXTRA_AUDIO_SOURCE) au lieu
 * du micro. On décode donc le fichier en PCM 16 kHz mono et on l'y injecte.
 * Le moteur hors ligne d'Android fait le reste : rien ne sort de l'appareil.
 */
object OnDeviceTranscriber {

    private const val TARGET_RATE = 16_000

    /** Android 13+ et moteur de reconnaissance hors ligne présent. */
    fun isSupported(context: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (_: Exception) {
        false
    }

    fun unsupportedReason(context: Context): String = when {
        Build.VERSION.SDK_INT < 33 ->
            "Android 13 minimum requis pour transcrire un fichier hors ligne (téléphone en Android ${Build.VERSION.RELEASE})."
        else ->
            "Aucun moteur de reconnaissance vocale hors ligne sur ce téléphone. " +
                "Installe la langue française : Paramètres → Système → Langues et saisie → " +
                "Saisie vocale Google → Reconnaissance vocale hors connexion → Français."
    }

    /**
     * Lance la transcription. [onDone] est rappelé sur le thread principal.
     * À APPELER DEPUIS LE THREAD PRINCIPAL (contrainte de SpeechRecognizer).
     */
    fun transcribe(
        context: Context,
        file: File,
        language: String = "fr-FR",
        onDone: (ok: Boolean, text: String, error: String) -> Unit
    ) {
        if (!isSupported(context)) {
            onDone(false, "", unsupportedReason(context))
            return
        }
        if (!file.exists()) {
            onDone(false, "", "Fichier introuvable")
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            onDone(false, "", unsupportedReason(context))
            return
        }
        startOnDevice(context, file, language, onDone)
    }

    /** Partie qui touche aux API de reconnaissance hors ligne (Android 13+). */
    @RequiresApi(33)
    private fun startOnDevice(
        context: Context,
        file: File,
        language: String,
        onDone: (ok: Boolean, text: String, error: String) -> Unit
    ) {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            onDone(false, "", "Tube audio impossible : ${e.message}")
            return
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val collected = StringBuilder()
        var finished = false

        fun finish(ok: Boolean, error: String = "") {
            if (finished) return
            finished = true
            try { recognizer.destroy() } catch (_: Exception) {}
            try { readSide.close() } catch (_: Exception) {}
            val text = collected.toString().trim()
            if (ok && text.isNotEmpty()) onDone(true, text, "")
            else onDone(false, "", error.ifBlank { "Aucune parole reconnue dans cet enregistrement." })
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { collected.append(it).append(' ') }
            }

            override fun onSegmentResults(segmentResults: Bundle) = onResults(segmentResults)

            override fun onEndOfSegmentedSession() = finish(true)

            override fun onError(error: Int) {
                // Une coupure en fin de flux est normale si du texte a déjà été reconnu
                if (collected.isNotBlank()) finish(true) else finish(false, errorLabel(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Le moteur lit le tube au lieu du micro
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // Sans session segmentée, la reconnaissance s'arrête au premier silence
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        recognizer.startListening(intent)

        // Décodage et injection du son dans le tube, en tâche de fond
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    decodeToPcm(file, out)
                }
                AppLogger.i("OnDeviceTranscriber", "Audio injecté : ${file.name}")
            } catch (e: Exception) {
                AppLogger.e("OnDeviceTranscriber", "Décodage impossible", e)
            }
        }.start()
    }

    @RequiresApi(33)
    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Aucune parole reconnue dans cet enregistrement."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune parole détectée."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Français hors ligne non installé : Paramètres → Système → Langues et saisie → " +
                "Saisie vocale Google → Reconnaissance vocale hors connexion → Français."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission micro refusée."
        else -> "Échec de la reconnaissance (code $code)."
    }

    /** Décode n'importe quel format audio lisible par Android en PCM 16 bits, 16 kHz, mono. */
    private fun decodeToPcm(file: File, output: OutputStream) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            var sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var resampler = Resampler(sourceRate, TARGET_RATE, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = codec.outputFormat
                        sourceRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        resampler = Resampler(sourceRate, TARGET_RATE, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* rien à lire pour l'instant */ }
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                val chunk = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(chunk, 0, info.size)
                                output.write(resampler.process(chunk))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            output.flush()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** Mixage en mono + rééchantillonnage linéaire vers 16 kHz. */
    private class Resampler(sourceRate: Int, targetRate: Int, private val channels: Int) {
        private val step = sourceRate.toDouble() / targetRate
        private var position = 0.0
        private var carry = ShortArray(0)

        fun process(pcm: ByteArray): ByteArray {
            val safeChannels = if (channels > 0) channels else 1
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val frames = pcm.size / 2 / safeChannels
            val mono = ShortArray(carry.size + frames)
            System.arraycopy(carry, 0, mono, 0, carry.size)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until safeChannels) sum += buffer.short.toInt()
                mono[carry.size + i] = (sum / safeChannels).toShort()
            }

            val out = ByteArrayOutputStream()
            while (position + 1 < mono.size) {
                val index = position.toInt()
                val fraction = position - index
                val a = mono[index].toInt()
                val b = mono[minOf(index + 1, mono.size - 1)].toInt()
                val value = (a + (b - a) * fraction).toInt().coerceIn(-32768, 32767)
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                position += step
            }
            val consumed = position.toInt().coerceAtMost(mono.size)
            carry = mono.copyOfRange(consumed, mono.size)
            position -= consumed
            return out.toByteArray()
        }
    }
}
