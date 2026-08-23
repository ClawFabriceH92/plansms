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
    val createdAt: Long = System.currentTimeMillis()
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
