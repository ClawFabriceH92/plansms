package com.fabrice.plansms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Canal d'envoi d'un message programmé. */
enum class Channel {
    SMS,        // envoi automatique via SmsManager
    WHATSAPP    // semi-auto : notification → ouverture WhatsApp pré-remplie (envoi manuel final)
}

/** Types de répétition d'un message programmé. */
enum class RepeatRule {
    ONCE,        // one-shot
    DAILY,
    WEEKLY,      // jours précis (weekDays bitmask)
    MONTHLY,
    WEEKDAYS     // lundi-vendredi
}

/** Statut d'une programmation. */
enum class SmsStatus {
    SCHEDULED,   // programmé
    SENT,        // envoyé
    FAILED,      // échec (brouillon conservé)
    EXPIRED,     // one-shot dont l'heure est passée sans envoi
    CANCELLED
}

/**
 * Message SMS programmé.
 * L'heure cible est exprimée en minutes depuis minuit (0..1439).
 * noSendStart/noSendEnd : plage d'envoi interdite (minutes depuis minuit, ex. 22h→7h = 1320→420).
 * weekDays : bitmask 1=Lundi..7=Dimanche (utilisé si repeatRule == WEEKLY).
 */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val text: String,
    val targetDate: Long,          // epoch millis du premier envoi prévu (jour, heure du jour)
    val hourOfDay: Int,            // 0..23
    val minuteOfHour: Int,         // 0..59
    val repeatRule: RepeatRule = RepeatRule.ONCE,
    val weekDays: Int = 0,         // bitmask pour WEEKLY
    val noSendStart: Int = -1,     // -1 = désactivé
    val noSendEnd: Int = -1,
    val status: SmsStatus = SmsStatus.SCHEDULED,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String = "",
    val sensitive: Boolean = false, // réservé v0.2 (chiffrement)
    val channel: Channel = Channel.SMS, // v0.2 : canal d'envoi
    val groupId: Long = 0          // v0.2 : 0 = numéro direct, sinon groupe de contacts
)

/** Modèle de message réutilisable. */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Ligne du journal de bord (chaque tentative d'envoi). */
@Entity(tableName = "send_logs")
data class SendLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduledId: Long,
    val phone: String,
    val textPreview: String,
    val status: String,            // SENT / FAILED / RATTRAPAGE / WHATSAPP
    val error: String = "",
    val sentAt: Long = System.currentTimeMillis()
)

/** Groupe de contacts (clients, famille…). */
@Entity(tableName = "contact_groups")
data class ContactGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Membre d'un groupe. */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "phone"])
data class GroupMember(
    val groupId: Long,
    val phone: String,
    val name: String = ""
)

/**
 * Enregistrement vocal (dictaphone / appel en haut-parleur).
 * Le fichier audio est stocké dans le dossier privé de l'app ; seule la fiche est en base.
 */
@Entity(tableName = "voice_recordings")
data class VoiceRecording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val label: String = "",          // libellé libre ("Appel M. Dupont", "Réunion Teams"…)
    val phone: String = "",          // numéro associé si l'enregistrement suit un appel
    val source: String = "MIC",      // source audio utilisée
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val exportStatus: String = "",   // "" (local) / OK / ERREUR
    val exportInfo: String = "",     // destination atteinte, ou motif de l'échec
    val transcript: String = ""      // texte issu de la transcription (vide = non transcrit)
)

/**
 * Message entrant capté via les notifications (RCS / chat de Google Messages).
 * Ces messages ne sont PAS dans la base SMS d'Android : c'est la seule façon,
 * pour une app tierce, de savoir qu'un correspondant a écrit.
 */
@Entity(tableName = "inbound_messages")
data class InboundMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchKey: String,        // 9 derniers chiffres du numéro
    val address: String,         // numéro ou nom affiché par la notification
    val receivedAt: Long,
    val source: String,          // NOTIF
    val preview: String = ""
)

/** Règle d'auto-réponse (une seule, globale). */
@Entity(tableName = "auto_reply_rules")
data class AutoReplyRule(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val replyText: String = "Je ne peux pas répondre pour le moment, je vous recontacte dès que possible.",
    val mode: String = "ALL_EXCEPT",   // ALL_EXCEPT (tous sauf liste noire) / ONLY (liste blanche)
    val numbers: String = "",          // liste de numéros séparés par des virgules
    val delayMinutes: Int = 0,         // délai avant réponse
    val onlyWhenIdle: Boolean = false  // ne répondre que si téléphone inoccupé
)

// ---------------------------------------------------------------------------
// Relais SMS : transfert automatique des SMS reçus vers d'autres destinataires
// ---------------------------------------------------------------------------

/**
 * Créneau récurrent hebdomadaire pendant lequel le relais transfère.
 * [daysMask] : bit 0 = lundi … bit 6 = dimanche.
 * [startMin] / [endMin] : minutes depuis minuit, fin exclue.
 */
@Entity(tableName = "relay_slots")
data class RelaySlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val daysMask: Int,
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true
)

/**
 * Jour d'exception : force l'activation ou l'inactivation, quels que soient
 * les créneaux récurrents. [epochDay] = jour calendaire local.
 */
@Entity(tableName = "relay_exceptions")
data class RelayException(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val active: Boolean,
    val note: String = ""
)

/**
 * Un SMS reçu, à transférer. Sert à la fois de file d'attente et d'historique :
 * le statut dit où il en est.
 */
@Entity(tableName = "relay_items")
data class RelayItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val status: String = RelayStatus.QUEUED,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    val sentAt: Long = 0,
    val detail: String = "",     // destinations atteintes, ou motif de l'échec
    val origin: String = "SMS"   // SMS (broadcast) ou RCS (capture de notification)
)

object RelayStatus {
    const val QUEUED = "QUEUED"     // en attente (hors plage, ou nouvelle tentative à venir)
    const val SENT = "SENT"         // transféré à tous les destinataires
    const val PARTIAL = "PARTIAL"   // une partie des destinataires seulement
    const val FAILED = "FAILED"     // échec après toutes les tentatives
}
