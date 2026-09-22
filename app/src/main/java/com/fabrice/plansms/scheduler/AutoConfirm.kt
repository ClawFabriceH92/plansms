package com.fabrice.plansms.scheduler

import android.content.Context
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.CalendarPrefs
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.TomorrowRdv
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.util.AppLogger

/**
 * Envoi AUTOMATIQUE de la confirmation de RDV à 15h la veille (jours ouvrés),
 * uniquement pour les RDV dont le numéro est écrit dans l'événement lui-même
 * (titre, lieu ou description) : ce numéro-là, c'est toi qui l'y as mis — pas
 * de rapprochement à valider, l'envoi peut partir seul.
 *
 * Les RDV dont le numéro vient d'un contact ou d'un rapprochement restent en
 * validation manuelle dans l'écran « RDV de demain », comme avant.
 *
 * Garde-fous :
 *  - option désactivée par défaut (Réglages → Calendrier) ;
 *  - un RDV donné n'est confirmé qu'une seule fois, même si l'envoi repasse ;
 *  - porté par le rappel de 15h : jamais d'envoi le week-end ;
 *  - chaque envoi est tracé dans Journal → Envois (RDV AUTO).
 */
object AutoConfirm {

    private const val PREFS = "plansms_auto_confirm"
    private const val K_ENABLED = "enabled"
    private const val K_SENT = "sent_events"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
    }

    /** Décision pure, testée unitairement : ce RDV part-il automatiquement ? */
    fun shouldSend(
        enabled: Boolean,
        phoneFromEvent: Boolean,
        phone: String,
        alreadySentAt: Long
    ): Boolean {
        if (!enabled) return false
        if (!phoneFromEvent) return false     // contact ou rapprochement → validation manuelle
        if (phone.isBlank()) return false
        if (alreadySentAt > 0) return false   // déjà confirmé
        return true
    }

    /** Ce RDV a-t-il déjà reçu sa confirmation automatique ? (pour l'écran RDV) */
    fun wasSent(context: Context, eventId: Long, start: Long): Boolean =
        (sentMap(context)["$eventId:$start"] ?: 0L) > 0

    /**
     * Envoie les confirmations automatiques parmi [rdvs] (les RDV du prochain
     * jour ouvré, déjà lus par l'appelant). Retourne le nombre d'envois réussis.
     */
    suspend fun run(context: Context, rdvs: List<TomorrowRdv>): Int {
        if (!enabled(context)) return 0
        var sent = 0
        for (r in rdvs) {
            val key = "${r.event.id}:${r.event.start}"
            if (!shouldSend(true, r.phoneFromEvent, r.phone, sentMap(context)[key] ?: 0L)) continue

            val who = r.attendeeName.ifBlank { r.contactName }
            val text = SmsRules.resolveTemplate(
                CalendarPrefs.confirmMessage(context), who, r.event.start
            )
            val error = SmsSender.send(context, r.phone, text)
            if (error == null) {
                mark(context, key)
                sent++
            }
            try {
                AppDatabase.get(context).sendLogDao().insert(
                    SendLog(
                        scheduledId = 0,
                        phone = if (who.isBlank()) r.phone else "$who (${r.phone})",
                        textPreview = "RDV AUTO: " + text.take(60),
                        status = if (error == null) "SENT" else "FAILED",
                        error = error ?: "",
                        sentAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}
            if (error == null) {
                AppLogger.i("AutoConfirm", "Confirmation auto → ${r.phone} (${r.event.title})")
            } else {
                AppLogger.w("AutoConfirm", "Confirmation auto en échec → ${r.phone} : $error")
            }
        }
        return sent
    }

    // --- Mémoire des RDV déjà confirmés, persistée et bornée ------------------

    private fun sentMap(context: Context): Map<String, Long> =
        (prefs(context).getString(K_SENT, "") ?: "")
            .split(',')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                val ts = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && parts[0].isNotBlank() && ts != null) parts[0] to ts else null
            }
            .toMap()

    private fun mark(context: Context, key: String) {
        val now = System.currentTimeMillis()
        val map = sentMap(context).toMutableMap()
        map[key] = now
        val cutoff = now - 14 * 24 * 60 * 60 * 1000L
        val pruned = map.filterValues { it >= cutoff }
            .entries.sortedByDescending { it.value }.take(200)
            .joinToString(",") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(K_SENT, pruned).apply()
    }
}
