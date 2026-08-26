package com.fabrice.plansms.data

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.fabrice.plansms.util.AppLogger
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transcription 100 % SUR LE TÉLÉPHONE, sans réseau.
 *
 * Le moteur vocal d'Android ne sait écouter que le micro… sauf depuis Android 13,
 * où l'on peut lui fournir un flux audio via un tube (EXTRA_AUDIO_SOURCE). On
 * prépare donc le fichier — mono, 16 kHz, nettoyé, remis à niveau — et on l'y
 * injecte. Rien ne sort de l'appareil.
 *
 * La préparation du son compte autant que le moteur : un message de répondeur est
 * faible, encombré de ronflement, et le rééchantillonnage brut d'un 44,1 kHz vers
 * 16 kHz replie tout l'aigu en bruit au milieu de la voix. D'où, dans cet ordre :
 * mixage mono → passe-haut 80 Hz → anti-repliement 7 kHz → 16 kHz → silence de
 * tête retiré → niveau normalisé.
 */
object OnDeviceTranscriber {

    private const val TARGET_RATE = 16_000
    private const val HIGH_PASS_HZ = 80.0
    private const val ANTI_ALIAS_HZ = 7_000.0

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

    /** Son conditionné, prêt à être injecté : PCM 16 bits 16 kHz mono. */
    private class Prepared(val pcm: File, val gain: Float, val startOffset: Long)

