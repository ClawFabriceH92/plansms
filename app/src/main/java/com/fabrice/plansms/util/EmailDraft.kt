package com.fabrice.plansms.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Ouvre un brouillon d'email pré-rempli, dans Outlook de préférence (le
 * calendrier du cabinet y vit), sinon dans l'application email par défaut.
 * Rien ne part tout seul : c'est un brouillon, l'envoi reste un geste.
 */
object EmailDraft {

    private const val OUTLOOK = "com.microsoft.office.outlook"

    /** Retourne true si une application a pris le brouillon. */
    fun open(context: Context, to: String, subject: String, body: String): Boolean {
        val draft = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:" + Uri.encode(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        // Outlook d'abord…
        try {
            context.startActivity(Intent(draft).setPackage(OUTLOOK))
            return true
        } catch (_: Exception) {
        }
        // …sinon l'app email par défaut
        return try {
            context.startActivity(draft)
            true
        } catch (_: Exception) {
            try {
                Toast.makeText(context, "Aucune application email sur ce téléphone.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            AppLogger.w("EmailDraft", "Aucune app email pour le brouillon → $to")
            false
        }
    }
}
