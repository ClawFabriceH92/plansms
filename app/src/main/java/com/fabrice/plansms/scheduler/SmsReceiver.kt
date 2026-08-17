package com.fabrice.plansms.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.AutoReplyRule
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Auto-réponse : reçoit les SMS entrants et répond selon la règle configurée.
 * - mode ALL_EXCEPT : répond à tous SAUF les numéros de la liste
 * - mode ONLY : répond UNIQUEMENT aux numéros de la liste
 * Anti-boucle : numéros courts/services ignorés, 1 réponse max par expéditeur / 10 min.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val recentReplies = HashMap<String, Long>()
        private const val MIN_REPLY_INTERVAL = 10 * 60 * 1000L // 10 min
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val db = AppDatabase.get(context)
            val rule = db.autoReplyRuleDao().get() ?: return@launch
            if (!rule.enabled) return@launch

            val messages = Telephony.SmsMessages.fromIntent(intent) ?: return@launch
            if (messages.isEmpty()) return@launch
            val sender = messages.first().originatingAddress ?: return@launch

            if (!shouldReply(rule, sender)) return@launch

            // Anti-boucle : pas de réponse aux numéros courts (services)
            if (sender.replace(Regex("[^0-9]"), "").length <= 5) return@launch

            val now = System.currentTimeMillis()
            synchronized(recentReplies) {
                val last = recentReplies[sender]
                if (last != null && now - last < MIN_REPLY_INTERVAL) return@launch
                recentReplies[sender] = now
                if (recentReplies.size > 200) recentReplies.clear()
            }

            // Délai configuré avant réponse
            if (rule.delayMinutes > 0) delay(rule.delayMinutes * 60_000L)

            // Option "ne répondre que si inoccupé"
            if (rule.onlyWhenIdle && isInCall(context)) return@launch

            val reply = SmsRules.resolveTemplate(rule.replyText, sender, now)
            val error = SmsSender.send(context, sender, reply)
            if (error == null) {
                db.sendLogDao().insert(
                    com.fabrice.plansms.data.SendLog(
                        scheduledId = 0, phone = sender,
                        textPreview = "AUTO-RÉPONSE: " + reply.take(60),
                        status = "SENT", sentAt = System.currentTimeMillis()
                    )
                )
                AppLogger.i("SmsReceiver", "Auto-réponse envoyée → $sender")
            } else {
                AppLogger.w("SmsReceiver", "Auto-réponse échouée → $sender : $error")
            }
        }
    }

    private fun shouldReply(rule: AutoReplyRule, sender: String): Boolean {
        val numbers = rule.numbers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val isListed = numbers.any { normalize(it) == normalize(sender) }
        return if (rule.mode == "ONLY") isListed else !isListed
    }

    private fun normalize(s: String): String = s.replace(Regex("[^0-9+]"), "")

    private fun isInCall(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) { false }
    }
}

/** Petite extraction des messages SMS depuis l'intent. */
object Telephony {
    object SmsMessages {
        fun fromIntent(intent: Intent): List<SmsMessage>? {
            val pdus = intent.getParcelableArrayExtra("pdus") ?: return null
            return pdus.mapNotNull { pdu ->
                try { SmsMessage.createFromPdu(pdu as ByteArray) } catch (e: Exception) { null }
            }
        }
    }
}
