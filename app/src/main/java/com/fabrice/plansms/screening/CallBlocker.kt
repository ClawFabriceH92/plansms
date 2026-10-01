package com.fabrice.plansms.screening

import android.app.role.RoleManager
import android.content.Context
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.CallLogRepository
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.util.AppLogger

/**
 * Blocage d'appels par préfixe : les numéros commençant par un préfixe de la
 * liste sont rejetés avant même de sonner, via le rôle Android « application
 * de filtrage d'appels » (le même que les apps anti-spam).
 *
 * Par défaut : 0162, un préfixe que l'ARCEP réserve aux plateformes de
 * démarchage téléphonique — aucun particulier ni client n'appelle depuis un
 * 0162. La liste complète ARCEP est proposée en un bouton dans Réglages.
 *
 * Chaque appel bloqué est tracé dans Journal → Envois (APPEL BLOQUÉ).
 */
object CallBlocker {

    private const val PREFS = "plansms_call_blocker"
    private const val K_ENABLED = "enabled"
    private const val K_PREFIXES = "prefixes"

    const val DEFAULT_PREFIXES = "0162"

    /** Préfixes réservés au démarchage téléphonique (ARCEP, métropole, 2023). */
    val ARCEP_PREFIXES = listOf(
        "0162", "0163", "0270", "0271", "0377", "0378",
        "0424", "0425", "0568", "0569", "0948", "0949"
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
    }

    /** Préfixes bruts tels que saisis (un par ligne ou séparés par des virgules). */
    fun prefixesRaw(context: Context): String =
        prefs(context).getString(K_PREFIXES, DEFAULT_PREFIXES) ?: DEFAULT_PREFIXES

    fun setPrefixesRaw(context: Context, raw: String) {
        prefs(context).edit().putString(K_PREFIXES, raw.trim()).apply()
    }

    fun prefixes(context: Context): List<String> =
        prefixesRaw(context).split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** PlanSMS tient-il le rôle Android de filtrage d'appels ? */
    fun hasRole(context: Context): Boolean = try {
        val rm = context.getSystemService(RoleManager::class.java)
        rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    } catch (_: Exception) {
        false
    }

    /**
     * Décision pure, testée unitairement : ce numéro doit-il être bloqué ?
     * Le numéro et les préfixes sont normalisés (+33 1 62… = 0162…), donc
     * toutes les écritures d'un même numéro sont traitées pareil.
     */
    fun shouldBlock(number: String, prefixes: List<String>): Boolean {
        if (number.isBlank()) return false
        val n = CallLogRepository.normalize(number)
        if (n.isBlank()) return false
        return prefixes.any { raw ->
            val p = CallLogRepository.normalize(raw)
            p.length >= 3 && n.startsWith(p)
        }
    }

    /** Trace l'appel bloqué dans Journal → Envois. */
    suspend fun logBlocked(context: Context, number: String) {
        try {
            AppDatabase.get(context).sendLogDao().insert(
                SendLog(
                    scheduledId = 0,
                    phone = number,
                    textPreview = "APPEL BLOQUÉ — préfixe de démarchage",
                    status = "BLOCKED",
                    sentAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            AppLogger.e("CallBlocker", "Journalisation du blocage impossible", e)
        }
    }
}
