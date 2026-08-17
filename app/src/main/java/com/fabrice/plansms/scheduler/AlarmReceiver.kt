package com.fabrice.plansms.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fabrice.plansms.util.AppLogger

/** Reçoit l'alarme exacte et déclenche le worker d'envoi immédiatement. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("scheduledId", -1L)
        if (id <= 0) return
        val request = OneTimeWorkRequestBuilder<SendSmsWorker>()
            .setInputData(androidx.work.Data.Builder().putLong("scheduledId", id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SmsScheduler.uniqueWorkName(id), ExistingWorkPolicy.REPLACE, request
        )
        AppLogger.i("AlarmReceiver", "Alarme reçue pour SMS #$id")
    }
}
