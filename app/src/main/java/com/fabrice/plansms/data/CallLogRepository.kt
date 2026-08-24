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
    val smsSinceAt: Long = 0,  // date du SMS reçu de ce numéro APRÈS l'appel (0 = aucun)
    val smsSincePreview: String = ""
) {
    val hasRepliedBySms: Boolean get() = smsSinceAt > 0
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
     * Dernier SMS REÇU par numéro depuis [since] : clé = numéro canonique,
     * valeur = (date, extrait). Sert à repérer les correspondants qui ont déjà
     * répondu par SMS après leur appel.
     */
    fun inboundSmsByNumber(context: Context, since: Long): Map<String, Pair<Long, String>> {
        if (!hasSmsReadPermission(context)) return emptyMap()
        val out = HashMap<String, Pair<Long, String>>()
        try {
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.DATE,
                    android.provider.Telephony.Sms.BODY
                ),
                android.provider.Telephony.Sms.DATE + " > ?",
                arrayOf(since.toString()),
                android.provider.Telephony.Sms.DATE + " DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(0)?.trim() ?: continue
                    if (address.isBlank()) continue
                    val key = normalize(address)
                    val date = it.getLong(1)
                    val previous = out[key]
                    if (previous == null || date > previous.first) {
                        out[key] = date to (it.getString(2) ?: "")
                    }
                }
            }
        } catch (_: Exception) {
            // boîte de réception inaccessible → aucun marquage, l'UI le signale
        }
        return out
    }

    /** Marque les appels dont le correspondant a envoyé un SMS APRÈS l'appel. */
    fun markSmsReplies(context: Context, calls: List<CallEntry>): List<CallEntry> {
        if (calls.isEmpty()) return calls
        val inbox = inboundSmsByNumber(context, calls.minOf { it.date })
        if (inbox.isEmpty()) return calls
        return calls.map { call ->
            val hit = inbox[normalize(call.number)]
            if (hit != null && hit.first > call.date) {
                call.copy(smsSinceAt = hit.first, smsSincePreview = hit.second.take(70))
            } else {
                call
            }
        }
    }
}
