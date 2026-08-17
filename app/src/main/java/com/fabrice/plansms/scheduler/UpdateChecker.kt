package com.fabrice.plansms.scheduler

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.fabrice.plansms.util.AppLogger
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérification des mises à jour sur GitHub Releases (repo ClawFabriceH92/plansms).
 */
object UpdateChecker {
    private const val REPO = "ClawFabriceH92/plansms"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=5"
    private const val PREFS = "plansms_update"
    private const val KEY_AUTO = "auto_update"

    data class UpdateInfo(val version: String, val apkUrl: String)

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdateEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun check(context: Context, currentVersion: String): UpdateInfo? {
        return try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "PlanSMS-Android")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val arr = JSONArray(body)
            var latest: UpdateInfo? = null
            var latestSegs = intArrayOf(0, 0, 0)
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                if (rel.optBoolean("draft")) continue
                val tag = rel.optString("tag_name").removePrefix("v")
                val segs = parseVersion(tag) ?: continue
                val apkUrl = findApkUrl(rel.optJSONArray("assets"))
                if (apkUrl == null || compare(segs, latestSegs) <= 0) continue
                latestSegs = segs
                latest = UpdateInfo(tag, apkUrl)
            }
            latest?.takeIf { compare(parseVersion(currentVersion) ?: intArrayOf(0, 0, 0), latestSegs) < 0 }
        } catch (e: Exception) {
            AppLogger.e("UpdateChecker", "Erreur vérification MAJ", e)
            null
        }
    }

    private fun parseVersion(v: String): IntArray? {
        val parts = v.split(".").map { it.toIntOrNull() } ?: return null
        if (parts.size < 2 || parts.any { it == null }) return null
        return intArrayOf(parts[0]!!, parts[1]!!, parts.getOrNull(2) ?: 0)
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0..2) {
            if (a[i] != b[i]) return a[i] - b[i]
        }
        return 0
    }

    private fun findApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return a.optString("browser_download_url")
        }
        return null
    }

    fun isUpdateAvailable(context: Context): Boolean =
        check(context, versionName(context)) != null

    fun versionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
