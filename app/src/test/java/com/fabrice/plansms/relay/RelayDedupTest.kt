package com.fabrice.plansms.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dédoublonnage SMS/notification : un vrai SMS passe par les deux canaux,
 * il ne doit être relayé qu'une fois — et deux clients qui envoient le même
 * texte ne doivent PAS être confondus.
 */
class RelayDedupTest {

    private val now = 1_000_000_000L

    private fun seen(sender: String, body: String, ago: Long = 5_000) =
        RelayDedup.Seen(sender, body, now - ago)

    @Test
    fun `meme numero et meme texte dans la fenetre = doublon`() {
        val recent = listOf(seen("+33612345678", "Bonjour, je vous rappelle demain."))
        assertTrue(RelayDedup.isDuplicate("06 12 34 56 78", "Bonjour, je vous rappelle demain.", now, recent))
    }

    @Test
    fun `texte tronque par la notification = doublon quand meme`() {
        val recent = listOf(seen("+33612345678", "Bonjour, je vous rappelle demain sans faute pour le dossier."))
        assertTrue(RelayDedup.isDuplicate("0612345678", "Bonjour, je vous rappelle demain", now, recent))
    }

    @Test
    fun `meme texte mais autre expediteur = pas un doublon`() {
        val recent = listOf(seen("+33612345678", "Oui merci"))
        assertFalse(RelayDedup.isDuplicate("+33698765432", "Oui merci", now, recent))
    }

    @Test
    fun `meme expediteur mais nouveau texte = pas un doublon`() {
        val recent = listOf(seen("+33612345678", "Premier message"))
        assertFalse(RelayDedup.isDuplicate("+33612345678", "Second message", now, recent))
    }

    @Test
    fun `hors fenetre de trois minutes = pas un doublon`() {
        val recent = listOf(seen("+33612345678", "Bonjour", ago = RelayDedup.WINDOW_MS + 1_000))
        assertFalse(RelayDedup.isDuplicate("+33612345678", "Bonjour", now, recent))
    }

    @Test
    fun `expediteur sans numero compare par nom`() {
        val recent = listOf(seen("Cabinet Durand", "RDV confirmé"))
        assertTrue(RelayDedup.isDuplicate("cabinet durand", "RDV confirmé", now, recent))
        assertFalse(RelayDedup.isDuplicate("Cabinet Martin", "RDV confirmé", now, recent))
    }

    @Test
    fun `corps vide jamais doublon`() {
        val recent = listOf(seen("+33612345678", ""))
        assertFalse(RelayDedup.isDuplicate("+33612345678", "", now, recent))
    }
}