    /**
     * On prépare AVANT d'allumer le moteur : sinon il attend le son pendant le
     * décodage et conclut au silence.
     */
    @RequiresApi(33)
    private fun startOnDevice(
        context: Context,
        file: File,
        language: String,
        onDone: (ok: Boolean, text: String, error: String) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val prepared = try {
                prepare(context, file)
            } catch (e: Exception) {
                AppLogger.e("OnDeviceTranscriber", "Préparation audio impossible", e)
                null
            }
            main.post {
                if (prepared == null) {
                    onDone(false, "", "Audio illisible : format non pris en charge par Android.")
                } else if (prepared.pcm.length() - prepared.startOffset < 8_000) {
                    prepared.pcm.delete()
                    onDone(false, "", "Enregistrement trop court ou silencieux (moins de 0,25 s de parole).")
                } else {
                    listen(context, prepared, language, onDone)
                }
            }
        }.start()
    }

    @RequiresApi(33)
    private fun listen(
        context: Context,
        prepared: Prepared,
        language: String,
        onDone: (ok: Boolean, text: String, error: String) -> Unit
    ) {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            prepared.pcm.delete()
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
            prepared.pcm.delete()
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
            // Ponctuation, majuscules, chiffres en chiffres : bien plus lisible
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY
            )
            // Pas de « m***e » : c'est une transcription de travail, pas un sous-titre
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            // Un blanc au milieu d'un message ne doit pas clore la reconnaissance
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000)
            // Le moteur lit le tube au lieu du micro
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // Sans session segmentée, la reconnaissance s'arrête au premier silence
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        recognizer.startListening(intent)

        // Injection du son préparé dans le tube, en tâche de fond
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    feed(prepared, out)
                }
                AppLogger.i(
                    "OnDeviceTranscriber",
                    "Audio injecté : ${prepared.pcm.length()} octets, gain ×%.1f".format(prepared.gain)
                )
            } catch (e: Exception) {
                AppLogger.e("OnDeviceTranscriber", "Injection impossible", e)
            }
        }.start()
    }

    /** Relit le PCM préparé en appliquant le gain de normalisation. */
    private fun feed(prepared: Prepared, output: OutputStream) {
        prepared.pcm.inputStream().use { input ->
            var toSkip = prepared.startOffset
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            val chunk = ByteArray(8_192)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (prepared.gain != 1f) {
                    val buffer = ByteBuffer.wrap(chunk, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                    var i = 0
                    while (i + 1 < read) {
                        val amplified = (buffer.getShort(i) * prepared.gain)
                            .toInt().coerceIn(-32768, 32767)
                        buffer.putShort(i, amplified.toShort())
                        i += 2
                    }
                }
                output.write(chunk, 0, read)
            }
            output.flush()
        }
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

    /**
     * Décode et conditionne le fichier en PCM 16 bits / 16 kHz / mono dans le cache.
     * Retourne aussi le gain à appliquer et l'offset du premier son utile.
     */
    private fun prepare(context: Context, source: File): Prepared? {
        val target = File.createTempFile("plansms-asr", ".pcm", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var peak = 0
        var startOffset = -1L
        var written = 0L

        try {
            extractor.setDataSource(source.absolutePath)
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
            if (track < 0 || format == null) {
                target.delete()
                return null
            }
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                target.delete()
                return null
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var chain = Chain(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            target.outputStream().buffered().use { out ->
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
                            chain = Chain(
                                actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            )
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> { /* rien à lire pour l'instant */ }
                        else -> if (outIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outIndex)
                                if (buffer != null) {
                                    val raw = ByteArray(info.size)
                                    buffer.position(info.offset)
                                    buffer.get(raw, 0, info.size)
                                    val conditioned = chain.process(raw)
                                    // Crête et début de parole, calculés au vol
                                    var i = 0
                                    while (i + 1 < conditioned.size) {
                                        val value = abs(
                                            ((conditioned[i + 1].toInt() shl 8) or
                                                (conditioned[i].toInt() and 0xFF)).toShort().toInt()
                                        )
                                        if (value > peak) peak = value
                                        if (startOffset < 0 && value > 900) startOffset = written + i
                                        i += 2
                                    }
                                    out.write(conditioned)
                                    written += conditioned.size
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                out.flush()
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }

        if (written == 0L) {
            target.delete()
            return null
        }
        // Remise à niveau : un répondeur est faible, le moteur attend une voix au micro.
        // Plafonné à ×12 pour ne pas transformer le souffle en parole.
        val gain = if (peak in 1..29_000) (29_000f / peak).coerceAtMost(12f) else 1f
        // On garde 0,2 s avant la première syllabe, et on aligne sur un échantillon
        val trimmed = ((startOffset - TARGET_RATE / 5 * 2).coerceAtLeast(0L)) / 2 * 2
        return Prepared(target, gain, if (startOffset < 0) 0L else trimmed)
    }

    /**
     * Mixage mono → passe-haut → anti-repliement → rééchantillonnage 16 kHz.
     * Le passe-bas avant décimation est ce qui évite que l'aigu se replie
     * en sifflement au milieu de la voix.
     */
    private class Chain(sourceRate: Int, channels: Int) {
        private val channels = if (channels > 0) channels else 1
        private val rate = if (sourceRate > 0) sourceRate else TARGET_RATE
        private val step = rate.toDouble() / TARGET_RATE
        private val highPass = Biquad.highPass(HIGH_PASS_HZ, rate)
        private val antiAlias =
            if (rate > TARGET_RATE) listOf(
                Biquad.lowPass(ANTI_ALIAS_HZ, rate),
                Biquad.lowPass(ANTI_ALIAS_HZ, rate)
            ) else emptyList()
        private var position = 0.0
        private var carry = DoubleArray(0)

        fun process(pcm: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val frames = pcm.size / 2 / channels
            val mono = DoubleArray(carry.size + frames)
            System.arraycopy(carry, 0, mono, 0, carry.size)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += buffer.short.toInt()
                var value = sum.toDouble() / channels
                value = highPass.process(value)
                for (filter in antiAlias) value = filter.process(value)
                mono[carry.size + i] = value
            }

            val out = ByteArray(((mono.size - position) / step).toInt().coerceAtLeast(0) * 2)
            var written = 0
            while (position + 1 < mono.size && written + 1 < out.size) {
                val index = position.toInt()
                val fraction = position - index
                val a = mono[index]
                val b = mono[minOf(index + 1, mono.size - 1)]
                val value = (a + (b - a) * fraction).toInt().coerceIn(-32768, 32767)
                out[written] = (value and 0xFF).toByte()
                out[written + 1] = ((value shr 8) and 0xFF).toByte()
                written += 2
                position += step
            }
            val consumed = position.toInt().coerceAtMost(mono.size)
            carry = mono.copyOfRange(consumed, mono.size)
            position -= consumed
            return if (written == out.size) out else out.copyOf(written)
        }
    }

    /** Filtre biquad Butterworth (Q = 0,707), formules Audio EQ Cookbook. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            private const val Q = 0.70710678

            fun lowPass(cutoff: Double, rate: Int): Biquad {
                val w0 = 2.0 * Math.PI * cutoff / rate
                val cosw = cos(w0)
                val alpha = sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    (1.0 - cosw) / 2.0 / a0, (1.0 - cosw) / a0, (1.0 - cosw) / 2.0 / a0,
                    (-2.0 * cosw) / a0, (1.0 - alpha) / a0
                )
            }

            fun highPass(cutoff: Double, rate: Int): Biquad {
                val w0 = 2.0 * Math.PI * cutoff / rate
                val cosw = cos(w0)
                val alpha = sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    (1.0 + cosw) / 2.0 / a0, -(1.0 + cosw) / a0, (1.0 + cosw) / 2.0 / a0,
                    (-2.0 * cosw) / a0, (1.0 - alpha) / a0
                )
            }
        }
    }
}
