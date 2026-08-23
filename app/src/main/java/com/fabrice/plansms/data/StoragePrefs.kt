package com.fabrice.plansms.data

import android.content.Context
import com.fabrice.plansms.security.SecretStore

/**
 * Destination des enregistrements audio.
 * Les mots de passe (FTP, SMTP) sont chiffrés via l'Android Keystore — voir SecretStore.
 */
object StoragePrefs {
    private const val PREFS = "plansms_storage"

    const val DEST_LOCAL = "LOCAL"    // téléphone uniquement (défaut)
    const val DEST_FOLDER = "FOLDER"  // dossier choisi (SD, OneDrive, Dropbox, Nextcloud…)
    const val DEST_FTP = "FTP"        // serveur FTP / FTPS
    const val DEST_EMAIL = "EMAIL"    // envoi automatique par email (SMTP)

    private const val K_DEST = "destination"
    private const val K_FOLDER = "folder_uri"
    private const val K_DELETE = "delete_after_export"
    private const val K_FTP_HOST = "ftp_host"
    private const val K_FTP_PORT = "ftp_port"
    private const val K_FTP_USER = "ftp_user"
    private const val K_FTP_PASS = "ftp_pass"
    private const val K_FTP_PATH = "ftp_path"
    private const val K_FTP_SECURE = "ftp_secure"
    private const val K_MAIL_HOST = "mail_host"
    private const val K_MAIL_PORT = "mail_port"
    private const val K_MAIL_USER = "mail_user"
    private const val K_MAIL_PASS = "mail_pass"
    private const val K_MAIL_FROM = "mail_from"
    private const val K_MAIL_TO = "mail_to"
    private const val K_MAIL_TLS = "mail_starttls"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun str(context: Context, key: String, def: String = "") =
        prefs(context).getString(key, def) ?: def

    private fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun destination(context: Context): String = str(context, K_DEST, DEST_LOCAL)
    fun setDestination(context: Context, dest: String) = put(context, K_DEST, dest)

    fun folderUri(context: Context): String = str(context, K_FOLDER)
    fun setFolderUri(context: Context, uri: String) = put(context, K_FOLDER, uri)

    /** Effacer le fichier du téléphone une fois l'export réussi. */
    fun deleteAfterExport(context: Context): Boolean = prefs(context).getBoolean(K_DELETE, false)
    fun setDeleteAfterExport(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_DELETE, on).apply()
    }

    // --- FTP ---
    fun ftpHost(context: Context): String = str(context, K_FTP_HOST)
    fun setFtpHost(context: Context, v: String) = put(context, K_FTP_HOST, v.trim())
    fun ftpPort(context: Context): Int = str(context, K_FTP_PORT, "21").toIntOrNull() ?: 21
    fun setFtpPort(context: Context, v: String) = put(context, K_FTP_PORT, v.trim())
    fun ftpUser(context: Context): String = str(context, K_FTP_USER)
    fun setFtpUser(context: Context, v: String) = put(context, K_FTP_USER, v.trim())
    fun ftpPassword(context: Context): String = SecretStore.decrypt(str(context, K_FTP_PASS))
    fun setFtpPassword(context: Context, v: String) = put(context, K_FTP_PASS, SecretStore.encrypt(v))
    fun ftpPath(context: Context): String = str(context, K_FTP_PATH)
    fun setFtpPath(context: Context, v: String) = put(context, K_FTP_PATH, v.trim())
    fun ftpSecure(context: Context): Boolean = prefs(context).getBoolean(K_FTP_SECURE, false)
    fun setFtpSecure(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_FTP_SECURE, on).apply()
    }

    // --- Email (SMTP) ---
    fun mailHost(context: Context): String = str(context, K_MAIL_HOST)
    fun setMailHost(context: Context, v: String) = put(context, K_MAIL_HOST, v.trim())
    fun mailPort(context: Context): Int = str(context, K_MAIL_PORT, "587").toIntOrNull() ?: 587
    fun setMailPort(context: Context, v: String) = put(context, K_MAIL_PORT, v.trim())
    fun mailUser(context: Context): String = str(context, K_MAIL_USER)
    fun setMailUser(context: Context, v: String) = put(context, K_MAIL_USER, v.trim())
    fun mailPassword(context: Context): String = SecretStore.decrypt(str(context, K_MAIL_PASS))
    fun setMailPassword(context: Context, v: String) = put(context, K_MAIL_PASS, SecretStore.encrypt(v))
    fun mailFrom(context: Context): String = str(context, K_MAIL_FROM)
    fun setMailFrom(context: Context, v: String) = put(context, K_MAIL_FROM, v.trim())
    fun mailTo(context: Context): String = str(context, K_MAIL_TO)
    fun setMailTo(context: Context, v: String) = put(context, K_MAIL_TO, v.trim())
    fun mailStartTls(context: Context): Boolean = prefs(context).getBoolean(K_MAIL_TLS, true)
    fun setMailStartTls(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_MAIL_TLS, on).apply()
    }

    /** Libellé lisible de la destination courante (pour l'UI). */
    fun label(context: Context): String = when (destination(context)) {
        DEST_FOLDER -> if (folderUri(context).isBlank()) "Dossier (non choisi)" else "Dossier choisi"
        DEST_FTP -> if (ftpHost(context).isBlank()) "FTP (non configuré)" else "FTP · ${ftpHost(context)}"
        DEST_EMAIL -> if (mailTo(context).isBlank()) "Email (non configuré)" else "Email · ${mailTo(context)}"
        else -> "Téléphone uniquement"
    }
}
