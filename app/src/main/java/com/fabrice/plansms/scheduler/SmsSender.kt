package com.fabrice.plansms.scheduler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.fabrice.plansms.util.AppLogger

/** Envoi SMS réel via SmsManager (API système). */
object SmsSender {

    fun canSend(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Envoie un SMS. Retourne null si OK, sinon le message d'erreur.
     */
    fun send(context: Context, phone: String, text: String): String? {
        if (!canSend(context)) return "Permission SEND_SMS refusée"
        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(phone, null, parts, null, null)
            else sms.sendTextMessage(phone, null, text, null, null)
            AppLogger.i("SmsSender", "SMS envoyé → $phone (${text.length} chars)")
            null
        } catch (e: Exception) {
            AppLogger.e("SmsSender", "Échec envoi → $phone", e)
            e.message ?: "Erreur inconnue"
        }
    }
}
