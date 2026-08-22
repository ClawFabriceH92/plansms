package com.fabrice.plansms.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Helpers contacts : lecture du numéro depuis l'URI retournée par le picker natif. */
object ContactsHelper {

    /** Contact du répertoire avec son premier numéro. */
    data class PhoneContact(val contactId: Long, val name: String, val phone: String)

    /** Tous les contacts ayant un numéro (premier numéro par contact). */
    fun allPhoneContacts(context: Context): List<PhoneContact> {
        val out = LinkedHashMap<Long, PhoneContact>()
        try {
            val c = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            c?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id in out) continue
                    val name = it.getString(1) ?: continue
                    val phone = it.getString(2) ?: continue
                    if (name.isBlank() || phone.isBlank()) continue
                    out[id] = PhoneContact(id, name, phone)
                }
            }
        } catch (_: Exception) {}
        return out.values.toList()
    }

    /**
     * Rapprochement par nom/prénom : cherche un contact dont le nom partage
     * au moins 2 mots avec le nom du participant (ou le début de son email,
     * ex. jean.dupont@…), ou dont le nom normalisé est identique.
     */
    fun suggestByName(contacts: List<PhoneContact>, attendeeName: String, email: String): PhoneContact? {
        val source = attendeeName.ifBlank { email.substringBefore("@").replace(Regex("[._-]+"), " ") }
        val tokens = tokens(source)
        if (tokens.isEmpty()) return null
        var best: PhoneContact? = null
        var bestScore = 0
        for (contact in contacts) {
            val cTokens = tokens(contact.name)
            if (cTokens.isEmpty()) continue
            val common = tokens.intersect(cTokens).size
            val exact = tokens == cTokens
            val score = when {
                exact -> 100
                common >= 2 -> common * 10
                common == 1 && tokens.size == 1 && tokens.first().length >= 4 -> 5
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = contact }
        }
        return best
    }

    private fun tokens(name: String): Set<String> =
        java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")          // enlève les accents
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .toSet()

    /**
     * Associe un email à un contact existant (fusion d'informations).
     * Nécessite la permission WRITE_CONTACTS. Retourne true si l'écriture a réussi.
     */
    fun addEmailToContact(context: Context, contactId: Long, email: String): Boolean {
        return try {
            // Trouver un raw contact du contact agrégé
            var rawId: Long? = null
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { if (it.moveToFirst()) rawId = it.getLong(0) }
            val raw = rawId ?: return false

            val values = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, raw)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim())
                put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
        } catch (_: Exception) {
            false
        }
    }

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
