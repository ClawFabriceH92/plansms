package com.fabrice.plansms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Extraction d'un numéro « SMS-able » depuis le texte d'un événement :
 * mobiles trouvés sous toutes leurs écritures, fixes/dates/heures écartés.
 */
class ExtractSmsCapableTest {

    private fun x(vararg texts: String?) = CallLogRepository.extractSmsCapable(*texts)

    @Test
    fun `mobile dans le titre, toutes ecritures`() {
        assertEquals("0612345678", x("RDV M. Dupont 06 12 34 56 78"))
        assertEquals("0612345678", x("RDV Dupont 06.12.34.56.78 bilan"))
        assertEquals("0612345678", x("Dupont +33 6 12 34 56 78"))
        assertEquals("0712345678", x("Point tél 0712345678"))
    }

    @Test
    fun `numero etranger accepte`() {
        assertEquals("+41791234567", x("Call M. Weber +41 79 123 45 67"))
    }

    @Test
    fun `fixe ecarte`() {
        assertNull(x("Standard 01 23 45 67 89"))
        assertNull(x("Ligne 09 70 00 00 00"))
    }

    @Test
    fun `dates et heures jamais confondues`() {
        assertNull(x("Bilan 06/12/2026 à 09:30"))
        assertNull(x("Clôture au 31/12/2026"))
        assertNull(x("Réunion 09 30 - 10 30"))
    }

    @Test
    fun `priorite au premier texte fourni`() {
        assertEquals("0612345678", x("Titre 0612345678", "Lieu 0798765432"))
        assertEquals("0798765432", x("Titre sans numéro", "Lieu 07 98 76 54 32"))
        assertNull(x(null, "", "rien ici"))
    }
}
