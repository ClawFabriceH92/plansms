package com.fabrice.plansms.recorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Option « proposer l'enregistrement pendant un appel » : quand un appel
 * téléphonique démarre, affiche une notification avec un bouton « Enregistrer ».
 * Rien ne démarre tout seul — c'est toujours une action explicite.
 */
class CallPromptReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "plansms_call_prompt"
        private const val NOTIF_ID = 4211

        // État de l'appel entrant en cours, pour le répondeur SMS : le broadcast
        // arrive plusieurs fois (avec puis sans numéro), on recolle la séquence
        // SONNE → (DÉCROCHÉ) → RACCROCHÉ.
        private var ringingNumber: String = ""
        private var wasAnswered = false

        fun cancel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        // --- Répondeur SMS : suit l'appel entrant, répond quand il se termine ---
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (number.isNotBlank()) ringingNumber = number
                wasAnswered = false
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                if (ringingNumber.isNotBlank()) wasAnswered = true
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val caller = ringingNumber
                val answered = wasAnswered
                ringingNumber = ""
                wasAnswered = false
                if (caller.isNotBlank() && com.fabrice.plansms.scheduler.CallResponder.enabled(context)) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            com.fabrice.plansms.scheduler.CallResponder.onCallEnded(context, caller, answered)
                        } catch (e: Exception) {
                            AppLogger.e("CallPrompt", "Répondeur SMS impossible", e)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }

        // --- Proposition d'enregistrement / bouton flottant (options dédiées) ---
        if (!RecorderPrefs.promptOnCall(context) && !RecorderPrefs.overlayButton(context)) return

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (RecorderPrefs.overlayButton(context)) CallOverlay.show(context)
                if (RecorderPrefs.promptOnCall(context)) showPrompt(context, number)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallOverlay.hide()
                cancel(context)
                if (RecorderState.isRecording.value) RecordingService.stop(context)
            }
        }
    }

    private fun showPrompt(context: Context, number: String) {
        if (RecorderState.isRecording.value) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proposition d'enregistrement", NotificationManager.IMPORTANCE_HIGH)
        )
        val record = PendingIntent.getForegroundService(
            context, 2,
            Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_LABEL, if (number.isBlank()) "Appel" else "Appel $number")
                putExtra(RecordingService.EXTRA_PHONE, number)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_btn_speak_now)
        val notif = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Appel en cours" + if (number.isNotBlank()) " — $number" else "")
            .setContentText("Enregistrer ? Pense à activer le haut-parleur pour capter les deux voix.")
            .addAction(android.app.Notification.Action.Builder(icon, "Enregistrer", record).build())
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            AppLogger.e("CallPrompt", "Notification refusée", e)
        }
    }
}
