package com.fabrice.plansms.relay

import com.fabrice.plansms.data.RelayItem
import com.fabrice.plansms.data.RelayStatus

/**
 * Bilan quotidien du relais : son arrivée chaque soir prouve que le relais est
 * vivant — c'est autant un chien de garde qu'un récapitulatif.
 *
 * Composition pure, sans Android : testée unitairement.
 */
object RelayDigest {

    data class Summary(val subject: String, val body: String)

    fun compose(dayLabel: String, today: List<RelayItem>, queuedNow: Int): Summary {
        val sent = today.count { it.status == RelayStatus.SENT }
        val partial = today.count { it.status == RelayStatus.PARTIAL }
        val failed = today.count { it.status == RelayStatus.FAILED }
        val subject = when {
            failed + partial > 0 -> "[SMS Relay] Bilan du $dayLabel — ${failed + partial} échec(s)"
            else -> "[SMS Relay] Bilan du $dayLabel — $sent transféré(s)"
        }
        val body = buildString {
            appendLine("Relais SMS — bilan du $dayLabel")
            appendLine()
            appendLine("Reçus aujourd'hui : ${today.size}")
            appendLine("Transférés : $sent")
            if (partial > 0) appendLine("Partiels : $partial")
            if (failed > 0) appendLine("Échecs : $failed")
            appendLine("En file d'attente : $queuedNow")
            val problems = today.filter { it.status == RelayStatus.FAILED || it.status == RelayStatus.PARTIAL }
            if (problems.isNotEmpty()) {
                appendLine()
                appendLine("À vérifier :")
                problems.take(10).forEach {
                    appendLine("- ${it.sender} : ${it.detail.ifBlank { "échec" }}")
                }
            }
            appendLine()
            append("Ce message quotidien confirme que le relais fonctionne.")
        }
        return Summary(subject, body)
    }
}
