package com.fabrice.plansms.scheduler

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.fabrice.plansms.util.AppLogger
import java.io.File

/**
 * Reçoit la fin du téléchargement APK et lance l'installation.
 * Vérifie la permission "installer des apps inconnues" — sinon notification explicite
 * (le bug classique : installation silencieuse bloquée).
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs: SharedPreferences = context.getSharedPreferences("plansms_update", Context.MODE_PRIVATE)
        if (prefs.getLong("download_id", -1L) != id) return
        prefs.edit().remove("download_id").apply()

        val file = File(context.getExternalFilesDir(null), "plansms-update.apk")
        if (!file.exists()) {
            AppLogger.e("UpdateReceiver", "APK téléchargé introuvable : ${file.absolutePath}")
            return
        }
        try {
            if (!UpdateDownloader.canRequestInstalls(context)) {
                UpdateDownloader.notifyPermissionNeeded(context, "Mise à jour téléchargée mais installation bloquée")
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
            AppLogger.i("UpdateReceiver", "Installation lancée")
        } catch (e: Exception) {
            AppLogger.e("UpdateReceiver", "Échec installation", e)
        }
    }
}

/** Démarre le téléchargement d'un APK depuis une URL GitHub. */
object UpdateDownloader {

    private const val CHANNEL = "plansms_updates"
    private const val NOTIF_ID = 999

    fun canRequestInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true

    /** Retourne true si le téléchargement a démarré. */
    fun start(context: Context, url: String): Boolean {
        if (!canRequestInstalls(context)) {
            notifyPermissionNeeded(context, "Autorise l'installation d'apps inconnues pour installer les mises à jour")
            AppLogger.w("UpdateDownloader", "Installation bloquée : permission apps inconnues absente")
            return false
        }
        val file = File(context.getExternalFilesDir(null), "plansms-update.apk")
        file.delete()
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setDestinationUri(Uri.fromFile(file))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle("PlanSMS — mise à jour")
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        context.getSharedPreferences("plansms_update", Context.MODE_PRIVATE)
            .edit().putLong("download_id", id).apply()
        AppLogger.i("UpdateDownloader", "Téléchargement MAJ démarré ($id)")
        return true
    }

    /** Notification avec action directe vers les réglages "installer des apps inconnues". */
    fun notifyPermissionNeeded(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mises à jour PlanSMS", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PlanSMS — mise à jour")
            .setContentText(message)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}
