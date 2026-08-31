package com.fabrice.plansms.logic

import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.ScheduledMessage
import java.util.Calendar
import java.util.Locale

/**
 * Règles métier pures (testables sans Android).
 * Toutes les fonctions sont déterministes : mêmes entrées → mêmes sorties.
 */
object SmsRules {

    /** minutes depuis minuit pour une heure donnée. */
    fun minutesOf(cal: Calendar): Int = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    /** 1=Lundi..7=Dimanche (Calendar.MONDAY=2 → décalage). */
    fun dayOfWeek(cal: Calendar): Int {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    fun isInNoSendRange(nowMin: Int, start: Int, end: Int): Boolean {
        if (start < 0 || end < 0) return false
        return if (start <= end) {
            nowMin >= start && nowMin < end
        } else {
            // plage qui chevauche minuit (ex. 22h→7h)
            nowMin >= start || nowMin < end
        }
    }

    /**
     * Prochaine occurrence d'envoi pour un message.
     * @param now epoch millis actuel
     * @param targetDate jour de référence (epoch millis)
     * @return epoch millis de la prochaine exécution, ou null si aucune (ONE-SHOT expiré)
     */
    fun nextOccurrence(msg: ScheduledMessage, now: Long): Long? {
        val cal = Calendar.getInstance()
        cal.timeInMillis = msg.targetDate
        cal.set(Calendar.HOUR_OF_DAY, msg.hourOfDay)
        cal.set(Calendar.MINUTE, msg.minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val nowCal = Calendar.getInstance().apply { timeInMillis = now }

        // One-shot : si la date cible est passée (jour strictement avant aujourd'hui) → expiré
        if (msg.repeatRule == RepeatRule.ONCE) {
            return if (cal.timeInMillis > now) cal.timeInMillis else null
        }

        // Récurrent : avancer jour par jour jusqu'à trouver une occurrence > now (garde 400 jours)
        for (i in 0..400) {
            if (cal.timeInMillis > now) {
                val ok = when (msg.repeatRule) {
                    RepeatRule.WEEKDAYS -> dayOfWeek(cal) <= 5
                    RepeatRule.WEEKLY -> msg.weekDays != 0 && (msg.weekDays and (1 shl (dayOfWeek(cal) - 1))) != 0
                    RepeatRule.MONTHLY -> cal.get(Calendar.DAY_OF_MONTH) == Calendar.getInstance().apply { timeInMillis = msg.targetDate }.get(Calendar.DAY_OF_MONTH)
                    else -> true // DAILY
                }
                if (ok) return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /**
     * Applique la plage d'envoi interdite : si l'heure calculée tombe dans la plage,
     * décaler à la FIN de la plage (noSendEnd) — même jour si futur, sinon lendemain,
     * en respectant la récurrence (jours ouvrés / hebdo / mensuel).
     */
    fun applyNoSendRange(next: Long, msg: ScheduledMessage, now: Long): Long {
        if (msg.noSendStart < 0 || msg.noSendEnd < 0) return next
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        if (!isInNoSendRange(minutesOf(cal), msg.noSendStart, msg.noSendEnd)) return next

        // Décaler à la fin de la plage
        val candidate = Calendar.getInstance().apply {
            timeInMillis = next
            set(Calendar.HOUR_OF_DAY, msg.noSendEnd / 60)
            set(Calendar.MINUTE, msg.noSendEnd % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }

        var guard = 0
        while (guard < 400) {
            val ok = when (msg.repeatRule) {
                RepeatRule.WEEKDAYS -> dayOfWeek(candidate) <= 5
                RepeatRule.WEEKLY -> msg.weekDays != 0 && (msg.weekDays and (1 shl (dayOfWeek(candidate) - 1))) != 0
                RepeatRule.MONTHLY -> candidate.get(Calendar.DAY_OF_MONTH) == Calendar.getInstance().apply { timeInMillis = msg.targetDate }.get(Calendar.DAY_OF_MONTH)
                else -> true
            }
            if (ok && !isInNoSendRange(minutesOf(candidate), msg.noSendStart, msg.noSendEnd)) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return candidate.timeInMillis
    }

    /**
     * Résout les variables d'un modèle :
     * {{prenom}} → premier mot du nom de contact · {{nom}} → nom complet
     * {{jour}} → jour de la semaine (« mercredi ») · {{date}} → JJ/MM/AAAA · {{heure}} → HH:MM
     * La date/heure utilisée est celle passée en paramètre — pour un RDV, celle du RDV.
     */
    fun resolveTemplate(template: String, contactName: String, dateMillis: Long): String {
        var out = template
        val first = contactName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val d = String.format(Locale.FRANCE, "%02d/%02d/%04d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        val h = String.format(Locale.FRANCE, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val weekday = java.text.SimpleDateFormat("EEEE", Locale.FRANCE)
            .format(java.util.Date(dateMillis))
        out = out.replace("{{prenom}}", first)
        out = out.replace("{{nom}}", contactName)
        out = out.replace("{{jour}}", weekday)
        out = out.replace("{{date}}", d)
        out = out.replace("{{heure}}", h)
        return out
    }
}
