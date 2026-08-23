package com.fabrice.plansms.recorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.fabrice.plansms.util.AppLogger

/**
 * Option « proposer l'enregistrement pendant un appel » : quand un appel
 * téléphonique démarre, affiche une notification avec un bouton « Enregistrer ».
 * Rien ne démarre tout seul — c'est toujours une action explicite.
 */
class CallPromptReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "plansms_call_prompt"
        private const val NOTIF_ID = 4211

        fun cancel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!RecorderPrefs.promptOnCall(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> showPrompt(context, number)
            TelephonyManager.EXTRA_STATE_IDLE -> {
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
