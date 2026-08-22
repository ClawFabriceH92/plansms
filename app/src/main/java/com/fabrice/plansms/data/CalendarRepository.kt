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
    val visible: Boolean,
    val ownerAccount: String = ""
)

/** Participant d'un événement (email + nom affiché si fourni par le calendrier). */
data class AttendeeInfo(val email: String, val name: String)

/**
 * RDV de demain avec participant : soit un contact est trouvé par email (phone non vide),
 * soit un rapprochement par nom est proposé (suggestion*), soit rien n'est trouvé.
 */
data class TomorrowRdv(
    val event: CalendarEvent,
    val email: String,               // email du participant retenu
    val attendeeName: String,        // nom du participant côté calendrier ("" si absent)
    val contactName: String,         // contact trouvé par email ("" si non trouvé)
    val phone: String,               // numéro du contact trouvé ("" si non trouvé)
    val suggestions: List<ContactsHelper.PhoneContact> = emptyList(), // candidats par nom (jamais auto-associés)
    val suggestionStrong: Boolean = false   // true = match prénom+nom sans ambiguïté ; false = doute → confirmation
)

/** Résultat de la lecture des RDV du jour cible (demain, ou lundi si on est vendredi/week-end). */
data class TomorrowRdvResult(
    val withEmail: List<TomorrowRdv>,
    val withoutEmailCount: Int,      // RDV du jour cible sans participant (ignorés)
    val targetStart: Long = 0        // minuit du jour cible (pour afficher « demain » / « lundi 25/08 »)
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
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.OWNER_ACCOUNT
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
                        visible = c.getInt(5) == 1,
                        ownerAccount = c.getString(6) ?: ""
                    )
                )
            }
        }
        return out
    }

    /**
     * Lecture des événements à venir via la table INSTANCES (occurrences entre deux dates).
     * Plus fiable que Events pour certains providers — méthode recommandée par Android.
     */
    fun readUpcomingEvents(context: Context, maxDays: Int = 30, limit: Int = 50): List<CalendarEvent> {
        val cals = readCalendars(context)
        val calNames = cals.associate { it.id to it.displayName }
        val hidden = CalendarPrefs.hiddenIds(context)
        val now = System.currentTimeMillis()
        val end = now + maxDays * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID
        )
        val out = mutableListOf<CalendarEvent>()
        val cursor = try {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val eventId = c.getLong(1)
                val calId = c.getLong(6)
                if (calId in hidden) continue   // calendrier masqué dans PlanSMS
                out.add(
                    CalendarEvent(
                        id = eventId,
                        title = c.getString(2) ?: "",
                        start = c.getLong(3),
                        end = c.getLong(4),
                        location = c.getString(5) ?: "",
                        attendees = readAttendees(context, eventId),
                        calendarName = calNames[calId] ?: ""
                    )
                )
            }
        }
        return out
    }

    /** Décompte d'événements par calendrier (diagnostic : où sont mes RDV ?). */
    fun eventCountsByCalendar(context: Context, maxDays: Int): List<Pair<CalendarInfo, Int>> {
        val cals = readCalendars(context)
        val now = System.currentTimeMillis()
        val end = now + maxDays * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val out = mutableListOf<Pair<CalendarInfo, Int>>()
        for (cal in cals) {
            var count = 0
            val cursor = try {
                context.contentResolver.query(
                    uri,
                    arrayOf(CalendarContract.Instances._ID),
                    "${CalendarContract.Instances.CALENDAR_ID} = ?",
                    arrayOf(cal.id.toString()),
                    null
                )
            } catch (e: Exception) { null }
            cursor?.use { c -> count = c.count }
            out.add(cal to count)
        }
        return out
    }

    private fun readAttendees(context: Context, eventId: Long): List<String> =
        readAttendeeInfos(context, eventId).map { it.email }

    private fun readAttendeeInfos(context: Context, eventId: Long): List<AttendeeInfo> {
        val out = mutableListOf<AttendeeInfo>()
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
                if (email.isNotBlank() && email.contains("@")) {
                    out.add(AttendeeInfo(email = email.trim(), name = c.getString(1)?.trim() ?: ""))
                }
            }
        }
        return out
    }

    /**
     * Jour cible des confirmations : demain en semaine, mais jamais le week-end —
     * vendredi → lundi (+3), samedi → lundi (+2), dimanche → lundi (+1).
     */
    fun nextTargetDayOffset(today: java.util.Calendar = java.util.Calendar.getInstance()): Int =
        when (today.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.FRIDAY -> 3
            java.util.Calendar.SATURDAY -> 2
            else -> 1
        }

    /**
     * RDV du jour cible (00:00 → 24:00) ayant au moins un participant avec email
     * (l'email du propriétaire du calendrier — toi — est ignoré).
     * Pour chaque RDV : contact trouvé par email, sinon rapprochement par nom/prénom
     * (simple proposition, jamais d'association automatique).
     * Les calendriers masqués dans PlanSMS sont exclus.
     */
    fun tomorrowMeetings(context: Context): TomorrowRdvResult {
        val cals = readCalendars(context)
        val hidden = CalendarPrefs.hiddenIds(context)
        val calNames = cals.associate { it.id to it.displayName }
        val owners = cals.map { it.ownerAccount.lowercase() }.filter { it.isNotBlank() }.toSet()

        val calStart = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, nextTargetDayOffset(this))
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = calStart.timeInMillis
        val end = start + 86_400_000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID
        )
        val withEmail = mutableListOf<TomorrowRdv>()
        var withoutEmail = 0
        var phoneContacts: List<ContactsHelper.PhoneContact>? = null   // chargés au premier besoin

        val cursor = try {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getLong(1)
                val calId = c.getLong(6)
                if (calId in hidden) continue
                val begin = c.getLong(3)
                if (begin < start || begin >= end) continue   // borne stricte sur demain
                val attendees = readAttendeeInfos(context, eventId)
                    .filter { it.email.lowercase() !in owners }
                if (attendees.isEmpty()) { withoutEmail++; continue }

                val event = CalendarEvent(
                    id = eventId,
                    title = c.getString(2) ?: "",
                    start = begin,
                    end = c.getLong(4),
                    location = c.getString(5) ?: "",
                    attendees = attendees.map { it.email },
                    calendarName = calNames[calId] ?: ""
                )

                // 1. Match par email dans le répertoire
                var matched: TomorrowRdv? = null
                for (a in attendees) {
                    val found = findContactPhone(context, a.email)
                    if (found != null) {
                        matched = TomorrowRdv(
                            event = event, email = a.email, attendeeName = a.name,
                            contactName = found.first, phone = found.second
                        )
                        break
                    }
                }
                if (matched != null) { withEmail.add(matched); continue }

                // 2. Rapprochement par nom/prénom (participant ou début d'email) — simple proposition
                val first = attendees.first()
                if (phoneContacts == null) phoneContacts = ContactsHelper.allPhoneContacts(context)
                val matches = ContactsHelper.suggestByName(phoneContacts!!, first.name, first.email)
                withEmail.add(
                    TomorrowRdv(
                        event = event, email = first.email, attendeeName = first.name,
                        contactName = "", phone = "",
                        suggestions = matches.map { it.contact },
                        suggestionStrong = ContactsHelper.isStrongMatch(matches)
                    )
                )
            }
        }
        return TomorrowRdvResult(withEmail = withEmail, withoutEmailCount = withoutEmail, targetStart = start)
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
