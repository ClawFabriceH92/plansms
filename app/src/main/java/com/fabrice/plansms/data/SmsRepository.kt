package com.fabrice.plansms.data

import android.content.Context
import com.fabrice.plansms.logic.SmsRules
import com.fabrice.plansms.scheduler.SmsScheduler
import kotlinx.coroutines.flow.Flow

/** Point d'accès unique aux données + planification. */
class SmsRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val msgDao = db.scheduledMessageDao()
    private val tmplDao = db.templateDao()
    private val logDao = db.sendLogDao()

    fun observeMessages(): Flow<List<ScheduledMessage>> = msgDao.observeAll()
    fun observeTemplates(): Flow<List<Template>> = tmplDao.observeAll()
    fun observeLogs(): Flow<List<SendLog>> = logDao.observeRecent()

    suspend fun addMessage(msg: ScheduledMessage): Long {
        val id = msgDao.insert(msg)
        val saved = msg.copy(id = id)
        val now = System.currentTimeMillis()
        val next = SmsRules.nextOccurrence(saved, now)
        if (next != null) {
            val target = SmsRules.applyNoSendRange(next, saved, now)
            msgDao.update(saved.copy(targetDate = target))
            SmsScheduler.schedule(context, saved.copy(targetDate = target), target)
        } else {
            msgDao.update(saved.copy(status = SmsStatus.EXPIRED))
        }
        return id
    }

    suspend fun updateMessage(msg: ScheduledMessage) {
        msgDao.update(msg)
        SmsScheduler.cancel(context, msg.id)
        val now = System.currentTimeMillis()
        val next = SmsRules.nextOccurrence(msg, now)
        if (next != null) {
            val target = SmsRules.applyNoSendRange(next, msg, now)
            msgDao.update(msg.copy(targetDate = target, status = SmsStatus.SCHEDULED))
            SmsScheduler.schedule(context, msg.copy(targetDate = target), target)
        } else {
            msgDao.update(msg.copy(status = SmsStatus.EXPIRED))
        }
    }

    suspend fun deleteMessage(msg: ScheduledMessage) {
        SmsScheduler.cancel(context, msg.id)
        msgDao.delete(msg)
    }

    suspend fun addTemplate(t: Template) = tmplDao.insert(t)
    suspend fun updateTemplate(t: Template) = tmplDao.update(t)
    suspend fun deleteTemplate(t: Template) = tmplDao.delete(t)

    suspend fun clearLogs() = logDao.clear()

    suspend fun rescheduleAll() {
        SmsScheduler.rescheduleAll(context, msgDao.getScheduled())
    }

    suspend fun importBackup(backup: com.fabrice.plansms.logic.JsonBackup.Backup) {
        for (m in backup.messages) addMessage(m)
        for (t in backup.templates) tmplDao.insert(t)
    }
}
