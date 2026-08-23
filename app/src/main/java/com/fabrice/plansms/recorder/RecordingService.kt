package com.fabrice.plansms.recorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fabrice.plansms.MainActivity
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.VoiceRecording
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistrement audio via le microphone, dans un service de premier plan
 * (l'enregistrement continue écran éteint / app en arrière-plan).
 *
 * Limite Android (toutes apps hors système, depuis Android 10) : la voix du
 * correspondant ne peut PAS être captée directement depuis la ligne. Il faut
 * mettre l'appel en haut-parleur — le micro capte alors les deux voix.
 * Cela vaut pour les appels GSM comme pour WhatsApp / Teams / Meet.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.fabrice.plansms.action.RECORD_START"
        const val ACTION_STOP = "com.fabrice.plansms.action.RECORD_STOP"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_PHONE = "extra_phone"
        const val CHANNEL_ID = "plansms_recording"
        private const val NOTIF_ID = 4210

        /** Dossier des enregistrements (privé à l'app, visible dans Android/data). */
        fun recordingsDir(context: Context): File {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val dir = File(base, "enregistrements")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun start(context: Context, label: String = "", phone: String = "") {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PHONE, phone)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var label = ""
    private var phone = ""
    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Obligatoire dans les 5 s après startForegroundService(), y compris pour un simple arrêt
        startForegroundCompat()
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            else -> {
                label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
                phone = intent?.getStringExtra(EXTRA_PHONE).orEmpty()
                startRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        RecorderState.lastError.value = ""

        val name = "PlanSMS-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date()) + ".m4a"
        val file = File(recordingsDir(this), name)
        val source = if (RecorderPrefs.audioSource(this) == RecorderPrefs.SOURCE_RECOGNITION) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(source)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            AppLogger.e("RecordingService", "Démarrage enregistrement impossible", e)
            RecorderState.lastError.value =
                "Enregistrement impossible : " + (e.message ?: "micro indisponible") +
                    ". Une autre app (appel, visio) utilise peut-être déjà le micro."
            try { rec.release() } catch (_: Exception) {}
            RecorderState.isRecording.value = false
            stopSelf()
            return
        }

        recorder = rec
        outputFile = file
        startedAt = System.currentTimeMillis()
        RecorderState.isRecording.value = true
        RecorderState.elapsedMs.value = 0L
        AppLogger.i("RecordingService", "Enregistrement démarré : ${file.name} (source $source)")

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (RecorderState.isRecording.value) {
                RecorderState.elapsedMs.value = System.currentTimeMillis() - startedAt
                delay(500)
            }
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        tickerJob?.cancel()
        val durationMs = System.currentTimeMillis() - startedAt
        var ok = true
        try {
            rec.stop()
        } catch (e: Exception) {
            // stop() lève une exception si l'enregistrement dure moins d'une seconde
            ok = false
            AppLogger.w("RecordingService", "Arrêt enregistrement : ${e.message}")
        }
        try { rec.release() } catch (_: Exception) {}
        RecorderState.isRecording.value = false
        RecorderState.elapsedMs.value = 0L

        val file = outputFile
        outputFile = null
        if (file == null) return

        if (!ok || !file.exists() || file.length() < 1024) {
            file.delete()
            RecorderState.lastError.value = "Enregistrement trop court — rien n'a été conservé."
            return
        }

        val recording = VoiceRecording(
            filePath = file.absolutePath,
            label = label,
            phone = phone,
            source = RecorderPrefs.audioSource(this),
            durationMs = durationMs,
            sizeBytes = file.length()
        )
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(appContext).voiceRecordingDao()
            val id = dao.insert(recording)
            AppLogger.i("RecordingService", "Enregistrement conservé : ${file.name} (${durationMs / 1000}s)")
            // Export vers la destination choisie (dossier / FTP / email), si configurée
            if (com.fabrice.plansms.data.StoragePrefs.destination(appContext) !=
                com.fabrice.plansms.data.StoragePrefs.DEST_LOCAL
            ) {
                val res = com.fabrice.plansms.export.RecordingExporter.export(appContext, file, label)
                dao.update(
                    recording.copy(
                        id = id,
                        exportStatus = if (res.ok) "OK" else "ERREUR",
                        exportInfo = res.message
                    )
                )
            }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Enregistrement en cours", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIcon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        val notif = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PlanSMS — enregistrement en cours")
            .setContentText("Touche pour ouvrir, ou « Arrêter » pour conserver le fichier.")
            .setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(stopIcon, "Arrêter", stop).build())
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
