package com.fabrice.plansms.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.fabrice.plansms.data.StoragePrefs
import com.fabrice.plansms.util.AppLogger
import java.io.File
import java.util.Properties

/**
 * Copie d'un enregistrement vers la destination choisie.
 * Tout se fait en tâche de fond ; le fichier reste sur le téléphone tant que
 * l'export n'a pas réussi (et n'est effacé que si l'option est activée).
 */
object RecordingExporter {

    data class Result(val ok: Boolean, val message: String)

    private fun mimeOf(file: File): String =
        if (file.extension.equals("m4a", true)) "audio/mp4" else "application/octet-stream"

    /** Envoie un petit fichier de test vers la destination configurée. */
    fun test(context: Context): Result {
        val probe = File(context.cacheDir, "PlanSMS-test.txt")
        return try {
            probe.writeText("Test de destination PlanSMS.")
            when (StoragePrefs.destination(context)) {
                StoragePrefs.DEST_FOLDER -> toFolder(context, probe)
                StoragePrefs.DEST_FTP -> toFtp(context, probe)
                StoragePrefs.DEST_EMAIL -> toEmail(context, probe, "test de configuration")
                else -> Result(true, "Destination « téléphone » : rien à tester")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "Échec du test")
        } finally {
            try { probe.delete() } catch (_: Exception) {}
        }
    }

    fun export(context: Context, file: File, label: String): Result {
        if (!file.exists()) return Result(false, "Fichier introuvable")
        val result = when (StoragePrefs.destination(context)) {
            StoragePrefs.DEST_FOLDER -> toFolder(context, file)
            StoragePrefs.DEST_FTP -> toFtp(context, file)
            StoragePrefs.DEST_EMAIL -> toEmail(context, file, label)
            else -> Result(true, "Téléphone")
        }
        if (result.ok &&
            StoragePrefs.destination(context) != StoragePrefs.DEST_LOCAL &&
            StoragePrefs.deleteAfterExport(context)
        ) {
            try { file.delete() } catch (_: Exception) {}
        }
        AppLogger.i("Exporter", "Export ${file.name} → ${result.message} (ok=${result.ok})")
        return result
    }

    /** Dossier choisi via le sélecteur Android (carte SD, OneDrive, Dropbox, Nextcloud…). */
    private fun toFolder(context: Context, file: File): Result {
        val uriStr = StoragePrefs.folderUri(context)
        if (uriStr.isBlank()) return Result(false, "Aucun dossier choisi")
        return try {
            val treeUri = Uri.parse(uriStr)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val target = DocumentsContract.createDocument(
                context.contentResolver, parent, mimeOf(file), file.name
            ) ?: return Result(false, "Le dossier a refusé la création du fichier")
            context.contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return Result(false, "Écriture impossible dans ce dossier")
            Result(true, "Copié dans le dossier")
        } catch (e: SecurityException) {
            Result(false, "Accès au dossier perdu — choisis-le à nouveau dans les réglages")
        } catch (e: Exception) {
            Result(false, e.message ?: "Erreur de copie")
        }
    }

    /** Serveur FTP ou FTPS (chiffré). */
    private fun toFtp(context: Context, file: File): Result {
        val host = StoragePrefs.ftpHost(context)
        if (host.isBlank()) return Result(false, "Serveur FTP non configuré")
        val secure = StoragePrefs.ftpSecure(context)
        val client = if (secure) {
            org.apache.commons.net.ftp.FTPSClient(false)
        } else {
            org.apache.commons.net.ftp.FTPClient()
        }
        return try {
            client.connectTimeout = 20_000
            client.connect(host, StoragePrefs.ftpPort(context))
            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) {
                client.disconnect()
                return Result(false, "Connexion refusée par le serveur")
            }
            if (!client.login(StoragePrefs.ftpUser(context), StoragePrefs.ftpPassword(context))) {
                client.disconnect()
                return Result(false, "Identifiants FTP refusés")
            }
            if (secure && client is org.apache.commons.net.ftp.FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            val path = StoragePrefs.ftpPath(context)
            if (path.isNotBlank() && !client.changeWorkingDirectory(path)) {
                client.makeDirectory(path)
                client.changeWorkingDirectory(path)
            }
            val sent = file.inputStream().use { client.storeFile(file.name, it) }
            try { client.logout() } catch (_: Exception) {}
            client.disconnect()
            if (sent) Result(true, "Envoyé sur $host") else Result(false, "Le serveur a refusé le fichier")
        } catch (e: Exception) {
            try { if (client.isConnected) client.disconnect() } catch (_: Exception) {}
            Result(false, e.message ?: "Erreur FTP")
        }
    }

    /** Envoi automatique par email (SMTP), enregistrement en pièce jointe. */
    private fun toEmail(context: Context, file: File, label: String): Result {
        val host = StoragePrefs.mailHost(context)
        val to = StoragePrefs.mailTo(context)
        if (host.isBlank() || to.isBlank()) return Result(false, "Email non configuré")
        return try {
            val port = StoragePrefs.mailPort(context)
            val startTls = StoragePrefs.mailStartTls(context)
            val user = StoragePrefs.mailUser(context)
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "40000")
                if (startTls) {
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
            val msg = javax.mail.internet.MimeMessage(session).apply {
                setFrom(javax.mail.internet.InternetAddress(from))
                setRecipients(
                    javax.mail.Message.RecipientType.TO,
                    javax.mail.internet.InternetAddress.parse(to)
                )
                subject = "PlanSMS — enregistrement" + if (label.isNotBlank()) " : $label" else ""
            }
            val body = javax.mail.internet.MimeBodyPart().apply {
                setText(
                    "Enregistrement audio joint (" + (file.length() / 1024) + " Ko).\n" +
                        "Envoyé automatiquement par PlanSMS."
                )
            }
            val attachment = javax.mail.internet.MimeBodyPart().apply { attachFile(file) }
            msg.setContent(
                javax.mail.internet.MimeMultipart().apply {
                    addBodyPart(body)
                    addBodyPart(attachment)
                }
            )
            javax.mail.Transport.send(msg)
            Result(true, "Envoyé à $to")
        } catch (e: Exception) {
            Result(false, e.message ?: "Erreur d'envoi email")
        }
    }
}
