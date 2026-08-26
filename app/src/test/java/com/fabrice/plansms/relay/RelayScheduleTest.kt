package com.fabrice.plansms.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Les plages du relais : la règle la plus facile à se tromper, donc celle
 * qu'on vérifie. Semaine de référence : 2026-09-01 (mardi) → 2026-09-07 (lundi).
 */
class RelayScheduleTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    /** lun–ven, 8h00 → 19h00 */
    private val officeHours = listOf(RelaySchedule.Slot(0b0011111, 8 * 60, 19 * 60))

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(paris).toInstant().toEpochMilli()

    private fun dayOf(day: Int): Long = LocalDate.of(2026, 9, day).toEpochDay()

    private fun active(millis: Long, exceptions: List<RelaySchedule.DayRule> = emptyList()) =
        RelaySchedule.isActive(millis, officeHours, exceptions, paris)

    private fun next(millis: Long, exceptions: List<RelaySchedule.DayRule> = emptyList()) =
        RelaySchedule.nextActiveAt(millis, officeHours, exceptions, paris)

    @Test
    fun `dans le creneau, le relais est actif`() {
        assertTrue(active(at(2, 10)))          // mercredi 10h
        assertTrue(active(at(2, 8)))           // début inclus
        assertFalse(active(at(2, 19)))         // fin exclue
        assertFalse(active(at(2, 7, 59)))
    }

    @Test
    fun `le week-end est hors plage`() {
        assertFalse(active(at(5, 10)))         // samedi
        assertFalse(active(at(6, 10)))         // dimanche
    }

    @Test
    fun `hors creneau, la prochaine ouverture est le jour ouvre suivant`() {
        // Mercredi 20h → jeudi 8h
        assertEquals(at(3, 8), next(at(2, 20)))
        // Samedi 10h → lundi 8h
        assertEquals(at(7, 8), next(at(5, 10)))
        // Mercredi 7h → le jour même à 8h
        assertEquals(at(2, 8), next(at(2, 7)))
    }

    @Test
    fun `deja actif, la prochaine ouverture est maintenant`() {
        val now = at(2, 10)
        assertEquals(now, next(now))
    }

    @Test
    fun `une exception fermee prime sur le creneau`() {
        val closed = listOf(RelaySchedule.DayRule(dayOf(3), active = false))  // jeudi fermé
        assertFalse(active(at(3, 10), closed))
        assertEquals(at(4, 8), next(at(3, 10), closed))                      // → vendredi 8h
    }

    @Test
    fun `une exception ouverte ouvre toute la journee`() {
        val open = listOf(RelaySchedule.DayRule(dayOf(6), active = true))    // dimanche ouvert
        assertTrue(active(at(6, 3), open))
        assertTrue(active(at(6, 23, 30), open))
        // Depuis samedi soir, la prochaine ouverture est le dimanche à minuit
        assertEquals(at(6, 0), next(at(5, 20), open))
    }

    @Test
    fun `sans aucun creneau, rien ne s'ouvre`() {
        assertFalse(RelaySchedule.isActive(at(2, 10), emptyList(), emptyList(), paris))
        assertNull(RelaySchedule.nextActiveAt(at(2, 10), emptyList(), emptyList(), paris))
    }

    @Test
    fun `un creneau desactive ne compte pas`() {
        val off = listOf(RelaySchedule.Slot(0b0011111, 8 * 60, 19 * 60, enabled = false))
        assertFalse(RelaySchedule.isActive(at(2, 10), off, emptyList(), paris))
    }

    @Test
    fun `une fin avant le debut court jusqu'a minuit`() {
        val evening = listOf(RelaySchedule.Slot(0b1111111, 22 * 60, 2 * 60))
        assertTrue(RelaySchedule.isActive(at(2, 23), evening, emptyList(), paris))
        assertFalse(RelaySchedule.isActive(at(2, 21), evening, emptyList(), paris))
    }

    @Test
    fun `les libelles restent lisibles`() {
        assertEquals("lun–ven", RelaySchedule.daysLabel(0b0011111))
        assertEquals("tous les jours", RelaySchedule.daysLabel(0b1111111))
        assertEquals("week-end", RelaySchedule.daysLabel(0b1100000))
        assertEquals("08h00", RelaySchedule.timeLabel(8 * 60))
        assertEquals("00h00", RelaySchedule.timeLabel(24 * 60))
    }
}
