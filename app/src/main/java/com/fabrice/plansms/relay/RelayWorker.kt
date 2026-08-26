package com.fabrice.plansms.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fabrice.plansms.util.AppLogger

/**
 * Réveil de la file d'attente : au début d'un créneau, ou pour une nouvelle
 * tentative après un échec d'envoi.
 */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SmsRelay.flush(applicationContext)
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("RelayWorker", "Vidage de la file impossible", e)
            Result.retry()
        }
    }
}
