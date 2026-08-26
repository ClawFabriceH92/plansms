package com.fabrice.plansms.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fabrice.plansms.MainActivity
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.RelayItem
import com.fabrice.plansms.data.RelayStatus
import com.fabrice.plansms.scheduler.SmsSender
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Relais SMS : chaque SMS reçu est transféré aux destinataires configurés
 * (numéros et/ou emails), pendant les plages actives seulement. Hors plage,
 * il attend en file et part au prochain créneau.
 *
 * Rien ne quitte l'appareil en dehors des transferts eux-mêmes.
 */
object SmsRelay {

    private const val WORK_FLUSH = "plansms-relay-flush"
    private const val CHANNEL = "plansms_relay"

    /**
     * Un seul vidage de file à la fois : la réception d'un SMS, le démarrage de
     * l'app et le réveil planifié peuvent se déclencher en même temps, et un
     * message ne doit jamais partir deux fois.
     */
    private val gate = Mutex()

    /** Appelé à la réception d'un SMS. Met en file, et transfère si la plage est ouverte. */
    suspend fun onSmsReceived(context: Context, sender: String, body: String, receivedAt: Long) {
        if (!RelayPrefs.enabled(context)) return
        if (RelayPrefs.numbers(context).isEmpty() && RelayPrefs.emails(context).isEmpty()) {
            AppLogger.w("SmsRelay", "SMS reçu mais aucun destinataire configuré")
            return
        }
        // Anti-boucle : un SMS venant d'un destinataire du relais n'est pas relayé,
        // sinon deux téléphones se renvoient le même message indéfiniment.
        if (isOwnCircuit(context, sender)) {
            AppLogger.i("SmsRelay", "SMS de $sender ignoré (destinataire du relais)")
            return
        }

        val dao = AppDatabase.get(context).relayItemDao()
        val id = dao.insert(RelayItem(sender = sender, body = body, receivedAt = receivedAt))
        AppLogger.i("SmsRelay", "SMS de $sender mis en file (#$id)")
        flush(context)
    }

    /**
     * Transfère la file d'attente si la plage est ouverte, dans l'ordre
     * chronologique, puis réarme le prochain réveil.
     */
    suspend fun flush(context: Context) = gate.withLock {
        val db = AppDatabase.get(context)
        val queued = db.relayItemDao().queued()
        val now = System.currentTimeMillis()

        var retryAfter = 0L
        if (RelayPrefs.enabled(context) && queued.isNotEmpty() && isActiveNow(context, now)) {
            for (item in queued) {
                val wait = forward(context, item)
                if (wait > retryAfter) retryAfter = wait
            }
        }
        purge(context)
        // Un échec réseau se retente plus tard ; sinon on dort jusqu'au prochain créneau.
        if (retryAfter > 0) scheduleRetry(context, retryAfter) else scheduleNextWake(context)
    }

    /**
     * Envoie un message à tous les destinataires et met à jour son statut.
     * Retourne le délai (ms) après lequel retenter, ou 0 s'il n'y a rien à retenter.
     */
    private suspend fun forward(context: Context, item: RelayItem): Long {
        val dao = AppDatabase.get(context).relayItemDao()
        val numbers = RelayPrefs.numbers(context).filterNot { same(it, item.sender) }
        val emails = RelayPrefs.emails(context)
        val text = "De ${item.sender} : ${item.body}"
        val now = System.currentTimeMillis()

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (number in numbers) {
            val error = SmsSender.send(context, number, text)
            if (error == null) done += number else failed += "$number ($error)"
        }
        for (email in emails) {
            val error = RelayMailer.send(context, email, item.sender, item.body, item.receivedAt)
            if (error == null) done += email else failed += "$email ($error)"
        }

        val attempts = item.attempts + 1
        val maxAttempts = RelayPrefs.maxAttempts(context)
        val detail = buildString {
            if (done.isNotEmpty()) append("Envoyé : ").append(done.joinToString(", "))
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("Échec : ").append(failed.joinToString(", "))
            }
            if (isEmpty()) append("Aucun destinataire")
        }

        val status = when {
            failed.isEmpty() -> RelayStatus.SENT
            attempts < maxAttempts -> RelayStatus.QUEUED     // on retentera
            done.isNotEmpty() -> RelayStatus.PARTIAL
            else -> RelayStatus.FAILED
        }

