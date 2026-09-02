package com.fabrice.plansms.scheduler

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fabrice.plansms.util.AppLogger

/**
 * Tuile « Répondeur SMS » dans les réglages rapides (volet tiré du haut de
 * l'écran) : montre l'état d'un coup d'œil et bascule le répondeur sans
 * ouvrir l'application.
 */
class ResponderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val on = !CallResponder.enabled(this)
        CallResponder.setEnabled(this, on)
        AppLogger.i("ResponderTile", "Répondeur SMS " + (if (on) "activé" else "désactivé") + " (tuile)")
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = CallResponder.enabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Répondeur SMS"
        tile.icon = Icon.createWithResource(this, android.R.drawable.stat_notify_voicemail)
        tile.updateTile()
    }
}
