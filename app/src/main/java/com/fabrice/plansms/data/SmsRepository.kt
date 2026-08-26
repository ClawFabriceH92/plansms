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
    private val groupDao = db.contactGroupDao()
    private val memberDao = db.groupMemberDao()
    private val autoReplyDao = db.autoReplyRuleDao()
    private val recordingDao = db.voiceRecordingDao()

    fun observeMessages(): Flow<List<ScheduledMessage>> = msgDao.observeAll()
    fun observeTemplates(): Flow<List<Template>> = tmplDao.observeAll()
    fun observeLogs(): Flow<List<SendLog>> = logDao.observeRecent()
    fun observeGroups(): Flow<List<ContactGroup>> = groupDao.observeAll()
    fun observeMembers(groupId: Long): Flow<List<GroupMember>> = memberDao.observeMembers(groupId)
    fun observeAutoReply(): Flow<AutoReplyRule?> = autoReplyDao.observe()
    fun observeRecordings(): Flow<List<VoiceRecording>> = recordingDao.observeAll()

    suspend fun renameRecording(r: VoiceRecording, label: String) = recordingDao.update(r.copy(label = label))

    /** (Ré)envoie un enregistrement vers la destination configurée. */
    suspend fun exportRecording(r: VoiceRecording): String {
        val res = com.fabrice.plansms.export.RecordingExporter.export(
            context, java.io.File(r.filePath), r.label
        )
        recordingDao.update(
            r.copy(exportStatus = if (res.ok) "OK" else "ERREUR", exportInfo = res.message)
        )
        return res.message
    }

    /** Mémorise un texte transcrit (quelle qu'en soit la source). */
    suspend fun saveTranscript(r: VoiceRecording, text: String) =
        recordingDao.update(r.copy(transcript = text))

    /** Transcrit l'enregistrement via le serveur configuré et mémorise le texte. */
    suspend fun transcribeRecording(r: VoiceRecording): Transcriber.Result {
        val result = Transcriber.transcribe(context, java.io.File(r.filePath))
        if (result.ok) recordingDao.update(r.copy(transcript = result.text))
        return result
    }

    /** Supprime la fiche ET le fichier audio. */
    suspend fun deleteRecording(r: VoiceRecording) {
        try { java.io.File(r.filePath).delete() } catch (_: Exception) {}
        recordingDao.delete(r)
    }

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
    suspend fun addLog(log: SendLog) = logDao.insert(log)

    suspend fun addGroup(name: String): Long = groupDao.insert(ContactGroup(name = name))
    suspend fun deleteGroup(id: Long) = groupDao.deleteById(id)
    suspend fun addMembers(groupId: Long, members: List<GroupMember>) = memberDao.insertAll(members)
    suspend fun removeMember(groupId: Long, phone: String) = memberDao.delete(groupId, phone)
    suspend fun getMembers(groupId: Long): List<GroupMember> = memberDao.getMembers(groupId)

    suspend fun saveAutoReply(rule: AutoReplyRule) = autoReplyDao.upsert(rule)

    suspend fun rescheduleAll() {
        SmsScheduler.rescheduleAll(context, msgDao.getScheduled())
    }

    suspend fun importBackup(backup: com.fabrice.plansms.logic.JsonBackup.Backup) {
        for (m in backup.messages) addMessage(m)
        for (t in backup.templates) tmplDao.insert(t)
    }
}
