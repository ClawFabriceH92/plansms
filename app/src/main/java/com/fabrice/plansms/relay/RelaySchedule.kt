package com.fabrice.plansms.relay

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Décide si le relais doit transférer maintenant, et sinon quand.
 *
 * Logique volontairement pure (aucun Android, aucune base) : c'est la règle
 * métier la plus facile à se tromper, donc la seule qu'on puisse tester.
 *
 * Règles :
 *  - un jour d'exception prime sur tout : « actif » = toute la journée ouverte,
 *    « inactif » = toute la journée fermée, même si un créneau la couvre ;
 *  - sinon, la journée suit les créneaux récurrents (fin exclue) ;
 *  - un créneau dont la fin est ≤ au début est lu comme allant jusqu'à minuit.
 */
object RelaySchedule {

    /** Vue minimale d'un créneau, indépendante de Room. */
    data class Slot(val daysMask: Int, val startMin: Int, val endMin: Int, val enabled: Boolean = true)

    /** Vue minimale d'une exception : jour calendaire local + sens. */
    data class DayRule(val epochDay: Long, val active: Boolean)

    private const val DAY_MINUTES = 24 * 60
    private const val HORIZON_DAYS = 21

    fun isActive(
        nowMillis: Long,
        slots: List<Slot>,
        exceptions: List<DayRule>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val day = now.toLocalDate()
        forcedFor(day, exceptions)?.let { return it }
        val minute = now.hour * 60 + now.minute
        return slots.any { it.enabled && covers(it, day, minute) }
    }

    /**
     * Prochain instant où le relais redevient actif, ou null si aucun créneau
     * n'est prévu dans les trois semaines à venir. Renvoie [nowMillis] si le
     * relais est déjà actif.
     */
    fun nextActiveAt(
        nowMillis: Long,
        slots: List<Slot>,
        exceptions: List<DayRule>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (isActive(nowMillis, slots, exceptions, zone)) return nowMillis
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val today = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute

        for (offset in 0..HORIZON_DAYS) {
            val day = today.plusDays(offset.toLong())
            val floor = if (offset == 0) nowMinute + 1 else 0
            val start = firstActiveMinute(day, floor, slots, exceptions) ?: continue
            return day.atTime(LocalTime.of(start / 60, start % 60))
                .atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /** Première minute active de [day] à partir de [floor], ou null. */
    private fun firstActiveMinute(
        day: LocalDate,
        floor: Int,
        slots: List<Slot>,
        exceptions: List<DayRule>
    ): Int? {
        if (floor >= DAY_MINUTES) return null
        when (forcedFor(day, exceptions)) {
            false -> return null                       // journée fermée d'office
            true -> return floor                       // journée ouverte d'office
            else -> Unit
        }
        return slots
            .filter { it.enabled && it.daysMask and dayBit(day) != 0 }
            .mapNotNull { slot ->
                val end = endOf(slot)
                when {
                    end <= floor -> null                // créneau déjà passé
                    slot.startMin >= floor -> slot.startMin
                    else -> floor                       // on est déjà dedans
                }
            }
            .minOrNull()
    }

    /** Sens imposé par une exception pour ce jour, ou null s'il n'y en a pas. */
    private fun forcedFor(day: LocalDate, exceptions: List<DayRule>): Boolean? =
        exceptions.firstOrNull { it.epochDay == day.toEpochDay() }?.active

    private fun covers(slot: Slot, day: LocalDate, minute: Int): Boolean =
        slot.daysMask and dayBit(day) != 0 && minute >= slot.startMin && minute < endOf(slot)

    private fun endOf(slot: Slot): Int =
        if (slot.endMin <= slot.startMin) DAY_MINUTES else slot.endMin

    /** bit 0 = lundi … bit 6 = dimanche. */
    private fun dayBit(day: LocalDate): Int = 1 shl (day.dayOfWeek.value - 1)

    // --- Confort d'affichage ---

    private val DAY_LABELS = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")

    fun daysLabel(daysMask: Int): String = when {
        daysMask == 0 -> "aucun jour"
        daysMask == 0b1111111 -> "tous les jours"
        daysMask == 0b0011111 -> "lun–ven"
        daysMask == 0b1100000 -> "week-end"
        else -> DAY_LABELS.filterIndexed { i, _ -> daysMask and (1 shl i) != 0 }.joinToString(" ")
    }

    fun timeLabel(minutes: Int): String =
        "%02dh%02d".format(minutes / 60 % 24, minutes % 60)

    fun slotLabel(slot: Slot): String =
        "${daysLabel(slot.daysMask)} · ${timeLabel(slot.startMin)} → ${timeLabel(endOf(slot))}"
}
