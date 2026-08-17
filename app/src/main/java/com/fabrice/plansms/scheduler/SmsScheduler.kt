package com.fabrice.plansms.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fabrice.plansms.data.ScheduledMessage
import java.util.concurrent.TimeUnit

/**
 * Planification des envois : WorkManager (fiable, persistant) + alarme exacte en renfort
 * (réveil précis hors Doze). Le BOOT_COMPLETED re-planifie tout (rattrapage).
 */
object SmsScheduler {

    fun uniqueWorkName(id: Long) = "send_sms_$id"

    /** Planifie l'envoi d'un message à l'instant donné (epoch millis). */
    fun schedule(context: Context, msg: ScheduledMessage, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<SendSmsWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.Data.Builder().putLong("scheduledId", msg.id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(msg.id), ExistingWorkPolicy.REPLACE, request)

        scheduleExactAlarm(context, msg.id, at)
    }

    /** Alarme exacte : réveil même en Doze (setExactAndAllowWhileIdle, API 23+). */
    private fun scheduleExactAlarm(context: Context, id: Long, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context, id)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Permission SCHEDULE_EXACT_ALARM absente → fallback inexact
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Nouvelle tentative d'envoi après échec : 60 s * tentative. */
    fun scheduleRetry(context: Context, msg: ScheduledMessage, attempt: Int) {
        val request = OneTimeWorkRequestBuilder<SendSmsWorker>()
            .setInitialDelay(60L * attempt, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(androidx.work.Data.Builder().putLong("scheduledId", msg.id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(msg.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(id))
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context, id))
    }

    /** Re-planifie tous les messages SCHEDULED (appelé au boot / après mise à jour). */
    fun rescheduleAll(context: Context, messages: List<ScheduledMessage>) {
        val now = System.currentTimeMillis()
        for (msg in messages) {
            val next = com.fabrice.plansms.logic.SmsRules.nextOccurrence(msg, now)
            if (next == null) continue
            val target = com.fabrice.plansms.logic.SmsRules.applyNoSendRange(next, msg, now)
            schedule(context, msg.copy(targetDate = target), target)
        }
    }

    private fun alarmPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.fabrice.plansms.SEND_SMS_ALARM"
            putExtra("scheduledId", id)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
