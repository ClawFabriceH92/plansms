package com.fabrice.plansms.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde la messagerie vocale visuelle : que voit RÉELLEMENT une app tierce ?
 *
 * Trois niveaux d'accès distincts :
 *  1. le journal d'appels marque les messages vocaux (type 4) — lisible avec READ_CALL_LOG ;
 *  2. la colonne « transcription » y est parfois remplie par l'opérateur ou Google ;
 *  3. l'audio lui-même est dans le fournisseur voicemail, protégé par READ_VOICEMAIL
 *     (réservée aux apps système et à l'application téléphone par défaut).
 */
object VoicemailProbe {

    fun report(context: Context): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        val sb = StringBuilder("=== Sonde messagerie vocale ===\n")
        val canReadCallLog = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        sb.append("Permission journal d'appels : ").append(if (canReadCallLog) "OUI" else "NON").append("\n")
        sb.append("Permission ADD_VOICEMAIL : ").append(
            if (ContextCompat.checkSelfPermission(context, "com.android.voicemail.permission.ADD_VOICEMAIL")
                == PackageManager.PERMISSION_GRANTED
            ) "OUI" else "NON"
        ).append("\n\n")

        if (!canReadCallLog) return sb.append("Sans le journal d'appels, rien n'est visible.").toString()

        // --- 1. Entrées « messagerie vocale » du journal d'appels ---
        sb.append("--- Messages vocaux vus dans le journal d'appels ---\n")
        var found = 0
        var withTranscription = 0
        val uris = mutableListOf<String>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                CallLog.Calls.TYPE + " = ?",
                arrayOf(CallLog.Calls.VOICEMAIL_TYPE.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                val iNumber = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                val iDuration = c.getColumnIndex(CallLog.Calls.DURATION)
                val iTranscription = c.getColumnIndex("transcription")
                val iVoicemailUri = c.getColumnIndex("voicemail_uri")
                sb.append("Colonne « transcription » présente : ")
                    .append(if (iTranscription >= 0) "OUI" else "NON").append("\n")
                sb.append("Colonne « voicemail_uri » présente : ")
                    .append(if (iVoicemailUri >= 0) "OUI" else "NON").append("\n\n")
                while (c.moveToNext() && found < 10) {
                    found++
                    val number = if (iNumber >= 0) c.getString(iNumber) else null
                    val date = if (iDate >= 0) c.getLong(iDate) else 0L
                    val duration = if (iDuration >= 0) c.getLong(iDuration) else 0L
                    sb.append(fmt.format(Date(date))).append("  ").append(number ?: "?")
                        .append("  ").append(duration).append("s\n")
                    if (iTranscription >= 0) {
                        val t = c.getString(iTranscription)
                        if (!t.isNullOrBlank()) {
                            withTranscription++
                            sb.append("    TRANSCRIPTION : « ").append(t.take(120)).append(" »\n")
                        } else {
                            sb.append("    (aucune transcription fournie)\n")
                        }
                    }
                    if (iVoicemailUri >= 0) {
                        val u = c.getString(iVoicemailUri)
                        if (!u.isNullOrBlank()) {
                            uris.add(u)
                            sb.append("    audio : ").append(u).append("\n")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("Lecture impossible : ").append(e.message).append("\n")
        }
        if (found == 0) sb.append("(aucune entrée de type messagerie vocale)\n")

        // --- 2. Le fournisseur voicemail est-il interrogeable ? ---
        sb.append("\n--- Fournisseur voicemail (content://voicemail) ---\n")
        try {
            context.contentResolver.query(
                android.provider.VoicemailContract.Voicemails.CONTENT_URI,
                null, null, null, null
            )?.use { sb.append("Requête acceptée : ").append(it.count).append(" message(s) visible(s)\n") }
                ?: sb.append("Requête refusée (curseur nul)\n")
        } catch (e: SecurityException) {
            sb.append("REFUSÉ : ").append(e.message?.take(150)).append("\n")
        } catch (e: Exception) {
            sb.append("Erreur : ").append(e.message?.take(150)).append("\n")
        }

        // --- 3. L'audio est-il ouvrable ? C'est le test décisif ---
        sb.append("\n--- Ouverture du fichier audio ---\n")
        if (uris.isEmpty()) {
            sb.append("(aucune URI audio à tester)\n")
        } else {
            try {
                context.contentResolver.openInputStream(android.net.Uri.parse(uris.first()))?.use {
                    val size = it.available()
                    sb.append("✅ AUDIO LISIBLE (").append(size).append(" octets disponibles)\n")
                    sb.append("→ la récupération et la transcription sont possibles.\n")
                } ?: sb.append("Flux nul.\n")
            } catch (e: SecurityException) {
                sb.append("❌ REFUSÉ : ").append(e.message?.take(200)).append("\n")
                sb.append("→ READ_VOICEMAIL réservée aux apps système / téléphone par défaut.\n")
                sb.append("→ Contournement : depuis l'app Téléphone, partage le message vocal\n")
                sb.append("  vers PlanSMS (il apparaîtra dans Journal → Audio).\n")
            } catch (e: Exception) {
                sb.append("Erreur : ").append(e.message?.take(200)).append("\n")
            }
        }

        sb.append("\n--- Résumé ---\n")
        sb.append(found).append(" message(s) vocal(aux) repéré(s), ")
            .append(withTranscription).append(" avec transcription.\n")
        return sb.toString()
    }
}
