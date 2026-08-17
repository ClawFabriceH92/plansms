package com.fabrice.plansms.data

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract

/** Événement de calendrier (lu depuis le CalendarProvider — Outlook synchronisé inclus). */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val location: String,
    val attendees: List<String>, // emails des participants
    val calendarName: String = ""
)

/** Informations sur un calendrier (diagnostic). */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val visible: Boolean
)

/** Lecture du calendrier + mapping participants → répertoire. */
object CalendarRepository {

    /** Liste les calendriers visibles par l'app (diagnostic synchro Outlook). */
    fun readCalendars(context: Context): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE
        )
        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalendarInfo(
                        id = c.getLong(0),
                        displayName = c.getString(1) ?: "",
                        accountName = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4),
                        visible = c.getInt(5) == 1
                    )
                )
            }
        }
        return out
    }

    /**
     * Lecture des événements à venir, calendrier par calendrier (contourne le filtre
     * de visibilité implicite : les événements des calendriers non sélectionnés
     * ne remontent pas avec une requête globale).
     */
    fun readUpcomingEvents(context: Context, maxDays: Int = 30, limit: Int = 50): List<CalendarEvent> {
        val cals = readCalendars(context)
        val out = mutableListOf<CalendarEvent>()
        val now = System.currentTimeMillis()
        val end = now + maxDays * 86_400_000L
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )
        for (cal in cals) {
            val cursor = try {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(now.toString(), end.toString(), cal.id.toString()),
                    "${CalendarContract.Events.DTSTART} ASC"
                )
            } catch (e: Exception) { null }
            cursor?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    out.add(
                        CalendarEvent(
                            id = id,
                            title = c.getString(1) ?: "",
                            start = c.getLong(2),
                            end = c.getLong(3),
                            location = c.getString(4) ?: "",
                            attendees = readAttendees(context, id),
                            calendarName = cal.displayName
                        )
                    )
                }
            }
        }
        return out.sortedBy { it.start }
    }

    /** Décompte d'événements par calendrier (diagnostic : où sont mes RDV ?). */
    fun eventCountsByCalendar(context: Context, maxDays: Int): List<Pair<CalendarInfo, Int>> {
        val cals = readCalendars(context)
        val now = System.currentTimeMillis()
        val end = now + maxDays * 86_400_000L
        val out = mutableListOf<Pair<CalendarInfo, Int>>()
        for (cal in cals) {
            var count = 0
            val cursor = try {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(now.toString(), end.toString(), cal.id.toString()),
                    null
                )
            } catch (e: Exception) { null }
            cursor?.use { c -> count = c.count }
            out.add(cal to count)
        }
        return out
    }

    private fun readAttendees(context: Context, eventId: Long): List<String> {
        val emails = mutableListOf<String>()
        val projection = arrayOf(
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_NAME
        )
        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                projection,
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val email = c.getString(0) ?: continue
                if (email.isNotBlank() && !email.equals(context.packageName, ignoreCase = true)) {
                    emails.add(email)
                }
            }
        }
        return emails
    }

    /** Mapping : email du participant → (nom, numéro) du contact Android. */
    fun findContactPhone(context: Context, email: String): Pair<String, String>? {
        val emailCursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Email.ADDRESS} = ?",
                arrayOf(email.trim()),
                null
            )
        } catch (e: Exception) { null }
        emailCursor?.use { c ->
            if (c.moveToFirst()) {
                val contactId = c.getLong(0)
                val name = c.getString(1) ?: ""
                val phone = findPhoneForContact(context, contactId)
                if (phone != null) return name to phone
            }
        }
        return null
    }

    private fun findPhoneForContact(context: Context, contactId: Long): String? {
        val phoneCursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )
        } catch (e: Exception) { null }
        phoneCursor?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }
}
