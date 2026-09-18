package com.fabrice.plansms.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.CalendarPrefs
import com.fabrice.plansms.data.CalendarRepository
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.relay.RelayMailer
import com.fabrice.plansms.util.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Demande automatique du numéro de portable, 48 h avant le rendez-vous.
 *
 * Une vérification périodique parcourt les RDV des 48 prochaines heures : ceux
 * dont le participant (email) n'a aucun numéro dans les contacts reçoivent
 * automatiquement l'email « pourriez-vous me communiquer votre portable ? »
 * (le modèle de Réglages / écran RDV), via le compte SMTP de l'app.
 *
 * Garde-fous :
 *  - un RDV donné ne déclenche qu'UN email, même si la vérification repasse ;
 *  - une même adresse n'est pas sollicitée deux fois en moins de 7 jours,
 *    même pour deux RDV différents ;
 *  - sans compte SMTP configuré, rien ne part ;
 *  - chaque envoi est tracé dans Journal → Envois (DEMANDE N° AUTO).
 */
object AskPhoneAhead {

    const val WINDOW_MS = 48 * 60 * 60 * 1000L
    const val EMAIL_COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000L

    private const val PREFS = "plansms_ask_ahead"
    private const val K_ENABLED = "enabled"
    private const val K_EVENTS = "asked_events"
    private const val K_EMAILS = "asked_emails"
    private const val WORK_NAME = "plansms-ask-phone-ahead"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
    }

    /**
     * Décision pure, testée unitairement : faut-il envoyer la demande pour ce RDV ?
     * [eventAskedAt] / [emailAskedAt] : 0 = jamais sollicité.
     */
    fun shouldAsk(
        enabled: Boolean,
        smtpReady: Boolean,
        start: Long,
        now: Long,
        eventAskedAt: Long,
        emailAskedAt: Long
    ): Boolean {
        if (!enabled || !smtpReady) return false
        if (start <= now || start > now + WINDOW_MS) return false   // passé, ou trop loin
        if (eventAskedAt > 0) return false                          // déjà demandé pour ce RDV
        if (emailAskedAt > 0 && now - emailAskedAt < EMAIL_COOLDOWN_MS) return false
        return true
    }

    /** Parcourt la fenêtre de 48 h et envoie les demandes manquantes. */
    suspend fun run(context: Context) {
        if (!enabled(context)) return
        if (!RelayMailer.isConfigured(context)) return
        val now = System.currentTimeMillis()
        val meetings = try {
            CalendarRepository.unresolvedMeetings(context, now, now + WINDOW_MS)
        } catch (e: Exception) {
            AppLogger.e("AskPhoneAhead", "Lecture agenda impossible", e)
            return
        }

        for (m in meetings) {
            val emailKey = m.email.trim().lowercase().replace("=", "%3D")
            val ok = shouldAsk(
                enabled = true, smtpReady = true,
                start = m.start, now = now,
                eventAskedAt = readMap(context, K_EVENTS)["${m.eventId}:${m.start}"] ?: 0L,
                emailAskedAt = readMap(context, K_EMAILS)[emailKey] ?: 0L
            )
            if (!ok) continue

            // Marqué AVANT l'envoi : jamais deux emails, même en cas de course
            mark(context, K_EVENTS, "${m.eventId}:${m.start}", now)
            mark(context, K_EMAILS, emailKey, now)

            val who = m.attendeeName.ifBlank { m.email }
            val subject = SmsRules.resolveTemplate(CalendarPrefs.askPhoneSubject(context), who, m.start)
            val body = SmsRules.resolveTemplate(CalendarPrefs.askPhoneBody(context), who, m.start)
            val error = RelayMailer.sendRaw(context, m.email, subject, body)

            try {
                AppDatabase.get(context).sendLogDao().insert(
                    SendLog(
                        scheduledId = 0,
                        phone = m.email,
                        textPreview = "DEMANDE N° AUTO (48h): " + subject.take(50),
                        status = if (error == null) "SENT" else "FAILED",
                        error = error ?: "",
                        sentAt = now
                    )
                )
            } catch (_: Exception) {}

            if (error == null) {
                AppLogger.i("AskPhoneAhead", "Demande de numéro envoyée → ${m.email} (RDV ${m.title})")
            } else {
                AppLogger.w("AskPhoneAhead", "Demande de numéro en échec → ${m.email} : $error")
            }
        }
    }

    /** Vérification périodique (6 h) : réarmée au démarrage de l'app et au boot. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AskPhoneWorker>(6, TimeUnit.HOURS).build()
        )
    }

    // --- Mémoire des demandes déjà faites, persistée et bornée ---------------

    private fun readMap(context: Context, key: String): Map<String, Long> =
        (prefs(context).getString(key, "") ?: "")
            .split(',')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                val ts = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && parts[0].isNotBlank() && ts != null) parts[0] to ts else null
            }
            .toMap()

    private fun mark(context: Context, key: String, id: String, now: Long) {
        val map = readMap(context, key).toMutableMap()
        map[id] = now
        val cutoff = now - 14 * 24 * 60 * 60 * 1000L
        val pruned = map.filterValues { it >= cutoff }
            .entries.sortedByDescending { it.value }.take(300)
            .joinToString(",") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(key, pruned).apply()
    }
}

/** Réveil périodique de la demande automatique de numéro. */
class AskPhoneWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            AskPhoneAhead.run(applicationContext)
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("AskPhoneWorker", "Vérification 48h impossible", e)
            Result.success()   // la prochaine période réessaiera
        }
    }
}
