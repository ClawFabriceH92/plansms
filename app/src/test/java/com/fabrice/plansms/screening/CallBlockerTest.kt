package com.fabrice.plansms.screening

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocage par préfixe : toutes les écritures d'un même numéro sont traitées
 * pareil, et un préfixe trop court ne peut pas bloquer la terre entière.
 */
class CallBlockerTest {

    private val p0162 = listOf("0162")

    @Test
    fun `0162 bloque sous toutes ses ecritures`() {
        assertTrue(CallBlocker.shouldBlock("0162999999", p0162))
        assertTrue(CallBlocker.shouldBlock("01 62 99 88 77", p0162))
        assertTrue(CallBlocker.shouldBlock("+33 1 62 12 34 56", p0162))
        assertTrue(CallBlocker.shouldBlock("0033162123456", p0162))
    }

    @Test
    fun `prefixe saisi avec espaces ou indicatif`() {
        assertTrue(CallBlocker.shouldBlock("0162999999", listOf("01 62")))
        assertTrue(CallBlocker.shouldBlock("0162999999", listOf("+33162")))
    }

    @Test
    fun `les autres numeros passent`() {
        assertFalse(CallBlocker.shouldBlock("0163999999", p0162))   // préfixe voisin
        assertFalse(CallBlocker.shouldBlock("0612345678", p0162))   // mobile
        assertFalse(CallBlocker.shouldBlock("0116299999", p0162))   // 0162 pas en tête
        assertFalse(CallBlocker.shouldBlock("", p0162))             // masqué
    }

    @Test
    fun `prefixe trop court ignore - pas de blocage massif par erreur`() {
        assertFalse(CallBlocker.shouldBlock("0612345678", listOf("06")))
        assertFalse(CallBlocker.shouldBlock("0162999999", listOf("0")))
    }

    @Test
    fun `liste arcep complete`() {
        val arcep = CallBlocker.ARCEP_PREFIXES
        assertTrue(CallBlocker.shouldBlock("0270123456", arcep))
        assertTrue(CallBlocker.shouldBlock("+33948123456", arcep))
        assertFalse(CallBlocker.shouldBlock("0612345678", arcep))
    }
}
