package com.fabrice.plansms.relay

import android.content.Context
import com.fabrice.plansms.data.StoragePrefs
import com.fabrice.plansms.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Envoi direct par SMTP, sans serveur intermédiaire. Réutilise le compte
 * d'envoi configuré dans Réglages → Stockage.
 */
object RelayMailer {

    fun isConfigured(context: Context): Boolean =
        StoragePrefs.mailHost(context).isNotBlank() && StoragePrefs.mailUser(context).isNotBlank()

    /** Retourne null si l'envoi a réussi, sinon le motif de l'échec. */
    fun send(context: Context, to: String, sender: String, body: String, receivedAt: Long): String? {
        val stamp = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(Date(receivedAt))
        return sendRaw(
            context, to,
            subject = "[SMS Relay] De $sender",
            body = "De : $sender\nReçu le $stamp\n\n$body"
        )
    }

    /** Envoi SMTP brut (sujet + corps). Retourne null si OK, sinon le motif. */
    fun sendRaw(context: Context, to: String, subject: String, body: String): String? {
        val host = StoragePrefs.mailHost(context)
        if (host.isBlank()) return "SMTP non configuré"
        return try {
            val port = StoragePrefs.mailPort(context)
            val user = StoragePrefs.mailUser(context)
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "40000")
                if (StoragePrefs.mailStartTls(context)) {
                    put("mail.smtp.starttls.enable", "true")
                } else {
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.port", port.toString())
                    put("mail.smtp.ssl.enable", "true")
                }
            }
            val session = javax.mail.Session.getInstance(
                props,
                object : javax.mail.Authenticator() {
                    override fun getPasswordAuthentication(): javax.mail.PasswordAuthentication =
                        javax.mail.PasswordAuthentication(user, StoragePrefs.mailPassword(context))
                }
            )
            val from = StoragePrefs.mailFrom(context).ifBlank { user }
            val message = javax.mail.internet.MimeMessage(session).apply {
                setFrom(javax.mail.internet.InternetAddress(from))
                setRecipients(
                    javax.mail.Message.RecipientType.TO,
                    javax.mail.internet.InternetAddress.parse(to)
                )
                setSubject(subject, "UTF-8")
                setText(body, "UTF-8")
            }
            javax.mail.Transport.send(message)
            AppLogger.i("RelayMailer", "SMS relayé par email → $to")
            null
        } catch (e: Exception) {
            AppLogger.e("RelayMailer", "Échec email → $to", e)
            e.message ?: "Erreur d'envoi email"
        }
    }
}
