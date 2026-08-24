package com.fabrice.plansms.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/** Un appel du journal d'appels du téléphone (lecture seule). */
data class CallEntry(
    val number: String,
    val name: String,          // nom du contact si connu, sinon vide
    val type: Int,             // CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE / REJECTED_TYPE
    val date: Long,            // epoch millis de l'appel (le plus récent si regroupé)
    val count: Int = 1,        // nombre d'appels regroupés pour ce numéro
    val lastSmsAt: Long = 0,   // date du dernier SMS reçu de ce numéro (0 = aucun)
    val lastSmsPreview: String = ""
) {
    /** Le correspondant a écrit APRÈS son dernier appel → ne pas le relancer. */
    val hasRepliedBySms: Boolean get() = lastSmsAt > date

    /** Un SMS existe, mais il est antérieur au dernier appel (simple information). */
    val hasEarlierSms: Boolean get() = lastSmsAt in 1 until date
    val isMissed: Boolean get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
    val isIncoming: Boolean get() = type == CallLog.Calls.INCOMING_TYPE
    val isOutgoing: Boolean get() = type == CallLog.Calls.OUTGOING_TYPE
}

/** Lecture du journal d'appels système (CallLog.Calls) — rien n'est modifié. */
object CallLogRepository {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Derniers appels (tous types), du plus récent au plus ancien. */
    fun readRecentCalls(context: Context, limit: Int = 300): List<CallEntry> {
        if (!hasPermission(context)) return emptyList()
        val list = mutableListOf<CallEntry>()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                val iNumber = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iType = it.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = it.getColumnIndex(CallLog.Calls.DATE)
                while (it.moveToNext() && list.size < limit) {
                    val number = (if (iNumber >= 0) it.getString(iNumber) else null)?.trim() ?: continue
                    if (number.isBlank()) continue      // numéro masqué → pas de SMS possible
                    list.add(
                        CallEntry(
                            number = number,
                            name = (if (iName >= 0) it.getString(iName) else null) ?: "",
                            type = if (iType >= 0) it.getInt(iType) else CallLog.Calls.INCOMING_TYPE,
                            date = if (iDate >= 0) it.getLong(iDate) else 0L
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // journal inaccessible → liste vide, l'UI affiche le message adapté
        }
        return list
    }

    /**
     * Regroupe une liste d'appels par numéro : un seul élément par numéro,
     * avec la date/type de l'appel le plus récent et le nombre total d'appels.
     */
    fun groupByNumber(calls: List<CallEntry>): List<CallEntry> {
        val byNumber = LinkedHashMap<String, CallEntry>()
        for (call in calls) {   // déjà trié du plus récent au plus ancien
            val key = normalize(call.number)
            val existing = byNumber[key]
            if (existing == null) {
                byNumber[key] = call
            } else {
                byNumber[key] = existing.copy(
                    count = existing.count + 1,
                    name = existing.name.ifBlank { call.name }
                )   // existing = appel le plus récent : son marquage SMS fait foi
            }
        }
        return byNumber.values.toList()
    }

    /**
     * Forme canonique : les numéros français en 0X XX XX XX XX, les étrangers en +NN…
     * Permet de regrouper 06…, +336… et 00336… sous la même clé.
     */
    fun normalize(number: String): String {
        var digits = number.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("0033")) digits = "+33" + digits.drop(4)
        if (digits.startsWith("00") && digits.length > 4) digits = "+" + digits.drop(2)
        if (digits.startsWith("+33")) digits = "0" + digits.drop(3)
        return digits
    }

    /** Nature d'un numéro, pour savoir s'il peut recevoir un SMS. */
    enum class NumberKind { MOBILE_FR, FOREIGN, LANDLINE_FR, SERVICE }

    fun kindOf(number: String): NumberKind {
        val n = normalize(number)
        return when {
            n.startsWith("+") -> NumberKind.FOREIGN
            n.length == 10 && n.startsWith("0") ->
                if (n[1] == '6' || n[1] == '7') NumberKind.MOBILE_FR else NumberKind.LANDLINE_FR
            else -> NumberKind.SERVICE   // numéro court, standard, numéro masqué…
        }
    }

    /**
     * Numéros susceptibles de recevoir un SMS : mobiles français (06/07) et
     * tous les numéros étrangers (impossible d'y distinguer fixe et mobile de façon fiable).
     * Exclut les fixes français (01-05), les 08/09 et les numéros courts.
     */
    fun canReceiveSms(number: String): Boolean = when (kindOf(number)) {
        NumberKind.MOBILE_FR, NumberKind.FOREIGN -> true
        else -> false
    }

    /** Libellé court de la nature du numéro (affiché sur les numéros écartés). */
    fun kindLabel(number: String): String = when (kindOf(number)) {
        NumberKind.MOBILE_FR -> "mobile"
        NumberKind.FOREIGN -> "étranger"
        NumberKind.LANDLINE_FR -> "fixe"
        NumberKind.SERVICE -> "n° court / service"
    }

    fun hasSmsReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Clé de rapprochement tolérante : les 9 derniers chiffres.
     * 0681371545, +33681371545 et 0033681371545 donnent tous « 681371545 »,
     * ce qui évite tout écart de préfixe entre le journal d'appels et les SMS.
     */
    fun matchKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 9) digits.takeLast(9) else digits
    }

    /** Résultat d'une lecture du journal enrichie des réponses SMS. */
    data class CallLogScan(
        val calls: List<CallEntry>,
        val smsScanned: Int         // nombre de SMS reçus analysés (-1 = permission absente)
    )

    /**
     * Derniers SMS REÇUS, par numéro : clé = 9 derniers chiffres,
     * valeur = (date du plus récent, extrait). On interroge la table complète
     * en filtrant sur le type « reçu » — plus fiable que la vue /inbox selon les surcouches.
     */
    private fun inboundSmsByNumber(context: Context, since: Long): Pair<Map<String, Pair<Long, String>>, Int> {
        if (!hasSmsReadPermission(context)) return emptyMap<String, Pair<Long, String>>() to -1
        val out = HashMap<String, Pair<Long, String>>()
        var scanned = 0
        try {
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.BODY
                ),
                android.provider.Telephony.Sms.TYPE + " = ? AND " +
                    android.provider.Telephony.Sms.DATE + " > ?",
                arrayOf(
                    android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                    since.toString()
                ),
                android.provider.Telephony.Sms.DATE + " DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    scanned++
                    val address = it.getString(0)?.trim() ?: continue
                    if (address.isBlank()) continue
                    val key = matchKey(address)
                    if (key.isEmpty()) continue
                    val date = it.getLong(1)
                    val previous = out[key]
                    if (previous == null || date > previous.first) {
                        out[key] = date to (it.getString(2) ?: "")
                    }
                }
            }
        } catch (_: Exception) {
            // provider inaccessible (surcouche constructeur) → 0 SMS lus, l'UI le signale
        }
        return out to scanned
    }

    /**
     * Attache à chaque appel la date du dernier SMS reçu de ce numéro.
     * La comparaison « SMS postérieur à l'appel ? » est faite à l'affichage, APRÈS
     * le regroupement par numéro : sinon le marquage porté par un vieil appel serait
     * perdu au profit du plus récent, et l'inverse produirait des alertes fantômes.
     */
    fun markSmsReplies(context: Context, calls: List<CallEntry>): CallLogScan {
        if (calls.isEmpty()) return CallLogScan(calls, if (hasSmsReadPermission(context)) 0 else -1)
        val (inbox, scanned) = inboundSmsByNumber(context, calls.minOf { it.date })
        if (inbox.isEmpty()) return CallLogScan(calls, scanned)
        val marked = calls.map { call ->
            val hit = inbox[matchKey(call.number)]
            if (hit != null) call.copy(lastSmsAt = hit.first, lastSmsPreview = hit.second.take(70)) else call
        }
        return CallLogScan(marked, scanned)
    }

    /** Rapport de diagnostic pour un numéro : ce que l'app voit réellement. */
    fun diagnosticReport(context: Context, rawNumber: String): String {
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE)
        val key = matchKey(rawNumber)
        val sb = StringBuilder()
        sb.append("=== Diagnostic PlanSMS ===\n")
        sb.append("Numéro saisi : ").append(rawNumber).append("\n")
        sb.append("Clé de rapprochement (9 derniers chiffres) : ").append(key).append("\n")
        sb.append("Nature : ").append(kindLabel(rawNumber)).append("\n")
        sb.append("Permission journal d'appels : ").append(if (hasPermission(context)) "OUI" else "NON").append("\n")
        sb.append("Permission lecture SMS : ").append(if (hasSmsReadPermission(context)) "OUI" else "NON").append("\n\n")

        if (key.isEmpty()) return sb.append("Numéro vide ou invalide.").toString()

        sb.append("--- Appels de ce numéro ---\n")
        val calls = readRecentCalls(context, limit = 500).filter { matchKey(it.number) == key }
        if (calls.isEmpty()) sb.append("(aucun appel trouvé)\n")
        calls.take(10).forEach { c ->
            val type = when {
                c.isMissed -> "manqué"
                c.isIncoming -> "reçu"
                c.isOutgoing -> "émis"
                else -> "autre"
            }
            sb.append(fmt.format(java.util.Date(c.date))).append("  ").append(type)
                .append("  [brut: ").append(c.number).append("]\n")
        }

        sb.append("\n--- SMS REÇUS de ce numéro ---\n")
        var smsCount = 0
        try {
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.TYPE
                ),
                null, null,
                android.provider.Telephony.Sms.DATE + " DESC"
            )
            cursor?.use {
                while (it.moveToNext() && smsCount < 10) {
                    val address = it.getString(0) ?: continue
                    if (matchKey(address) != key) continue
                    smsCount++
                    val typeLabel = if (it.getInt(3) == android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX)
                        "REÇU" else "envoyé/autre (type ${it.getInt(3)})"
                    sb.append(fmt.format(java.util.Date(it.getLong(1)))).append("  ").append(typeLabel)
                        .append("  [brut: ").append(address).append("]\n")
                        .append("    « ").append((it.getString(2) ?: "").take(50)).append(" »\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Lecture impossible : ").append(e.message).append("\n")
        }
        if (smsCount == 0) {
            sb.append("(aucun message trouvé pour ce numéro dans la base SMS)\n")
            sb.append("→ probablement un message RCS/chat : invisible pour toute app tierce.\n")
        }

        sb.append("\n--- Verdict ---\n")
        val lastCall = calls.maxOfOrNull { it.date } ?: 0L
        val (inbox, scanned) = inboundSmsByNumber(context, 0L)
        val lastSms = inbox[key]?.first ?: 0L
        sb.append("Total SMS reçus analysés : ").append(scanned).append("\n")
        sb.append("Dernier appel : ").append(if (lastCall > 0) fmt.format(java.util.Date(lastCall)) else "aucun").append("\n")
        sb.append("Dernier SMS  : ").append(if (lastSms > 0) fmt.format(java.util.Date(lastSms)) else "aucun").append("\n")
        sb.append(
            when {
                lastSms == 0L -> "→ aucun SMS de ce numéro : pas d'alerte possible."
                lastSms > lastCall -> "→ SMS POSTÉRIEUR à l'appel : l'alerte doit s'afficher."
                else -> "→ SMS antérieur au dernier appel : affiché en information, sans alerte."
            }
        )
        return sb.toString()
    }
}
