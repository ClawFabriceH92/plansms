package com.fabrice.plansms.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Au démarrage du téléphone (ou après mise à jour de l'app) :
 * 1. Rattrapage : les messages dont l'heure est passée sont envoyés immédiatement (log RATTRAPAGE).
 * 2. Re-planification de tous les messages SCHEDULED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLogger.i("BootReceiver", "Démarrage reçu : $action")

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val db = AppDatabase.get(context)
            val messages = db.scheduledMessageDao().getScheduled()
            val now = System.currentTimeMillis()
            var caughtUp = 0
            var rescheduled = 0

            for (msg in messages) {
                val next = SmsRules.nextOccurrence(msg, now)
                if (msg.repeatRule == com.fabrice.plansms.data.RepeatRule.ONCE && next == null) {
                    // One-shot expiré pendant l'extinction : ENVOI de rattrapage immédiat
                    val error = SmsSender.send(context, msg.phone, msg.text)
                    if (error == null) {
                        db.scheduledMessageDao().update(msg.copy(status = SmsStatus.SENT))
                        db.sendLogDao().insert(
                            SendLog(scheduledId = msg.id, phone = msg.phone, textPreview = msg.text.take(80), status = "RATTRAPAGE", sentAt = now)
                        )
                        caughtUp++
                    } else {
                        db.scheduledMessageDao().update(msg.copy(status = SmsStatus.FAILED, lastError = error))
                        db.sendLogDao().insert(
                            SendLog(scheduledId = msg.id, phone = msg.phone, textPreview = msg.text.take(80), status = "FAILED", error = error, sentAt = now)
                        )
                    }
                } else if (next != null) {
                    val target = SmsRules.applyNoSendRange(next, msg, now)
                    SmsScheduler.schedule(context, msg.copy(targetDate = target), target)
                    rescheduled++
                }
            }
            AppLogger.i("BootReceiver", "Rattrapage : $caughtUp envoyé(s), $rescheduled replanifié(s)")
        }

        // Réarme le rappel 15h des RDV du lendemain (jours ouvrés)
        RdvReminder.schedule(context)
    }
}
