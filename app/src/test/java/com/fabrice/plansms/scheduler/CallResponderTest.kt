package com.fabrice.plansms.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Répondeur SMS : la décision d'envoi, avec ses garde-fous — mobiles seulement,
 * appels non décrochés par défaut, jamais deux réponses en moins de 4 h.
 */
class CallResponderTest {

    private val now = 10_000_000_000L

    private fun decide(
        enabled: Boolean = true,
        mode: String = CallResponder.MODE_MISSED,
        answered: Boolean = false,
        number: String = "06 12 34 56 78",
        lastSentAt: Long = 0
    ) = CallResponder.shouldReply(enabled, mode, answered, number, lastSentAt, now)

    @Test
    fun `appel manque d'un mobile = reponse`() {
        assertTrue(decide())
        assertTrue(decide(number = "+33 7 98 76 54 32"))
        assertTrue(decide(number = "+41 79 123 45 67"))   // étranger : on ne peut pas trier
    }

    @Test
    fun `desactive = jamais de reponse`() {
        assertFalse(decide(enabled = false))
    }

    @Test
    fun `fixe, numero court ou masque = jamais de reponse`() {
        assertFalse(decide(number = "01 23 45 67 89"))    // fixe
        assertFalse(decide(number = "09 70 00 00 00"))    // fixe VoIP
        assertFalse(decide(number = "3244"))              // numéro court
        assertFalse(decide(number = ""))                  // masqué
    }

    @Test
    fun `appel decroche = pas de reponse en mode manques, reponse en mode tous`() {
        assertFalse(decide(answered = true))
        assertTrue(decide(answered = true, mode = CallResponder.MODE_ALL))
    }

    @Test
    fun `un meme numero pas deux fois en moins de 4 heures`() {
        assertFalse(decide(lastSentAt = now - CallResponder.COOLDOWN_MS + 60_000))
        assertTrue(decide(lastSentAt = now - CallResponder.COOLDOWN_MS - 60_000))
    }
}
