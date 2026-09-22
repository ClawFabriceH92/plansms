package com.fabrice.plansms.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envoi automatique des confirmations de RDV : uniquement les numéros écrits
 * dans l'événement, une seule fois, jamais sans opt-in.
 */
class AutoConfirmTest {

    private fun decide(
        enabled: Boolean = true,
        phoneFromEvent: Boolean = true,
        phone: String = "0612345678",
        alreadySentAt: Long = 0
    ) = AutoConfirm.shouldSend(enabled, phoneFromEvent, phone, alreadySentAt)

    @Test
    fun `numero de l'evenement = envoi automatique`() {
        assertTrue(decide())
    }

    @Test
    fun `option desactivee = jamais`() {
        assertFalse(decide(enabled = false))
    }

    @Test
    fun `numero venant d'un contact ou d'un rapprochement = validation manuelle`() {
        assertFalse(decide(phoneFromEvent = false))
    }

    @Test
    fun `deja confirme = pas de second envoi`() {
        assertFalse(decide(alreadySentAt = 123L))
    }

    @Test
    fun `sans numero = rien`() {
        assertFalse(decide(phone = ""))
    }
}
