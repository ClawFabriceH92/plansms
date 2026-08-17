package com.fabrice.plansms.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.fabrice.plansms.R

/** Envoi WhatsApp semi-auto : notification → ouverture WhatsApp avec le message pré-rempli. */
object WhatsAppSender {

    fun openChatIntent(phone: String, text: String): Intent {
        val clean = phone.replace(Regex("[^0-9+]"), "")
        val url = "https://wa.me/$clean?text=${Uri.encode(text)}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun notify(context: Context, msgId: Long, phone: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "plansms_whatsapp"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "WhatsApp programmé", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context, msgId.toInt(), openChatIntent(phone, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("WhatsApp programmé")
            .setContentText("Appuyez pour ouvrir WhatsApp avec le message prêt à envoyer")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1000 + msgId.toInt(), notif)
    }
}
