package com.fabrice.plansms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val sensitive: Boolean = false // réservé v0.2 (chiffrement)
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
    val status: String,            // SENT / FAILED / RATTRAPAGE
    val error: String = "",
    val sentAt: Long = System.currentTimeMillis()
)
