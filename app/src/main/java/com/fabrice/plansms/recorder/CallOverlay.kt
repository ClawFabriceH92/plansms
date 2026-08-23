package com.fabrice.plansms.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Bouton flottant affiché PAR-DESSUS l'écran d'appel (téléphone, WhatsApp,
 * Teams…) : un appui lance ou arrête l'enregistrement. Déplaçable au doigt.
 * Nécessite l'autorisation « Afficher par-dessus les autres applications ».
 */
object CallOverlay {

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var watchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun isAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (view != null) return
        if (!isAllowed(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val button = TextView(app).apply {
            text = "⏺ Enregistrer"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#C62828"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(150)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try { manager.updateViewLayout(button, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap(app)
                    true
                }
                else -> false
            }
        }

        try {
            manager.addView(button, params)
        } catch (e: Exception) {
            AppLogger.e("CallOverlay", "Bouton flottant impossible", e)
            return
        }
        view = button
        wm = manager

        watchJob?.cancel()
        watchJob = scope.launch {
            RecorderState.isRecording.collect { recording ->
                view?.let { v ->
                    v.text = if (recording) "⏹ Arrêter" else "⏺ Enregistrer"
                    (v.background as? GradientDrawable)?.setColor(
                        Color.parseColor(if (recording) "#2E7D32" else "#C62828")
                    )
                }
            }
        }
    }

    private fun onTap(context: Context) {
        if (RecorderState.isRecording.value) {
            RecordingService.stop(context)
        } else {
            RecordingService.start(context, "Appel")
        }
    }

    fun hide() {
        watchJob?.cancel()
        watchJob = null
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
        wm = null
    }
}
