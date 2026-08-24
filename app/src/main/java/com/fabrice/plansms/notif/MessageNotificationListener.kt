package com.fabrice.plansms.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.CallLogRepository
import com.fabrice.plansms.data.ContactsHelper
import com.fabrice.plansms.data.InboundMessage
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Capture les messages entrants des applications de messagerie via leurs
 * notifications. C'est la SEULE façon, pour une application tierce, de savoir
 * qu'un correspondant a écrit en RCS / « chat » : ces messages ne sont pas
 * enregistrés dans la base SMS d'Android.
 *
 * Limite assumée : ne voit que les messages reçus APRÈS activation, et
 * seulement si la notification est affichée.
 */
class MessageNotificationListener : NotificationListenerService() {

    companion object {
        /** Applications de messagerie SMS/RCS surveillées. */
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.miui.smsextra",
            "com.android.messaging"
        )

        fun isEnabled(context: Context): Boolean = try {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val me = ComponentName(context, MessageNotificationListener::class.java).flattenToString()
            flat.split(":").any { it.equals(me, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotifPrefs.captureEnabled(this)) return
        if (sbn.packageName !in MESSAGING_PACKAGES) return
        // Les résumés de groupe n'ont ni expéditeur ni contenu exploitable
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return

        val postedAt = sbn.postTime
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Le titre est soit le numéro, soit le nom du contact : on résout le nom si besoin
                val digits = title.filter { it.isDigit() }
                val number = if (digits.length >= 9) {
                    title
                } else {
                    ContactsHelper.phoneForDisplayName(appContext, title) ?: return@launch
                }
                val key = CallLogRepository.matchKey(number)
                if (key.length < 9) return@launch

                val dao = AppDatabase.get(appContext).inboundMessageDao()
                // Anti-doublon : Android republie la même notification à chaque mise à jour
                val existing = dao.forKey(key)
                if (existing.any { kotlin.math.abs(it.receivedAt - postedAt) < 5_000 }) return@launch

                dao.insert(
                    InboundMessage(
                        matchKey = key,
                        address = title,
                        receivedAt = postedAt,
                        source = "NOTIF",
                        preview = text.take(70)
                    )
                )
                dao.purge(System.currentTimeMillis() - 180L * 86_400_000L)
                AppLogger.i("NotifListener", "Message capté de $title (${sbn.packageName})")
            } catch (e: Exception) {
                AppLogger.e("NotifListener", "Capture impossible", e)
            }
        }
    }
}
