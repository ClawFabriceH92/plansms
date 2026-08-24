package com.fabrice.plansms.notif

import android.content.Context

/** Préférence de capture des messages via les notifications (RCS / chat). */
object NotifPrefs {
    private const val PREFS = "plansms_notif"
    private const val KEY_CAPTURE = "capture_messages"

    fun captureEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAPTURE, false)

    fun setCaptureEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CAPTURE, on).apply()
    }
}
