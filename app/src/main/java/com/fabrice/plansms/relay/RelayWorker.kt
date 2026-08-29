package com.fabrice.plansms.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fabrice.plansms.util.AppLogger

/**
 * Vidage de la file d'attente du relais : à la réception d'un message (job
 * expédié), au début d'un créneau, ou pour une nouvelle tentative après un
 * échec d'envoi.
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

    /**
     * Requis pour un job expédié sous Android 10–11 : WorkManager le fait alors
     * tourner en service de premier plan, avec cette discrète notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        relayForegroundInfo(applicationContext)
}

/** Bilan quotidien de 19h30, qui se reprogramme lui-même pour le lendemain. */
class RelayDigestWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SmsRelay.runDailyDigest(applicationContext)
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("RelayDigestWorker", "Bilan quotidien impossible", e)
            SmsRelay.scheduleDailyDigest(applicationContext)
            Result.success()
        }
    }
}

private const val FG_CHANNEL = "plansms_relay_fg"

internal fun relayForegroundInfo(context: Context): ForegroundInfo {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(FG_CHANNEL, "Relais SMS — envoi", NotificationManager.IMPORTANCE_LOW)
        )
    }
    val notification = NotificationCompat.Builder(context, FG_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("Relais SMS")
        .setContentText("Transfert en cours…")
        .setOngoing(true)
        .build()
    return ForegroundInfo(4210, notification)
}
