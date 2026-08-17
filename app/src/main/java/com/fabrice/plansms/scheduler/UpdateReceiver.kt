package com.fabrice.plansms.scheduler

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.FileProvider
import com.fabrice.plansms.util.AppLogger
import java.io.File

/** Reçoit la fin du téléchargement APK et lance l'installation. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs: SharedPreferences = context.getSharedPreferences("plansms_update", Context.MODE_PRIVATE)
        if (prefs.getLong("download_id", -1L) != id) return
        prefs.edit().remove("download_id").apply()

        val file = File(context.getExternalFilesDir(null), "plansms-update.apk")
        if (!file.exists()) return
        try {
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
    fun start(context: Context, url: String) {
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
    }
}
