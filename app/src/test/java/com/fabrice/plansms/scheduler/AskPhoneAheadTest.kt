package com.fabrice.plansms.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demande automatique de numéro 48 h avant le RDV : la décision et ses garde-fous. */
class AskPhoneAheadTest {

    private val now = 20_000_000_000L
    private val in24h = now + 24 * 3600_000L

    private fun decide(
        enabled: Boolean = true,
        smtpReady: Boolean = true,
        start: Long = in24h,
        eventAskedAt: Long = 0,
        emailAskedAt: Long = 0
    ) = AskPhoneAhead.shouldAsk(enabled, smtpReady, start, now, eventAskedAt, emailAskedAt)

    @Test
    fun `rdv dans la fenetre de 48h = demande envoyee`() {
        assertTrue(decide())
        assertTrue(decide(start = now + AskPhoneAhead.WINDOW_MS - 60_000))
    }

    @Test
    fun `rdv passe ou au-dela de 48h = rien`() {
        assertFalse(decide(start = now - 60_000))
        assertFalse(decide(start = now + AskPhoneAhead.WINDOW_MS + 60_000))
    }

    @Test
    fun `desactive ou sans smtp = rien`() {
        assertFalse(decide(enabled = false))
        assertFalse(decide(smtpReady = false))
    }

    @Test
    fun `un seul email par rdv`() {
        assertFalse(decide(eventAskedAt = now - 3600_000))
    }

    @Test
    fun `une meme adresse au plus une fois par semaine`() {
        assertFalse(decide(emailAskedAt = now - AskPhoneAhead.EMAIL_COOLDOWN_MS + 3600_000))
        assertTrue(decide(emailAskedAt = now - AskPhoneAhead.EMAIL_COOLDOWN_MS - 3600_000))
    }
}
