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
    val count: Int = 1         // nombre d'appels regroupés pour ce numéro
) {
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
                )
            }
        }
        return byNumber.values.toList()
    }

    /** Normalisation simple pour regrouper 06… et +336… : chiffres seuls, préfixe FR replié. */
    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+33") -> "0" + digits.drop(3)
            digits.startsWith("0033") -> "0" + digits.drop(4)
            else -> digits
        }
    }
}
