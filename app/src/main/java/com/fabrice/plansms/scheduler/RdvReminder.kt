package com.fabrice.plansms.scheduler

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabrice.plansms.MainActivity
import com.fabrice.plansms.data.CalendarPrefs
import com.fabrice.plansms.data.CalendarRepository
import com.fabrice.plansms.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Rappel quotidien à 15h (jours ouvrés uniquement) : notification s'il y a des RDV
 * au prochain jour ouvré (demain, ou lundi quand on est vendredi) avec participant.
 * Jamais de notification le samedi ni le dimanche.
 */
object RdvReminder {

    private const val REQUEST_CODE = 9150
    const val CHANNEL_ID = "rdv_reminder"
    const val EXTRA_OPEN_RDV = "open_rdv"

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        if (!CalendarPrefs.reminderEnabled(context)) {
            am.cancel(pi)
            return
        }
        val next = nextTrigger()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi)
        }
        AppLogger.i("RdvReminder", "Prochain rappel RDV : ${SimpleDateFormat("EEE dd/MM HH:mm", Locale.FRANCE).format(Date(next))}")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RdvReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Prochain déclenchement : aujourd'hui 15h si pas encore passé, sinon le prochain jour ouvré à 15h. */
    fun nextTrigger(now: Calendar = Calendar.getInstance()): Long {
        val c = now.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 15)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 1)
        while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun showNotification(context: Context, count: Int, targetStart: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rappel RDV du lendemain", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val dayLabel = SimpleDateFormat("EEEE dd/MM", Locale.FRANCE).format(Date(targetStart))
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_RDV, true)
        }
        val contentIntent = PendingIntent.getActivity(
            context, REQUEST_CODE + 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("$count RDV $dayLabel")
            .setContentText("Touche pour envoyer les SMS de confirmation.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(REQUEST_CODE, notif)
        } catch (e: SecurityException) {
            AppLogger.e("RdvReminder", "Notification refusée (permission)", e)
        }
    }
}

/** À 15h les jours ouvrés : compte les RDV du prochain jour ouvré et notifie s'il y en a. */
class RdvReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
                if (!weekend && CalendarPrefs.reminderEnabled(context)) {
                    val result = CalendarRepository.tomorrowMeetings(context)
                    AppLogger.i("RdvReminder", "15h : ${result.withEmail.size} RDV avec participant au prochain jour ouvré")
                    if (result.withEmail.isNotEmpty()) {
                        RdvReminder.showNotification(context, result.withEmail.size, result.targetStart)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("RdvReminder", "Erreur rappel RDV", e)
            } finally {
                RdvReminder.schedule(context)   // réarme le prochain jour ouvré à 15h
                pending.finish()
            }
        }.start()
    }
}
