package com.fabrice.plansms.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Helpers contacts : lecture du numéro depuis l'URI retournée par le picker natif. */
object ContactsHelper {

    /**
     * Récupère (numéro, nom) d'un contact depuis l'URI du sélecteur Android (PickContact).
     * L'URI peut pointer sur une data (content://com.android.contacts/data/N) ou
     * sur un contact (content://com.android.contacts/contacts/N) → deux stratégies.
     */
    fun contactPhoneFromUri(context: Context, uri: Uri): Pair<String, String>? {
        // 1. Lecture directe de la ligne (data)
        try {
            val c = context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null, null, null
            )
            c?.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    return (it.getString(0) ?: "") to (it.getString(1) ?: "")
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback : résoudre par contact_id puis chercher un téléphone
        val id = uri.lastPathSegment ?: return null
        if (id.all { it.isDigit() }) {
            try {
                val c2 = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )
                c2?.use {
                    if (it.moveToFirst()) {
                        return (it.getString(0) ?: "") to (it.getString(1) ?: "")
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
