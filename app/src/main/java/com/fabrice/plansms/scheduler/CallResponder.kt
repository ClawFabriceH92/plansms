package com.fabrice.plansms.scheduler

import android.content.Context
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.CallLogRepository
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.util.AppLogger

/**
 * Répondeur SMS : quand un appel entrant se termine, l'appelant reçoit
 * automatiquement le message configuré — uniquement les numéros capables de
 * recevoir un SMS (mobiles français 06/07 et numéros étrangers), jamais les
 * fixes ni les numéros courts.
 *
 * Garde-fous :
 *  - par défaut, seuls les appels NON décrochés déclenchent l'envoi (c'est un
 *    répondeur) ; option pour répondre aussi aux appels décrochés ;
 *  - un même numéro ne reçoit pas deux réponses en moins de 4 heures, même
 *    s'il rappelle dix fois ;
 *  - chaque envoi est tracé dans Journal → Envois (statut RÉPONDEUR).
 */
object CallResponder {

    private const val PREFS = "plansms_call_responder"
    private const val K_ENABLED = "enabled"
    private const val K_MESSAGE = "message"
    private const val K_MODE = "mode"
    private const val K_RECENT = "recent_replies"

    const val MODE_MISSED = "MISSED"   // appels non décrochés seulement (défaut)
    const val MODE_ALL = "ALL"         // tous les appels entrants

    /** Un même numéro n'est pas re-répondu avant ce délai. */
    const val COOLDOWN_MS = 4 * 60 * 60 * 1000L

    const val DEFAULT_MESSAGE =
        "Bonjour, je ne peux pas répondre pour le moment. J'ai bien reçu votre appel " +
            "et je vous rappelle dès que possible. Cordialement."

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
    }

    fun message(context: Context): String =
        prefs(context).getString(K_MESSAGE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE

    fun setMessage(context: Context, value: String) {
        prefs(context).edit().putString(K_MESSAGE, value.trim()).apply()
    }

    fun mode(context: Context): String = prefs(context).getString(K_MODE, MODE_MISSED) ?: MODE_MISSED

    fun setMode(context: Context, value: String) {
        prefs(context).edit().putString(K_MODE, value).apply()
    }

    /**
     * Décision pure, testée unitairement : faut-il répondre à cet appel ?
     * [lastSentAt] = dernier envoi du répondeur à ce numéro (0 = jamais).
     */
    fun shouldReply(
        enabled: Boolean,
        mode: String,
        answered: Boolean,
        number: String,
        lastSentAt: Long,
        now: Long
    ): Boolean {
        if (!enabled) return false
        if (number.isBlank()) return false                          // numéro masqué
        if (answered && mode != MODE_ALL) return false              // décroché = pas un appel manqué
        if (!CallLogRepository.canReceiveSms(number)) return false  // fixes, n° courts, services
        if (lastSentAt > 0 && now - lastSentAt < COOLDOWN_MS) return false
        return true
    }

    /** Appelé par le receiver téléphonie quand un appel entrant se termine. */
    suspend fun onCallEnded(context: Context, number: String, answered: Boolean) {
        val now = System.currentTimeMillis()
        val key = CallLogRepository.matchKey(number)
        if (!shouldReply(enabled(context), mode(context), answered, number, lastSentAt(context, key), now)) {
            return
        }

        markSent(context, key, now)   // avant l'envoi : jamais deux SMS, même en cas de course
        val text = message(context)
        val error = SmsSender.send(context, number, text)
        try {
            AppDatabase.get(context).sendLogDao().insert(
                SendLog(
                    scheduledId = 0,
                    phone = number,
                    textPreview = "RÉPONDEUR: " + text.take(60),
                    status = if (error == null) "SENT" else "FAILED",
                    error = error ?: "",
                    sentAt = now
                )
            )
        } catch (e: Exception) {
            AppLogger.e("CallResponder", "Journalisation impossible", e)
        }
        if (error == null) {
            AppLogger.i("CallResponder", "Répondeur SMS → $number (décroché=$answered)")
        } else {
            AppLogger.w("CallResponder", "Répondeur SMS en échec → $number : $error")
        }
    }

    // --- Mémoire anti-rafale : numéro → dernier envoi, persistée, bornée -----

    private fun lastSentAt(context: Context, key: String): Long =
        recentMap(context)[key] ?: 0L

    private fun markSent(context: Context, key: String, now: Long) {
        val map = recentMap(context).toMutableMap()
        map[key] = now
        // Purge : entrées plus vieilles que 48 h, et jamais plus de 200 numéros
        val cutoff = now - 48 * 60 * 60 * 1000L
        val pruned = map.filterValues { it >= cutoff }
            .entries.sortedByDescending { it.value }.take(200)
            .joinToString(",") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(K_RECENT, pruned).apply()
    }

    private fun recentMap(context: Context): Map<String, Long> =
        (prefs(context).getString(K_RECENT, "") ?: "")
            .split(',')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                val ts = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && parts[0].isNotBlank() && ts != null) parts[0] to ts else null
            }
            .toMap()
}
