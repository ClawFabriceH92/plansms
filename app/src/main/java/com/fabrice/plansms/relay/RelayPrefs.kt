package com.fabrice.plansms.relay

import android.content.Context

/**
 * Réglages du relais SMS.
 *
 * Les identifiants SMTP ne sont PAS dupliqués ici : le relais réutilise le
 * compte d'envoi déjà configuré dans Réglages → Stockage (mot de passe chiffré
 * par l'Android Keystore). Seule la liste des destinataires est propre au relais.
 */
object RelayPrefs {
    private const val PREFS = "plansms_relay"
    private const val K_ENABLED = "enabled"
    private const val K_NUMBERS = "numbers"
    private const val K_EMAILS = "emails"
    private const val K_RETENTION = "retention_days"
    private const val K_ATTEMPTS = "max_attempts"
    private const val K_SELF = "self_number"
    private const val K_RCS = "relay_rcs"
    private const val K_DIGEST = "daily_digest"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Interrupteur général, indépendant des plages horaires. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
    }

    fun numbers(context: Context): List<String> = split(prefs(context).getString(K_NUMBERS, ""))

    fun setNumbers(context: Context, values: List<String>) {
        prefs(context).edit().putString(K_NUMBERS, values.joinToString("\n")).apply()
    }

    fun emails(context: Context): List<String> = split(prefs(context).getString(K_EMAILS, ""))

    fun setEmails(context: Context, values: List<String>) {
        prefs(context).edit().putString(K_EMAILS, values.joinToString("\n")).apply()
    }

    /** Durée de conservation de l'historique, en jours (0 = illimité). */
    fun retentionDays(context: Context): Int = prefs(context).getInt(K_RETENTION, 90)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(K_RETENTION, days.coerceIn(0, 3650)).apply()
    }

    /** Nombre de tentatives d'envoi avant abandon et notification. */
    fun maxAttempts(context: Context): Int = prefs(context).getInt(K_ATTEMPTS, 3)

    fun setMaxAttempts(context: Context, count: Int) {
        prefs(context).edit().putInt(K_ATTEMPTS, count.coerceIn(1, 10)).apply()
    }

    /**
     * Numéro de la SIM de cet appareil, saisi à la main : Android ne le donne
     * pas de façon fiable, et c'est le garde-fou anti-boucle le plus sûr.
     */
    fun selfNumber(context: Context): String = prefs(context).getString(K_SELF, "") ?: ""

    fun setSelfNumber(context: Context, value: String) {
        prefs(context).edit().putString(K_SELF, value.trim()).apply()
    }

    /** Relayer aussi les messages RCS captés par l'accès aux notifications. */
    fun relayRcs(context: Context): Boolean = prefs(context).getBoolean(K_RCS, true)

    fun setRelayRcs(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_RCS, on).apply()
    }

    /** Bilan quotidien vers 19h30 : chien de garde du relais. */
    fun dailyDigest(context: Context): Boolean = prefs(context).getBoolean(K_DIGEST, false)

    fun setDailyDigest(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_DIGEST, on).apply()
    }

    private fun split(raw: String?): List<String> =
        (raw ?: "").split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
