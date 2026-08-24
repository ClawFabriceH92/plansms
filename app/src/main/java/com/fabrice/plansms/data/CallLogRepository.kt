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
    val lastSmsPreview: String = "",
    // Agrégats calculés par numéro sur l'ensemble du journal (voir groupByNumber)
    val lastMissedAt: Long = 0,
    val lastOutgoingAt: Long = 0,
    val lastIncomingAt: Long = 0
) {
    /**
     * Suite donnée à un appel manqué : rappel de ma part, SMS reçu du
     * correspondant, ou nouvel appel de sa part que j'ai décroché.
     */
    val followUpAt: Long get() = maxOf(lastOutgoingAt, lastSmsAt, lastIncomingAt)

    /** Appel manqué auquel une suite a déjà été donnée → inutile d'envoyer un SMS. */
    val isHandled: Boolean get() = lastMissedAt > 0 && followUpAt > lastMissedAt

    /** Appel manqué resté sans suite → c'est celui-là qu'il faut traiter. */
    val needsFollowUp: Boolean get() = lastMissedAt > 0 && followUpAt <= lastMissedAt

    /** Motif du classement « déjà traité ». */
    val handledReason: String get() = when {
        !isHandled -> ""
        lastSmsAt == followUpAt -> "SMS reçu"
        lastOutgoingAt == followUpAt -> "tu as rappelé"
        else -> "appel repris"
    }

    /** Un SMS existe sans qu'il y ait d'appel manqué en attente (simple information). */
    val hasEarlierSms: Boolean get() = lastSmsAt > 0 && !isHandled
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
            // La base garde l'appel le plus récent ; les agrégats couvrent tous les appels du numéro
            val base = byNumber[key] ?: call.copy(count = 0)
            byNumber[key] = base.copy(
                count = base.count + 1,
                name = base.name.ifBlank { call.name },
                lastSmsAt = maxOf(base.lastSmsAt, call.lastSmsAt),
                lastSmsPreview = base.lastSmsPreview.ifBlank { call.lastSmsPreview },
                lastMissedAt = maxOf(base.lastMissedAt, if (call.isMissed) call.date else 0L),
                lastOutgoingAt = maxOf(base.lastOutgoingAt, if (call.isOutgoing) call.date else 0L),
                lastIncomingAt = maxOf(base.lastIncomingAt, if (call.isIncoming) call.date else 0L)
            )
        }
        return byNumber.values.toList()
    }

    /** Reporte les agrégats calculés sur TOUT le journal sur une liste filtrée. */
    fun applyFollowUp(filtered: List<CallEntry>, aggregates: Map<String, CallEntry>): List<CallEntry> =
        filtered.map { entry ->
            val agg = aggregates[normalize(entry.number)] ?: return@map entry
            entry.copy(
                lastSmsAt = agg.lastSmsAt,
                lastSmsPreview = agg.lastSmsPreview,
                lastMissedAt = agg.lastMissedAt,
                lastOutgoingAt = agg.lastOutgoingAt,
                lastIncomingAt = agg.lastIncomingAt
            )
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

        fun record(address: String?, date: Long, body: String) {
            scanned++
            val addr = address?.trim() ?: return
            if (addr.isBlank()) return
            val key = matchKey(addr)
            if (key.isEmpty()) return
            val previous = out[key]
            if (previous == null || date > previous.first) out[key] = date to body
        }

        // SMS reçus
        try {
            context.contentResolver.query(
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
            )?.use {
                while (it.moveToNext()) record(it.getString(0), it.getLong(1), it.getString(2) ?: "")
            }
        } catch (_: Exception) {
        }

        // MMS reçus (la date y est en SECONDES, et l'expéditeur est dans une table à part)
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms/inbox"),
                arrayOf("_id", "date"),
                "date > ?",
                arrayOf((since / 1000).toString()),
                "date DESC"
            )?.use { mms ->
                var handled = 0
                while (mms.moveToNext() && handled < 300) {
                    handled++
                    val id = mms.getLong(0)
                    val dateMs = mms.getLong(1) * 1000L
                    context.contentResolver.query(
                        android.net.Uri.parse("content://mms/" + id + "/addr"),
                        arrayOf("address", "type"),
                        "msg_id = ?", arrayOf(id.toString()), null
                    )?.use { addr ->
                        while (addr.moveToNext()) {
                            // type 137 = PduHeaders.FROM : l'expéditeur du message
                            if (addr.getInt(1) == 137) record(addr.getString(0), dateMs, "(MMS)")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return out to scanned
    }

    /**
     * Attache à chaque appel la date du dernier message reçu de ce numéro.
     * [captured] : messages relevés via les notifications (RCS / chat), fusionnés
     * avec les SMS et MMS lus dans la base Android.
     */
    fun markSmsReplies(
        context: Context,
        calls: List<CallEntry>,
        captured: Map<String, Pair<Long, String>> = emptyMap()
    ): CallLogScan {
        if (calls.isEmpty()) return CallLogScan(calls, if (hasSmsReadPermission(context)) 0 else -1)
        val (inbox, scanned) = inboundSmsByNumber(context, calls.minOf { it.date })
        val merged = HashMap(inbox)
        for ((key, value) in captured) {
            val previous = merged[key]
            if (previous == null || value.first > previous.first) merged[key] = value
        }
        if (merged.isEmpty()) return CallLogScan(calls, scanned)
        val marked = calls.map { call ->
            val hit = merged[matchKey(call.number)]
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
            sb.append("(aucun message pour ce numéro dans la base SMS/MMS d'Android)\n")
            sb.append("→ conversation RCS / « chat » : ces messages sont chiffrés de bout en bout\n")
            sb.append("  et stockés hors de la base SMS. Active « Capture des messages RCS »\n")
            sb.append("  dans Réglages pour que les PROCHAINS soient détectés.\n")
        }

        sb.append("\n--- Verdict ---\n")
        val lastCall = calls.maxOfOrNull { it.date } ?: 0L
        val (inbox, scanned) = inboundSmsByNumber(context, 0L)
        val lastSms = inbox[key]?.first ?: 0L
        sb.append("Total SMS reçus analysés : ").append(scanned).append("\n")
        sb.append("Dernier appel : ").append(if (lastCall > 0) fmt.format(java.util.Date(lastCall)) else "aucun").append("\n")
        sb.append("Dernier SMS  : ").append(if (lastSms > 0) fmt.format(java.util.Date(lastSms)) else "aucun").append("\n")
        val lastMissed = calls.filter { it.isMissed }.maxOfOrNull { it.date } ?: 0L
        val lastOutgoing = calls.filter { it.isOutgoing }.maxOfOrNull { it.date } ?: 0L
        val lastIncoming = calls.filter { it.isIncoming }.maxOfOrNull { it.date } ?: 0L
        sb.append("Dernier appel manqué : ")
            .append(if (lastMissed > 0) fmt.format(java.util.Date(lastMissed)) else "aucun").append("\n")
        sb.append("Dernier appel émis   : ")
            .append(if (lastOutgoing > 0) fmt.format(java.util.Date(lastOutgoing)) else "aucun").append("\n")
        sb.append("Dernier appel reçu   : ")
            .append(if (lastIncoming > 0) fmt.format(java.util.Date(lastIncoming)) else "aucun").append("\n")
        val followUp = maxOf(lastOutgoing, lastIncoming, lastSms)
        sb.append(
            when {
                lastMissed == 0L -> "→ aucun appel manqué : rien à traiter pour ce numéro."
                followUp > lastMissed -> "→ DÉJÀ TRAITÉ depuis l'appel manqué (" +
                    (if (lastSms == followUp) "SMS reçu" else if (lastOutgoing == followUp) "tu as rappelé" else "appel repris") +
                    ") : l'alerte doit s'afficher."
                else -> "→ appel manqué SANS SUITE : ce numéro est à contacter."
            }
        )
        return sb.toString()
    }
}
