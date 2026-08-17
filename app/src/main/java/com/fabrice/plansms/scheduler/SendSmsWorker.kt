package com.fabrice.plansms.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.Channel
import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.util.AppLogger

/**
 * Worker d'envoi : exécute le SMS puis :
 * - one-shot : marque SENT / FAILED (brouillon conservé, re-planifie 2 tentatives)
 * - récurrent : marque envoyé et re-planifie la prochaine occurrence
 * - canal WHATSAPP : notification semi-auto (ouverture WhatsApp pré-remplie)
 * - groupe : envoi à tous les membres
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

        val now = System.currentTimeMillis()

        // Déterminer les destinataires (numéro direct ou membres du groupe)
        val recipients: List<Pair<String, String>> = if (msg.groupId > 0) {
            db.groupMemberDao().getMembers(msg.groupId).map { it.phone to it.name }
        } else {
            listOf(msg.phone to "")
        }
        if (recipients.isEmpty()) {
            db.scheduledMessageDao().update(msg.copy(status = SmsStatus.FAILED, lastError = "Groupe vide"))
            return Result.success()
        }

        var allOk = true
        var firstError = ""

        for ((phone, name) in recipients) {
            val text = SmsRules.resolveTemplate(msg.text, name.ifBlank { phone }, now)
            when (msg.channel) {
                Channel.SMS -> {
                    val error = SmsSender.send(applicationContext, phone, text)
                    if (error == null) {
                        db.sendLogDao().insert(
                            SendLog(scheduledId = msg.id, phone = phone, textPreview = text.take(80), status = "SENT", sentAt = now)
                        )
                    } else {
                        allOk = false
                        firstError = error
                        db.sendLogDao().insert(
                            SendLog(scheduledId = msg.id, phone = phone, textPreview = text.take(80), status = "FAILED", error = error, sentAt = now)
                        )
                    }
                }
                Channel.WHATSAPP -> {
                    WhatsAppSender.notify(applicationContext, msg.id, phone, text)
                    db.sendLogDao().insert(
                        SendLog(scheduledId = msg.id, phone = phone, textPreview = text.take(80), status = "WHATSAPP", sentAt = now)
                    )
                    AppLogger.i("SendSmsWorker", "WhatsApp notifié → $phone")
                }
            }
        }

        return if (msg.channel == Channel.WHATSAPP || allOk) {
            if (msg.repeatRule == RepeatRule.ONCE) {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.SENT))
            } else {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.SCHEDULED))
                val next = SmsRules.nextOccurrence(msg, now)?.let { SmsRules.applyNoSendRange(it, msg, now) }
                if (next != null) {
                    SmsScheduler.schedule(applicationContext, msg.copy(targetDate = next), next)
                    AppLogger.i("SendSmsWorker", "Prochaine occurrence $next")
                }
            }
            Result.success()
        } else {
            // Échec : 2 tentatives espacées (1 min, 2 min), puis FAILED
            val attempts = msg.lastError.toIntOrNull() ?: 0
            if (attempts < 2) {
                db.scheduledMessageDao().update(msg.copy(lastError = (attempts + 1).toString()))
                SmsScheduler.scheduleRetry(applicationContext, msg, attempts + 1)
                AppLogger.w("SendSmsWorker", "SMS #${msg.id} échec (tentative ${attempts + 1}) : $firstError")
                Result.retry()
            } else {
                db.scheduledMessageDao().update(msg.copy(status = SmsStatus.FAILED, lastError = firstError))
                AppLogger.e("SendSmsWorker", "SMS #${msg.id} définitivement en échec : $firstError")
                Result.success()
            }
        }
    }
}
