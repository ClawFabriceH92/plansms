package com.fabrice.plansms.logic

import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.ScheduledMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SmsRulesTest {

    private fun cal(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    // --- Plage d'envoi ---

    @Test
    fun `plage simple dans l'intervalle`() {
        assertTrue(SmsRules.isInNoSendRange(22 * 60, 22 * 60, 7 * 60))
        assertTrue(SmsRules.isInNoSendRange(3 * 60, 22 * 60, 7 * 60))
        assertFalse(SmsRules.isInNoSendRange(10 * 60, 22 * 60, 7 * 60))
    }

    @Test
    fun `plage désactivée`() {
        assertFalse(SmsRules.isInNoSendRange(10 * 60, -1, -1))
    }

    @Test
    fun `plage sans chevauchement minuit`() {
        assertTrue(SmsRules.isInNoSendRange(13 * 60, 12 * 60, 14 * 60))
        assertFalse(SmsRules.isInNoSendRange(15 * 60, 12 * 60, 14 * 60))
    }

    // --- Occurrences ---

    @Test
    fun `one-shot futur est planifié`() {
        val now = cal(2026, 8, 17, 10, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 18, 9, 0),
            hourOfDay = 9, minuteOfHour = 0, repeatRule = RepeatRule.ONCE
        )
        val next = SmsRules.nextOccurrence(msg, now)
        assertNotNull(next)
        assertEquals(cal(2026, 8, 18, 9, 0), next)
    }

    @Test
    fun `one-shot passé est expiré`() {
        val now = cal(2026, 8, 17, 10, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 16, 9, 0),
            hourOfDay = 9, minuteOfHour = 0, repeatRule = RepeatRule.ONCE
        )
        assertNull(SmsRules.nextOccurrence(msg, now))
    }

    @Test
    fun `quotidien renvoie demain à la même heure`() {
        val now = cal(2026, 8, 17, 10, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 17, 9, 0),
            hourOfDay = 9, minuteOfHour = 0, repeatRule = RepeatRule.DAILY
        )
        val next = SmsRules.nextOccurrence(msg, now)
        assertEquals(cal(2026, 8, 18, 9, 0), next)
    }

    @Test
    fun `quotidien avec heure pas encore passée aujourd'hui`() {
        val now = cal(2026, 8, 17, 8, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 17, 9, 0),
            hourOfDay = 9, minuteOfHour = 0, repeatRule = RepeatRule.DAILY
        )
        assertEquals(cal(2026, 8, 17, 9, 0), SmsRules.nextOccurrence(msg, now))
    }

    @Test
    fun `jours ouvrés saute le week-end`() {
        // Lundi 17/08/2026 → l'occurrence à 9h est passée → mardi 18 (OK) — testons un vendredi passé → lundi
        val now = cal(2026, 8, 14, 10, 0) // vendredi
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 14, 9, 0),
            hourOfDay = 9, minuteOfHour = 0, repeatRule = RepeatRule.WEEKDAYS
        )
        val next = SmsRules.nextOccurrence(msg, now)
        val c = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK)) // lundi
        assertEquals(17, c.get(Calendar.DAY_OF_MONTH)) // 17/08/2026
    }

    // --- Application de la plage interdite ---

    @Test
    fun `heure dans la plage interdite est décalée à la fin de plage`() {
        val now = cal(2026, 8, 17, 8, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 17, 23, 0),
            hourOfDay = 23, minuteOfHour = 0, repeatRule = RepeatRule.DAILY,
            noSendStart = 22 * 60, noSendEnd = 7 * 60
        )
        val next = SmsRules.nextOccurrence(msg, now)!!
        val shifted = SmsRules.applyNoSendRange(next, msg, now)
        // 23h est toujours dans la plage 22h-7h → décalé à la fin de plage (7h) le lendemain
        assertEquals(cal(2026, 8, 18, 7, 0), shifted)
    }

    @Test
    fun `heure dans plage simple décalée à la fin de plage le même jour`() {
        val now = cal(2026, 8, 17, 8, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 17, 13, 0),
            hourOfDay = 13, minuteOfHour = 0, repeatRule = RepeatRule.ONCE,
            noSendStart = 12 * 60, noSendEnd = 14 * 60
        )
        val next = SmsRules.nextOccurrence(msg, now)!!
        val shifted = SmsRules.applyNoSendRange(next, msg, now)
        assertEquals(cal(2026, 8, 17, 14, 0), shifted)
    }

    @Test
    fun `heure hors plage interdite inchangée`() {
        val now = cal(2026, 8, 17, 8, 0)
        val msg = ScheduledMessage(
            phone = "0600000000", text = "Test",
            targetDate = cal(2026, 8, 17, 10, 0),
            hourOfDay = 10, minuteOfHour = 0, repeatRule = RepeatRule.ONCE,
            noSendStart = 22 * 60, noSendEnd = 7 * 60
        )
        val next = SmsRules.nextOccurrence(msg, now)!!
        assertEquals(next, SmsRules.applyNoSendRange(next, msg, now))
    }

    // --- Variables de template ---

    @Test
    fun `variables resolues`() {
        val resolved = SmsRules.resolveTemplate(
            "Bonjour {{prenom}}, RDV le {{date}} à {{heure}}",
            "Jean Dupont",
            cal(2026, 8, 17, 14, 30)
        )
        assertEquals("Bonjour Jean, RDV le 17/08/2026 à 14:30", resolved)
    }

    @Test
    fun `jour de semaine`() {
        val c = Calendar.getInstance().apply { timeInMillis = cal(2026, 8, 17, 12, 0) } // lundi
        assertEquals(1, SmsRules.dayOfWeek(c))
        val sun = Calendar.getInstance().apply { timeInMillis = cal(2026, 8, 23, 12, 0) } // dimanche
        assertEquals(7, SmsRules.dayOfWeek(sun))
    }

    @Test
    fun resolveTemplate_jour_date_heure_du_rdv() {
        // Mercredi 2 septembre 2026, 14h30
        val rdv = cal(2026, 9, 2, 14, 30)
        val out = com.fabrice.plansms.logic.SmsRules.resolveTemplate(
            "RDV {{jour}} {{date}} à {{heure}} avec {{prenom}}", "Fabrice Heuvrard", rdv
        )
        assertEquals("RDV mercredi 02/09/2026 à 14:30 avec Fabrice", out)
    }
}
