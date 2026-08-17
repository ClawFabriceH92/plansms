package com.fabrice.plansms.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logger en anneau (300 lignes) + capture des crashes. */
object AppLogger {
    private val log = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, version: String) {
        installCrashHandler(context)
        i("AppLogger", "=== PlanSMS v$version === on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
    }

    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun w(tag: String, msg: String) = append("W", tag, msg)
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        append("E", tag, msg)
        throwable?.let { it.stackTraceToString().lines().forEach { line -> if (line.isNotBlank()) append("E", tag, "  $line") } }
    }

    private fun append(level: String, tag: String, msg: String) {
        synchronized(log) {
            log.add("${dateFormat.format(Date())} | $level | $tag: $msg")
            while (log.size > 300) log.removeAt(0)
        }
    }

    fun saveToFile(context: Context): File = File(context.filesDir, "app_log.txt").also { it.writeText(getLogText()) }

    fun getLogText(): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        synchronized(log) { for (line in log) appendLine(line) }
    }

    private fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(context.filesDir, "crash_log.txt").writeText(
                    "=== CRASH ===\nThread: ${thread.name}\n${throwable.javaClass.name}: ${throwable.message}\n\n${throwable.stackTraceToString()}"
                )
            } catch (_: Exception) {}
            finally { previous?.uncaughtException(thread, throwable) }
        }
    }
}
