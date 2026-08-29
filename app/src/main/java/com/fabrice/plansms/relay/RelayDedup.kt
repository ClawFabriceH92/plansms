package com.fabrice.plansms.relay

/**
 * Dédoublonnage entre les deux sources du relais.
 *
 * Un vrai SMS déclenche À LA FOIS le broadcast SMS_RECEIVED et une notification
 * de l'app Messages : sans garde-fou, il serait relayé deux fois. Un RCS, lui,
 * n'existe que côté notification. On considère donc qu'une capture de
 * notification est un doublon si un message du même correspondant, au texte
 * identique (ou contenant/contenu, une notification pouvant tronquer), est déjà
 * passé dans la fenêtre récente.
 *
 * Logique pure, sans Android : testée unitairement.
 */
object RelayDedup {

    /** Fenêtre pendant laquelle deux messages identiques sont le même message. */
    const val WINDOW_MS = 3 * 60_000L

    data class Seen(val sender: String, val body: String, val receivedAt: Long)

    fun isDuplicate(sender: String, body: String, at: Long, recent: List<Seen>): Boolean =
        recent.any { seen ->
            at - seen.receivedAt in 0..WINDOW_MS &&
                sameSender(sender, seen.sender) &&
                sameBody(body, seen.body)
        }

    /** Même correspondant : 9 derniers chiffres, ou nom identique s'il n'y a pas de numéro. */
    fun sameSender(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }.takeLast(9)
        val y = b.filter { it.isDigit() }.takeLast(9)
        if (x.length >= 9 && y.length >= 9) return x == y
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    /** Même texte : égalité, ou l'un contient l'autre (notification tronquée). */
    private fun sameBody(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }
}