        dao.update(
            item.copy(
                status = status,
                attempts = attempts,
                lastAttemptAt = now,
                sentAt = if (failed.isEmpty()) now else item.sentAt,
                detail = detail
            )
        )

        return when (status) {
            RelayStatus.QUEUED -> {
                AppLogger.w("SmsRelay", "Transfert incomplet (#${item.id}), tentative $attempts/$maxAttempts")
                retryDelay(attempts)
            }
            RelayStatus.PARTIAL, RelayStatus.FAILED -> {
                AppLogger.e("SmsRelay", "Transfert en échec (#${item.id}) : $detail")
                notifyFailure(context, item.sender, detail)
                0L
            }
            else -> {
                AppLogger.i("SmsRelay", "SMS de ${item.sender} relayé : $detail")
                0L
            }
        }
    }

    /** Recul croissant entre deux tentatives : 2 min, 10 min, puis 30 min. */
    private fun retryDelay(attempts: Int): Long = when (attempts) {
        1 -> 2 * 60_000L
        2 -> 10 * 60_000L
        else -> 30 * 60_000L
    }

    suspend fun isActiveNow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val db = AppDatabase.get(context)
        return RelaySchedule.isActive(now, slotsOf(db), exceptionsOf(db))
    }

    /** Prochain instant d'ouverture, ou null si aucun créneau n'est prévu. */
    suspend fun nextActiveAt(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val db = AppDatabase.get(context)
        return RelaySchedule.nextActiveAt(now, slotsOf(db), exceptionsOf(db))
    }

    /** Réveille l'app à la prochaine ouverture de créneau pour vider la file. */
    suspend fun scheduleNextWake(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!RelayPrefs.enabled(context)) {
            work.cancelUniqueWork(WORK_FLUSH)
            return
        }
        val db = AppDatabase.get(context)
        if (db.relayItemDao().queued().isEmpty()) {
            work.cancelUniqueWork(WORK_FLUSH)
            return
        }
        val now = System.currentTimeMillis()
        val next = RelaySchedule.nextActiveAt(now, slotsOf(db), exceptionsOf(db)) ?: return
        val delay = (next - now).coerceAtLeast(0L)
        work.enqueueUniqueWork(
            WORK_FLUSH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RelayWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        )
        AppLogger.i("SmsRelay", "File d'attente : réveil dans ${delay / 60_000} min")
    }

    /** Nouvelle tentative après un échec réseau. */
    private fun scheduleRetry(context: Context, delayMillis: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_FLUSH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RelayWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        )
        AppLogger.i("SmsRelay", "Nouvelle tentative dans ${delayMillis / 60_000} min")
    }

    private suspend fun purge(context: Context) {
        val days = RelayPrefs.retentionDays(context)
        if (days <= 0) return
        val before = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        AppDatabase.get(context).relayItemDao().purge(before)
        val today = java.time.LocalDate.now().toEpochDay()
        AppDatabase.get(context).relayExceptionDao().purge(today - 400)
    }

    private suspend fun slotsOf(db: AppDatabase): List<RelaySchedule.Slot> =
        db.relaySlotDao().getAll().map {
            RelaySchedule.Slot(it.daysMask, it.startMin, it.endMin, it.enabled)
        }

    private suspend fun exceptionsOf(db: AppDatabase): List<RelaySchedule.DayRule> =
        db.relayExceptionDao().from(java.time.LocalDate.now().toEpochDay() - 1)
            .map { RelaySchedule.DayRule(it.epochDay, it.active) }

    /** L'expéditeur est-il un destinataire du relais, ou la SIM de cet appareil ? */
    private fun isOwnCircuit(context: Context, sender: String): Boolean {
        val self = RelayPrefs.selfNumber(context)
        if (self.isNotBlank() && same(self, sender)) return true
        return RelayPrefs.numbers(context).any { same(it, sender) }
    }

    /** Deux numéros désignent-ils le même correspondant ? (9 derniers chiffres) */
    private fun same(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }.takeLast(9)
        val y = b.filter { it.isDigit() }.takeLast(9)
        return x.isNotEmpty() && x == y
    }

    private fun notifyFailure(context: Context, sender: String, detail: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Relais SMS", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = android.app.PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                4200,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Relais SMS : transfert en échec")
                    .setContentText("SMS de $sender non transféré")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            AppLogger.e("SmsRelay", "Notification d'échec impossible", e)
        }
    }
}
