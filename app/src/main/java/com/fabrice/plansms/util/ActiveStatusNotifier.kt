package com.fabrice.plansms.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Icône permanente dans la barre d'état tant qu'une veille de PlanSMS est
 * active (répondeur SMS et/ou relais SMS).
 *
 * Android réserve le côté de l'opérateur aux icônes système : la seule place
 * accessible à une application est le côté des notifications. D'où cette
 * notification silencieuse, épinglée, sans pastille — visible d'un coup d'œil,
 * jamais bruyante.
 */
object ActiveStatusNotifier {

    private const val CHANNEL = "plansms_active"
    private const val NOTIF_ID = 4300

    /** À appeler à chaque changement d'état (activation, désactivation, démarrage). */
    fun refresh(context: Context) {
        try {
            val responder = com.fabrice.plansms.scheduler.CallResponder.enabled(context)
            val relay = com.fabrice.plansms.relay.RelayPrefs.enabled(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!responder && !relay) {
                nm.cancel(NOTIF_ID)
                return
            }

            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Veille active (icône d'état)", NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        description = "Icône permanente indiquant que le répondeur ou le relais veille."
                    }
                )
            }

            val parts = mutableListOf<String>()
            if (responder) parts.add("📵 Répondeur SMS")
            if (relay) parts.add("📨 Relais SMS")

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, com.fabrice.plansms.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_voicemail)
                    .setContentTitle(parts.joinToString(" · "))
                    .setContentText("PlanSMS veille sur les appels et messages. Touche pour ouvrir.")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setShowWhen(false)
                    .build()
            )
        } catch (e: Exception) {
            AppLogger.e("ActiveStatus", "Icône d'état impossible", e)
        }
    }
}
