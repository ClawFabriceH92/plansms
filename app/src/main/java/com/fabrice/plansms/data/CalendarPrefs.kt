package com.fabrice.plansms.data

import android.content.Context

/**
 * Préférences calendrier de PlanSMS :
 *  - calendriers masqués (ignorés partout dans l'app, indépendant de la visibilité système)
 *  - message de confirmation de RDV (modifiable, mémorisé)
 */
object CalendarPrefs {
    private const val PREFS = "plansms_calendar"
    private const val KEY_HIDDEN = "hidden_calendar_ids"
    private const val KEY_CONFIRM_MSG = "rdv_confirm_message"
    private const val KEY_REMINDER = "rdv_reminder_enabled"

    const val DEFAULT_CONFIRM_MESSAGE =
        "Bonjour, nous avons rendez-vous demain. En cas d'indisponibilité de votre part, merci de me prévenir. Cordialement."

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hiddenIds(context: Context): Set<Long> =
        (prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun setHidden(context: Context, calendarId: Long, hidden: Boolean) {
        val current = (prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()
        if (hidden) current.add(calendarId.toString()) else current.remove(calendarId.toString())
        prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
    }

    fun confirmMessage(context: Context): String =
        prefs(context).getString(KEY_CONFIRM_MSG, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CONFIRM_MESSAGE

    fun setConfirmMessage(context: Context, message: String) {
        prefs(context).edit().putString(KEY_CONFIRM_MSG, message.trim()).apply()
    }

    /** Rappel quotidien 15h (jours ouvrés) pour les RDV du lendemain. Activé par défaut. */
    fun reminderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER, true)

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER, enabled).apply()
    }
}
