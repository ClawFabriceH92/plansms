package com.fabrice.plansms.relay

import com.fabrice.plansms.data.RelayItem
import com.fabrice.plansms.data.RelayStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDigestTest {

    private fun item(status: String, sender: String = "+33612345678", detail: String = "") =
        RelayItem(sender = sender, body = "texte", receivedAt = 0, status = status, detail = detail)

    @Test
    fun `journee sans probleme = sujet neutre et compteurs`() {
        val digest = RelayDigest.compose(
            "mardi 1 septembre",
            listOf(item(RelayStatus.SENT), item(RelayStatus.SENT)),
            queuedNow = 1
        )
        assertTrue(digest.subject.contains("2 transféré(s)"))
        assertTrue(digest.body.contains("Reçus aujourd'hui : 2"))
        assertTrue(digest.body.contains("En file d'attente : 1"))
        assertFalse(digest.body.contains("À vérifier"))
    }

    @Test
    fun `echec = sujet alarmant et liste a verifier`() {
        val digest = RelayDigest.compose(
            "mardi 1 septembre",
            listOf(
                item(RelayStatus.SENT),
                item(RelayStatus.FAILED, sender = "+33698765432", detail = "SMTP injoignable")
            ),
            queuedNow = 0
        )
        assertTrue(digest.subject.contains("1 échec(s)"))
        assertTrue(digest.body.contains("À vérifier"))
        assertTrue(digest.body.contains("+33698765432"))
        assertTrue(digest.body.contains("SMTP injoignable"))
    }

    @Test
    fun `journee vide = bilan quand meme (chien de garde)`() {
        val digest = RelayDigest.compose("mardi 1 septembre", emptyList(), queuedNow = 0)
        assertTrue(digest.body.contains("Reçus aujourd'hui : 0"))
        assertTrue(digest.body.contains("confirme que le relais fonctionne"))
    }
}
