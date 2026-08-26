package com.fabrice.plansms.data

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/**
 * Repasse sur le texte transcrit avec ton vocabulaire à toi.
 *
 * Aucun moteur vocal ne connaît « liasse fiscale », « commissaire aux apports »
 * ou le nom de tes clients : il écrit ce qui sonne pareil. On rapproche donc
 * chaque mot (ou groupe de mots) des termes que tu as saisis dans les réglages,
 * et on corrige quand c'est manifestement le même mot mal orthographié.
 *
 * Volontairement prudent : on ne corrige que ce que TU as inscrit au lexique,
 * jamais du vocabulaire deviné, et chaque correction appliquée est affichée.
 */
object TranscriptPolisher {

    data class Result(val text: String, val corrections: List<String>)

    private val WORD = Regex("[\\p{L}\\p{N}'’-]+")

    /** « l'assemblée », « d'affaires » : l'élision ne fait pas partie du terme. */
    private val ELISION = Regex("^([ldnjmtscqu]{1,2}['’])", RegexOption.IGNORE_CASE)

    /** Lexique saisi dans les réglages : un terme par ligne. */
    fun terms(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.length >= 4 }.distinct()

    fun polish(text: String, terms: List<String>): Result {
        if (text.isBlank() || terms.isEmpty()) return Result(text, emptyList())
        val tokens = WORD.findAll(text).toList()
        if (tokens.isEmpty()) return Result(text, emptyList())

        // Les expressions les plus longues d'abord : « commissaire aux comptes »
        // doit primer sur « comptes » seul.
        val prepared = terms
            .map { Triple(it, normalize(it), it.trim().split(Regex("[\\s-]+")).size) }
            .filter { it.second.length >= 4 }
            .sortedByDescending { it.third }

        val corrections = mutableListOf<String>()
        val out = StringBuilder()
        var cursor = 0
        var i = 0

        while (i < tokens.size) {
            var consumed = 0
            for ((term, key, words) in prepared) {
                if (i + words > tokens.size) continue
                val window = tokens.subList(i, i + words)
                val raw = window.map { it.value }.toMutableList()
                var from = window.first().range.first
                val elision = ELISION.find(raw[0])?.value
                if (elision != null && raw[0].length > elision.length) {
                    from += elision.length
                    raw[0] = raw[0].substring(elision.length)
                }
                val candidate = normalize(raw.joinToString(" "))
                if (candidate == key) {
                    consumed = words          // déjà écrit correctement
                    break
                }
                if (isSameWord(candidate, key, words)) {
                    val to = window.last().range.last + 1
                    out.append(text, cursor, from)
                    out.append(term)
                    corrections.add("${text.substring(from, to)} → $term")
                    cursor = to
                    consumed = words
                    break
                }
            }
            i += if (consumed > 0) consumed else 1
        }
        out.append(text, cursor, text.length)
        return Result(out.toString(), corrections)
    }

    /**
     * Même mot mal entendu ?
     *
     * Très strict sur un mot isolé : « prévision » et « provision » ne diffèrent
     * que d'une lettre, on ne veut surtout pas transformer l'un en l'autre à tort.
     * Plus tolérant sur une expression de plusieurs mots, où une collision de ce
     * genre n'arrive pas.
     */
    private fun isSameWord(candidate: String, term: String, words: Int): Boolean {
        if (candidate.isEmpty() || term.length < 4) return false
        if (abs(candidate.length - term.length) > 2) return false
        // La première lettre est presque toujours bien entendue : garde-fou simple
        // contre le remplacement d'un mot courant par un terme du lexique.
        if (candidate.first() != term.first()) return false
        val tolerance = when {
            words == 1 -> if (term.length >= 6) 1 else 0
            term.length < 14 -> 2
            else -> 3
        }
        return distance(candidate, term) in 1..tolerance
    }

    /** Distance de Levenshtein, deux lignes seulement. */
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /** Minuscules, sans accent ni ponctuation, espaces resserrés. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('-', ' ')
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
