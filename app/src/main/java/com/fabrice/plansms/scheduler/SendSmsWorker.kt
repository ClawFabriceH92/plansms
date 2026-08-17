package com.fabrice.plansms.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.util.AppLogger

/**
 * Worker d'envoi : exécute le SMS puis :
 * - one-shot : marque SENT / FAILED (brouillon conservé, re-planifie 2 tentatives)
 * - récurrent : marque envoyé et re-planifie la prochaine occurrence
 */
class SendSmsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong("scheduledId", -1L)
        if (id <= 0) return Result.failure()
        val db = AppDatabase.get(applicationContext)
        val msg = db.scheduledMessageDao().getById(id) ?: return Result.success()

        // Récupérer le nom du contact pour les variables (best effort)
        val contactName = msg.phone

        val error = SmsSender.send(applicationContext, msg.phone, msg.text)
        val now = System.currentTimeMillis()

        return if (error == null) {
            db.sendLogDao().insert(
                SendLog(scheduledId = msg.id, phone = msg.phone, textPreview = msg.text.take(80), status = "SENT", sentAt = now)
            )
            if (msg.repeatRule == com.fabrice.plansms.data.RepeatRule.ONCE) {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.SENT))
                AppLogger.i("SendSmsWorker", "SMS #${msg.id} envoyé (one-shot)")
            } else {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.SCHEDULED))
                val next = SmsRules.nextOccurrence(msg, now)?.let { SmsRules.applyNoSendRange(it, msg, now) }
                if (next != null) {
                    SmsScheduler.schedule(applicationContext, msg.copy(targetDate = next), next)
                    AppLogger.i("SendSmsWorker", "SMS #${msg.id} envoyé, prochaine occurrence $next")
                }
            }
            Result.success()
        } else {
            // Échec : 2 tentatives espacées (1 min, 2 min), puis FAILED
            val attempts = msg.lastError.toIntOrNull() ?: 0
            if (attempts < 2) {
                db.scheduledMessageDao().update(msg.copy(lastError = (attempts + 1).toString()))
                SmsScheduler.scheduleRetry(applicationContext, msg, attempts + 1)
                AppLogger.w("SendSmsWorker", "SMS #${msg.id} échec (tentative ${attempts + 1}) : $error")
            } else {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.FAILED, lastError = error))
                db.sendLogDao().insert(
                    SendLog(scheduledId = msg.id, phone = msg.phone, textPreview = msg.text.take(80), status = "FAILED", error = error, sentAt = now)
                )
                AppLogger.e("SendSmsWorker", "SMS #${msg.id} définitivement en échec : $error")
            }
            Result.retry()
        }
    }
}
